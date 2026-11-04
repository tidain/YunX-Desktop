// YunX-Desktop 便携版启动器（进程内 JVM + 启动闪屏）
//
// 职责：
// 1. 双击后 ~100ms 内渐显启动闪屏（应用图标 + 「正在启动…」），遮盖 JVM/Compose 启动延迟
// 2. 解析 app\YunX-Desktop.cfg，用 JNI 在【本进程内】创建 JVM 并调用主类
//    （不再另起 runtime\bin\java.exe 子进程 → 任务管理器只显示一个「云析 YunX-Desktop」进程）
// 3. 检测到应用主窗口后交叉淡出：闪屏淡出 + 应用窗口通过 WS_EX_LAYERED 由透明渐入，
//    实现无缝衔接
// 4. 固定读取 app\YunX-Desktop.cfg（不跟随 exe 名）→ 支持任意重命名；全程宽字符 API → 兼容中文路径
//
// 由打包脚本用 csc 编译为 winexe（无控制台窗口），并内嵌应用图标。

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace YunXDesktop
{
    static class Launcher
    {
        static string mainClass;
        static readonly List<string> javaOpts = new List<string>();
        static readonly List<string> classpath = new List<string>();
        static SplashForm splash;
        static int exitCode = 1;
        // JVM 是否已退出：供闪屏交叉淡出线程轮询（替代原先的 Process.HasExited）
        static volatile bool jvmExited;

        // ---- JNI 常量与函数表下标（x64 标准布局）----
        const int JNI_VERSION_1_8 = 0x00010008;
        const int JNI_OK = 0;
        // JNINativeInterface_（JNIEnv 函数表）下标
        const int JNI_FindClass = 6;
        const int JNI_ExceptionOccurred = 15;
        const int JNI_ExceptionDescribe = 16;
        const int JNI_ExceptionClear = 17;
        const int JNI_GetStaticMethodID = 113;
        const int JNI_CallStaticVoidMethodA = 143;
        const int JNI_NewString = 163;
        const int JNI_NewObjectArray = 172;
        const int JNI_SetObjectArrayElement = 174;
        // JNIInvokeInterface_（JavaVM 函数表）下标
        const int VM_DestroyJavaVM = 3;

        [STAThread]
        static int Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();

            // 诊断冒烟模式：无 UI、无闪屏
            if (Array.IndexOf(args, "--jcef-smoke") >= 0)
                return RunJava(args, false);

            // 启动闪屏跑在主线程消息循环；JVM 创建与窗口检测在后台线程
            // 配色跟随应用里「设置 → 主题与外观 → 深色模式」（跟随系统 / 浅色 / 深色）
            splash = new SplashForm(SplashForm.ResolveDark());
            var done = new ManualResetEvent(false);
            var worker = new Thread((ThreadStart)delegate
            {
                try { exitCode = RunJava(args, true); }
                finally { done.Set(); }
            });
            worker.IsBackground = true;
            worker.Start();

            Application.Run(splash);   // 渐显 + 消息循环；闪屏 Close 后返回
            done.WaitOne();
            return exitCode;
        }

        static int RunJava(string[] args, bool useSplash)
        {
            // 所有非托管内存记录在此，函数结束统一释放
            var allocations = new List<IntPtr>();
            try
            {
                // app-image 根目录 = 本 exe 所在目录（<root>\app、<root>\runtime）
                string root = AppDomain.CurrentDomain.BaseDirectory;

                string appDir = Path.Combine(root, "app");
                string runtimeBin = Path.Combine(root, "runtime", "bin");
                string jvmDll = Path.Combine(runtimeBin, "server", "jvm.dll");
                string cfgPath = Path.Combine(appDir, "YunX-Desktop.cfg");

                if (!File.Exists(jvmDll))
                {
                    Fail("未找到 Java 运行时：\n" + jvmDll);
                    return 1;
                }
                if (!File.Exists(cfgPath))
                {
                    Fail("未找到启动配置：\n" + cfgPath);
                    return 1;
                }

                ParseCfg(cfgPath);
                if (mainClass == null)
                {
                    Fail("启动配置中未指定主类（app.mainclass）");
                    return 1;
                }

                // 等效 jpackage 环境补充：原生库搜索路径（Skiko / JNA / SQLite 等）
                javaOpts.Add("-Djava.library.path=" + appDir);
                javaOpts.Add("-Dskiko.library.path=" + appDir);

                // JVM 硬崩溃（访问违规等）的现场 hs_err：固定落到用户本地目录。
                // 默认写「当前工作目录」，便携版若解压在只读位置（Program Files / 只读 U 盘）就写不进去，
                // 崩溃会彻底没有线索；应用启动时会从该目录把 hs_err 收进诊断包（DiagnosticLog.harvestJvmCrashLogs）。
                string crashDir = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                if (string.IsNullOrEmpty(crashDir)) crashDir = root;
                crashDir = Path.Combine(crashDir, "YunX-Desktop");
                try { Directory.CreateDirectory(crashDir); } catch { }
                javaOpts.Add("-XX:ErrorFile=" + Path.Combine(crashDir, "hs_err_pid%p.log"));
                // JNI 方式没有命令行 -cp，classpath 通过系统属性传入（等价 jpackage 的 -cp）
                javaOpts.Insert(0, "-Djava.class.path=" + string.Join(";", classpath.ToArray()));

                // 闪屏模式：java 侧窗口全透明启动、内容就绪后自行渐入（与闪屏淡出交叉）
                if (useSplash) Environment.SetEnvironmentVariable("YUNXPC_SPLASH", "1");

                // 工作目录与 java.exe 启动保持一致（<root>）
                Directory.SetCurrentDirectory(root);

                // 让 jvm.dll 的同目录依赖（java.dll / net.dll / msvcp140.dll ...）能在 runtime\bin 下解析
                SetDllDirectory(runtimeBin);

                IntPtr hJvm = LoadLibrary(jvmDll);
                if (hJvm == IntPtr.Zero)
                {
                    Fail("无法加载 jvm.dll（Win32 错误 " + Marshal.GetLastWin32Error() + "）：\n" + jvmDll);
                    return 1;
                }

                IntPtr pCreate = GetProcAddress(hJvm, "JNI_CreateJavaVM");
                IntPtr pGetDefault = GetProcAddress(hJvm, "JNI_GetDefaultJavaVMInitArgs");
                if (pCreate == IntPtr.Zero || pGetDefault == IntPtr.Zero)
                {
                    Fail("jvm.dll 缺少 JNI 调用接口（JNI_CreateJavaVM / JNI_GetDefaultJavaVMInitArgs）");
                    return 1;
                }
                var createVm = (JNI_CreateJavaVMDelegate)Marshal.GetDelegateForFunctionPointer(
                    pCreate, typeof(JNI_CreateJavaVMDelegate));
                var getDefaultArgs = (JNI_GetDefaultJavaVMInitArgsDelegate)Marshal.GetDelegateForFunctionPointer(
                    pGetDefault, typeof(JNI_GetDefaultJavaVMInitArgsDelegate));

                // ---- 组装 JavaVMInitArgs ----
                // x64 布局：version@0(4) nOptions@4(4) options@8(ptr) ignoreUnrecognized@16(jboolean) → 24 字节
                const int ARGS_SIZE = 24;
                IntPtr argsPtr = Alloc(allocations, ARGS_SIZE);
                for (int i = 0; i < ARGS_SIZE; i += 8) Marshal.WriteInt64(argsPtr, i, 0); // AllocHGlobal 不清零
                Marshal.WriteInt32(argsPtr, 0, JNI_VERSION_1_8);
                getDefaultArgs(argsPtr);                    // 取默认参数（版本/默认选项），失败也继续
                Marshal.WriteInt32(argsPtr, 0, JNI_VERSION_1_8);

                int n = javaOpts.Count;
                // JavaVMOption：optionString@0(ptr) extraInfo@8(ptr) → 16 字节
                IntPtr optionsPtr = Alloc(allocations, Math.Max(1, n) * 16);
                for (int i = 0; i < n; i++)
                {
                    // optionString 用 ANSI：与 JVM 在 Windows 上使用的原生路径编码（CP_ACP）一致
                    IntPtr s = Marshal.StringToHGlobalAnsi(javaOpts[i]);
                    allocations.Add(s);
                    Marshal.WriteIntPtr(optionsPtr, i * 16, s);
                    Marshal.WriteIntPtr(optionsPtr, i * 16 + 8, IntPtr.Zero);
                }
                Marshal.WriteInt32(argsPtr, 4, n);
                Marshal.WriteIntPtr(argsPtr, 8, optionsPtr);
                Marshal.WriteInt64(argsPtr, 16, 1);         // ignoreUnrecognized = JNI_TRUE

                // ---- 在本进程内创建 JVM ----
                IntPtr vmPtr = Alloc(allocations, IntPtr.Size);
                IntPtr envPtr = Alloc(allocations, IntPtr.Size);
                int rc = createVm(vmPtr, envPtr, argsPtr);
                if (rc != JNI_OK)
                {
                    Fail("创建 Java 虚拟机失败（JNI 错误码 " + rc + "）");
                    return 1;
                }
                IntPtr vm = Marshal.ReadIntPtr(vmPtr);
                IntPtr env = Marshal.ReadIntPtr(envPtr);

                // ---- 读取 JNIEnv 函数表，绑定要用到的 JNI 函数 ----
                IntPtr envTable = Marshal.ReadIntPtr(env);
                var findClass = Fn<FindClassDelegate>(envTable, JNI_FindClass);
                var getStaticMethodID = Fn<GetStaticMethodIDDelegate>(envTable, JNI_GetStaticMethodID);
                var callStaticVoidMethodA = Fn<CallStaticVoidMethodADelegate>(envTable, JNI_CallStaticVoidMethodA);
                var newString = Fn<NewStringDelegate>(envTable, JNI_NewString);
                var newObjectArray = Fn<NewObjectArrayDelegate>(envTable, JNI_NewObjectArray);
                var setObjectArrayElement = Fn<SetObjectArrayElementDelegate>(envTable, JNI_SetObjectArrayElement);
                var exceptionOccurred = Fn<ExceptionOccurredDelegate>(envTable, JNI_ExceptionOccurred);
                var exceptionDescribe = Fn<ExceptionDescribeDelegate>(envTable, JNI_ExceptionDescribe);
                var exceptionClear = Fn<ExceptionClearDelegate>(envTable, JNI_ExceptionClear);

                IntPtr mainKtClass = findClass(env, mainClass.Replace('.', '/'));
                if (mainKtClass == IntPtr.Zero)
                {
                    exceptionDescribe(env); exceptionClear(env);
                    Fail("未找到主类：" + mainClass);
                    return 1;
                }
                IntPtr mainMid = getStaticMethodID(env, mainKtClass, "main", "([Ljava/lang/String;)V");
                if (mainMid == IntPtr.Zero)
                {
                    exceptionDescribe(env); exceptionClear(env);
                    Fail("主类缺少 main(String[]) 方法：" + mainClass);
                    return 1;
                }

                // ---- 构造 String[] 实参：命令行参数透传到 main(args) ----
                IntPtr stringClass = findClass(env, "java/lang/String");
                IntPtr argArray = newObjectArray(env, args.Length, stringClass, IntPtr.Zero);
                for (int i = 0; i < args.Length; i++)
                {
                    // 用 NewString(jchar*) 传 UTF-16，天然支持中文等非 ASCII 参数
                    char[] cs = args[i].ToCharArray();
                    GCHandle pinned = GCHandle.Alloc(cs, GCHandleType.Pinned);
                    IntPtr js;
                    try { js = newString(env, pinned.AddrOfPinnedObject(), cs.Length); }
                    finally { pinned.Free(); }
                    setObjectArrayElement(env, argArray, i, js);
                }
                // jvalue 联合体（x64 为 8 字节）：仅一个变参槽，放 String[]
                IntPtr jvaluePtr = Alloc(allocations, 8);
                Marshal.WriteIntPtr(jvaluePtr, 0, argArray);

                // 闪屏模式：独立线程等待主窗口出现后淡出闪屏（本线程将阻塞在 main 调用上）
                var windowSeen = new bool[1];
                if (useSplash)
                {
                    var watcher = new Thread((ThreadStart)delegate { windowSeen[0] = CrossFade(); });
                    watcher.IsBackground = true;
                    watcher.Start();
                }

                // ---- 调用 com.yunx.app.MainKt.main(String[])（阻塞直到应用退出）----
                callStaticVoidMethodA(env, mainKtClass, mainMid, jvaluePtr);

                int code = 0;
                IntPtr pending = exceptionOccurred(env);
                if (pending != IntPtr.Zero)
                {
                    exceptionDescribe(env);   // 打印到 stderr
                    exceptionClear(env);
                    code = 1;
                }
                jvmExited = true;

                // DestroyJavaVM 阻塞至所有非守护线程结束，保证进程生命周期正确
                IntPtr vmTable = Marshal.ReadIntPtr(vm);
                var destroyVm = Fn<DestroyJavaVMDelegate>(vmTable, VM_DestroyJavaVM);
                destroyVm(vm);

                if (useSplash && code != 0 && !windowSeen[0])
                {
                    // 应用在主窗口出现前异常退出：提示而非静默消失
                    Fail("应用异常退出（代码 " + code + "）。");
                }
                return code;
            }
            catch (Exception ex)
            {
                Fail("启动失败：\n" + ex.Message);
                return 1;
            }
            finally
            {
                foreach (IntPtr p in allocations)
                {
                    try { Marshal.FreeHGlobal(p); } catch { }
                }
            }
        }

        static void ParseCfg(string cfgPath)
        {
            foreach (string raw in File.ReadAllLines(cfgPath, Encoding.UTF8))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("["))
                    continue;
                int eq = line.IndexOf('=');
                if (eq <= 0)
                    continue;
                string key = line.Substring(0, eq).Trim();
                string val = line.Substring(eq + 1).Trim();
                if (key.Equals("app.mainclass", StringComparison.OrdinalIgnoreCase))
                    mainClass = val;
                else if (key.Equals("app.classpath", StringComparison.OrdinalIgnoreCase))
                    classpath.Add(val.Replace("$APPDIR",
                        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app")));
                else if (key.Equals("java-options", StringComparison.OrdinalIgnoreCase))
                {
                    if (val.Length >= 2 && val.StartsWith("\"") && val.EndsWith("\""))
                        val = val.Substring(1, val.Length - 2);
                    javaOpts.Add(val);
                }
            }
        }

        /// 等待应用主窗口出现 → 闪屏淡出。
        /// 应用窗口的渐入由 java 侧自行完成（YUNXPC_SPLASH=1：窗口全透明显示，内容首帧就绪后渐入）。
        /// @return 是否检测到应用主窗口
        static bool CrossFade()
        {
            uint pid = GetCurrentProcessId();
            IntPtr hwnd = IntPtr.Zero;
            int waited = 0;
            while (waited < 45000)
            {
                if (jvmExited) break;
                hwnd = FindVisibleWindow(pid);
                if (hwnd != IntPtr.Zero) break;
                Thread.Sleep(15);
                waited += 15;
            }
            splash.BeginFadeOut();
            return hwnd != IntPtr.Zero;
        }

        /// 查找本进程内首个「可见且带标题」的顶层窗口（排除启动闪屏自身）
        static IntPtr FindVisibleWindow(uint pid)
        {
            IntPtr found = IntPtr.Zero;
            IntPtr splashHandle = splash != null ? splash.HandleValue : IntPtr.Zero;
            EnumWindows(delegate (IntPtr h, IntPtr l)
            {
                if (h == splashHandle) return true;   // 跳过启动闪屏
                uint wpid;
                GetWindowThreadProcessId(h, out wpid);
                if (wpid == pid && IsWindowVisible(h) && GetWindowTextLength(h) > 0)
                {
                    found = h;
                    return false;
                }
                return true;
            }, IntPtr.Zero);
            return found;
        }

        // ---- 非托管内存 / JNI 绑定小工具 ----
        static IntPtr Alloc(List<IntPtr> keep, int size)
        {
            IntPtr p = Marshal.AllocHGlobal(size);
            keep.Add(p);
            return p;
        }

        /// 从 JNI 函数表取第 index 个函数并转成委托
        static T Fn<T>(IntPtr table, int index)
        {
            IntPtr p = Marshal.ReadIntPtr(table, index * IntPtr.Size);
            return (T)(object)Marshal.GetDelegateForFunctionPointer(p, typeof(T));
        }

        static void Fail(string msg)
        {
            try
            {
                MessageBox.Show(msg, "云析 YunX-Desktop", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }

        // ---- JNI 委托（JNICALL = stdcall；x64 下与默认调用约定相同）----
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int JNI_CreateJavaVMDelegate(IntPtr pvm, IntPtr penv, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int JNI_GetDefaultJavaVMInitArgsDelegate(IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int DestroyJavaVMDelegate(IntPtr vm);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr FindClassDelegate(IntPtr env, [MarshalAs(UnmanagedType.LPStr)] string name);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr GetStaticMethodIDDelegate(IntPtr env, IntPtr clazz,
            [MarshalAs(UnmanagedType.LPStr)] string name, [MarshalAs(UnmanagedType.LPStr)] string sig);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void CallStaticVoidMethodADelegate(IntPtr env, IntPtr clazz, IntPtr methodID, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr NewStringDelegate(IntPtr env, IntPtr unicode, int len);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr NewObjectArrayDelegate(IntPtr env, int len, IntPtr elementClass, IntPtr initial);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void SetObjectArrayElementDelegate(IntPtr env, IntPtr array, int index, IntPtr val);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr ExceptionOccurredDelegate(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void ExceptionDescribeDelegate(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void ExceptionClearDelegate(IntPtr env);

        // ---- Win32 ----
        delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string lpFileName);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool SetDllDirectory(string lpPathName);
        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true, BestFitMapping = false)]
        static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);
        [DllImport("kernel32.dll")]
        static extern uint GetCurrentProcessId();

        [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
        [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr hWnd);
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    }

    /// 启动闪屏：Win11 圆角卡片，微渐变背景 + 流光进度条，缓动渐显。
    /// 配色跟随应用里的深色模式设置（见 [ResolveDark]），默认值退化到「跟随系统」。
    class SplashForm : Form
    {
        readonly Icon appIcon;
        readonly Font titleFont = new Font("Microsoft YaHei UI", 16.5f, FontStyle.Bold);
        readonly Font subFont = new Font("Microsoft YaHei UI", 9.5f);
        readonly Font hintFont = new Font("Microsoft YaHei UI", 9f);
        readonly System.Windows.Forms.Timer anim = new System.Windows.Forms.Timer();
        double fadeT;   // 渐显进度 0..1
        int tick;       // 动画帧（进度条相位）
        IntPtr handleValue;   // 窗口句柄（供窗口枚举线程排除闪屏自身）

        // 主题色：深 / 浅两套（强调色共用，与深色 UI 的柔和蓝一致）
        static readonly Color Accent = Color.FromArgb(0x5B, 0x8D, 0xFF);
        readonly Color bgTop, bgBottom, borderColor, titleColor, mutedColor, trackColor;

        public IntPtr HandleValue { get { return handleValue; } }

        /// <summary>
        /// 解析闪屏配色：读应用「设置 → 主题与外观」的深色模式。
        /// java.util.prefs 在 Windows 上落在 HKCU\Software\JavaSoft\Prefs\yunx\settings，
        /// dark_mode：0=跟随系统、1=浅色、2=深色。读不到按「跟随系统」处理。
        /// </summary>
        public static bool ResolveDark()
        {
            try
            {
                using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                    @"Software\JavaSoft\Prefs\yunx\settings"))
                {
                    string raw = key != null ? key.GetValue("dark_mode") as string : null;
                    if (raw == "1") return false;
                    if (raw == "2") return true;
                }
            }
            catch { }
            // 跟随系统：AppsUseLightTheme=0 表示系统「应用」为深色（读不到按浅色）
            try
            {
                using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    object v = key != null ? key.GetValue("AppsUseLightTheme") : null;
                    if (v is int) return (int)v == 0;
                }
            }
            catch { }
            return false;
        }

        public SplashForm(bool dark)
        {
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            ShowInTaskbar = false;
            TopMost = true;
            DoubleBuffered = true;
            Size = new Size(460, 280);
            if (dark)
            {
                BackColor = Color.FromArgb(27, 28, 31);
                bgTop = Color.FromArgb(38, 39, 44);
                bgBottom = Color.FromArgb(26, 27, 31);
                borderColor = Color.FromArgb(56, 58, 64);
                titleColor = Color.FromArgb(236, 236, 241);
                mutedColor = Color.FromArgb(154, 160, 166);
                trackColor = Color.FromArgb(44, 46, 51);
            }
            else
            {
                BackColor = Color.FromArgb(246, 247, 250);
                bgTop = Color.FromArgb(252, 252, 253);
                bgBottom = Color.FromArgb(243, 244, 247);
                borderColor = Color.FromArgb(224, 227, 233);
                titleColor = Color.FromArgb(26, 28, 32);
                mutedColor = Color.FromArgb(108, 115, 124);
                trackColor = Color.FromArgb(226, 229, 235);
            }
            Opacity = 0;

            string icoPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "YunX-Desktop.ico");
            try { appIcon = new Icon(icoPath, 96, 96); }
            catch
            {
                try { appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); }
                catch { appIcon = null; }
            }

            // 单一动画时钟：驱动渐显（easeOutCubic）与进度条相位
            anim.Interval = 16;
            anim.Tick += delegate
            {
                if (fadeT < 1)
                {
                    fadeT = Math.Min(1, fadeT + 16.0 / 340.0);
                    Opacity = 1 - Math.Pow(1 - fadeT, 3);
                }
                tick++;
                Invalidate();
            };
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            handleValue = Handle;
            base.OnHandleCreated(e);
            // Win11 圆角窗口（Win10 无此属性，静默忽略）
            try
            {
                int round = 2; // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND
                DwmSetWindowAttribute(Handle, 33, ref round, 4);
            }
            catch { }
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            anim.Start();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            int w = ClientSize.Width, h = ClientSize.Height;

            // 背景：竖向微渐变（深/浅两套配色，见构造函数）
            using (var bg = new System.Drawing.Drawing2D.LinearGradientBrush(
                new Rectangle(0, 0, 1, h), bgTop, bgBottom,
                System.Drawing.Drawing2D.LinearGradientMode.Vertical))
                g.FillRectangle(bg, 0, 0, w, h);

            // 1px 描边，勾出卡片轮廓（浅色下为浅灰，深色下为深灰）
            using (var pen = new Pen(borderColor))
                g.DrawRectangle(pen, 0, 0, w - 1, h - 1);

            // 应用图标
            if (appIcon != null)
                g.DrawIcon(appIcon, new Rectangle(w / 2 - 40, 40, 80, 80));

            // 标题：云析 / YunX-Desktop 两行
            var sz = g.MeasureString("云析", titleFont);
            using (var b = new SolidBrush(titleColor))
                g.DrawString("云析", titleFont, b, (w - sz.Width) / 2f, 126);
            var sz2 = g.MeasureString("YunX-Desktop", subFont);
            using (var b = new SolidBrush(mutedColor))
                g.DrawString("YunX-Desktop", subFont, b, (w - sz2.Width) / 2f, 160);

            // 流光进度条：轨道 + 两端羽化的移动高亮段
            int trackW = 200, trackH = 4, trackY = 202;
            int trackX = (w - trackW) / 2;
            using (var track = new SolidBrush(trackColor))
                FillRounded(g, track, trackX, trackY, trackW, trackH);

            double phase = (tick % 110) / 110.0;
            int segW = 76;
            var segRect = new RectangleF(
                trackX + (float)(-segW + phase * (trackW + segW)), trackY, segW, trackH);
            var state = g.Save();
            g.SetClip(new Rectangle(trackX, trackY - 1, trackW, trackH + 2));
            using (var seg = new System.Drawing.Drawing2D.LinearGradientBrush(
                segRect, Color.Empty, Color.Empty, 0f))
            {
                var blend = new System.Drawing.Drawing2D.ColorBlend(4);
                blend.Colors = new[]
                {
                    Color.FromArgb(0, Accent), Color.FromArgb(235, Accent),
                    Color.FromArgb(235, Accent), Color.FromArgb(0, Accent)
                };
                blend.Positions = new[] { 0f, 0.25f, 0.75f, 1f };
                seg.InterpolationColors = blend;
                g.FillRectangle(seg, segRect);
            }
            g.Restore(state);

            // 提示文字
            var hz = g.MeasureString("正在准备运行环境", hintFont);
            using (var b = new SolidBrush(mutedColor))
                g.DrawString("正在准备运行环境", hintFont, b, (w - hz.Width) / 2f, 220);
        }

        /// 填充胶囊形（两端半圆）小条
        static void FillRounded(Graphics g, Brush b, int x, int y, int w, int h)
        {
            using (var p = new System.Drawing.Drawing2D.GraphicsPath())
            {
                p.AddArc(x, y, h, h, 90, 180);
                p.AddArc(x + w - h, y, h, h, 270, 180);
                p.CloseFigure();
                g.FillPath(b, p);
            }
        }

        /// 淡出后自动关闭（可在任意线程调用）
        public void BeginFadeOut()
        {
            try
            {
                Invoke((MethodInvoker)delegate
                {
                    var t = new System.Windows.Forms.Timer { Interval = 16 };
                    t.Tick += delegate
                    {
                        if (Opacity <= 0.05) { t.Stop(); Close(); }
                        else Opacity = Opacity - 0.09;
                    };
                    t.Start();
                });
            }
            catch
            {
                try { Close(); } catch { }
            }
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    }
}
