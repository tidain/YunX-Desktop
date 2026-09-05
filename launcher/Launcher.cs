// YunX-Desktop 便携版启动器（带启动闪屏）
//
// 职责：
// 1. 双击后 ~100ms 内渐显启动闪屏（应用图标 + 「正在启动…」），遮盖 JVM/Compose 启动延迟
// 2. 解析 app\YunX-Desktop.cfg 并拉起 runtime\bin\java.exe
// 3. 检测到应用主窗口后交叉淡出：闪屏淡出 + 应用窗口通过 WS_EX_LAYERED 由透明渐入，
//    实现无缝衔接
// 4. 固定读取 app\YunX-Desktop.cfg（不跟随 exe 名）→ 支持任意重命名；全程宽字符 API → 兼容中文路径
//
// 由打包脚本用 csc 编译为 winexe（无控制台窗口），并内嵌应用图标。

using System;
using System.Collections.Generic;
using System.Diagnostics;
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

        [STAThread]
        static int Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();

            // 诊断冒烟模式：无 UI、无闪屏
            if (Array.IndexOf(args, "--jcef-smoke") >= 0)
                return RunJava(args, false);

            // 启动闪屏跑在主线程消息循环；java 启动与窗口检测在后台线程
            splash = new SplashForm();
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
            try
            {
                // app-image 根目录 = 本 exe 所在目录（<root>\app、<root>\runtime）
                string root = AppDomain.CurrentDomain.BaseDirectory;

                string appDir = Path.Combine(root, "app");
                string javaExe = Path.Combine(root, "runtime", "bin", "java.exe");
                string cfgPath = Path.Combine(appDir, "YunX-Desktop.cfg");

                if (!File.Exists(javaExe))
                {
                    Fail("未找到 Java 运行时：\n" + javaExe);
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

                var sb = new StringBuilder();
                foreach (string opt in javaOpts)
                    sb.Append(Quote(opt)).Append(' ');
                sb.Append(Quote("-cp")).Append(' ');
                sb.Append(Quote(string.Join(";", classpath.ToArray())));
                sb.Append(' ').Append(mainClass);
                foreach (string a in args)
                    sb.Append(' ').Append(Quote(a));

                var psi = new ProcessStartInfo
                {
                    FileName = javaExe,
                    Arguments = sb.ToString(),
                    UseShellExecute = false,
                    WorkingDirectory = root,
                    CreateNoWindow = true
                };
                // 闪屏模式：java 侧窗口全透明启动、内容就绪后自行渐入（与闪屏淡出交叉）
                if (useSplash) psi.EnvironmentVariables["YUNXPC_SPLASH"] = "1";

                using (Process p = Process.Start(psi))
                {
                    bool windowSeen = false;
                    if (useSplash) windowSeen = CrossFade(p);
                    p.WaitForExit();
                    int code = p.ExitCode;
                    if (useSplash && code != 0 && !windowSeen)
                    {
                        // 应用在主窗口出现前异常退出：提示而非静默消失
                        Fail("应用异常退出（代码 " + code + "）。");
                    }
                    return code;
                }
            }
            catch (Exception ex)
            {
                Fail("启动失败：\n" + ex.Message);
                return 1;
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

        /// 等待应用主窗口出现 → 闪屏淡出 + 应用窗口透明渐入（交叉衔接）
        /// @return 是否检测到应用主窗口
        static bool CrossFade(Process p)
        {
            uint pid = (uint)p.Id;
            IntPtr hwnd = IntPtr.Zero;
            int waited = 0;
            while (waited < 45000)
            {
                if (p.HasExited) break;
                hwnd = FindVisibleWindow(pid);
                if (hwnd != IntPtr.Zero) break;
                Thread.Sleep(15);
                waited += 15;
            }

            if (hwnd != IntPtr.Zero)
            {
                // 主窗口已出现：只需淡出闪屏。主窗口的渐入由 java 侧自行完成
                // （YUNXPC_SPLASH=1：窗口全透明显示，内容首帧就绪后渐入）。
                // 此前由启动器驱动渐入，但它无法感知内容何时真正上屏——淡入结束时
                // 内容才出现，界面表现为"忽然弹出"。
                splash.BeginFadeOut();
                return true;
            }
            // 窗口迟迟未出现（慢盘解包等）：直接撤掉闪屏，等待进程结果
            splash.BeginFadeOut();
            return false;
        }

        static IntPtr FindVisibleWindow(uint pid)
        {
            IntPtr found = IntPtr.Zero;
            EnumWindows(delegate (IntPtr h, IntPtr l)
            {
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

        static string Quote(string s)
        {
            var sb = new StringBuilder("\"");
            foreach (char c in s)
            {
                if (c == '"') sb.Append("\\\"");
                else sb.Append(c);
            }
            sb.Append('"');
            return sb.ToString();
        }

        static void Fail(string msg)
        {
            try
            {
                MessageBox.Show(msg, "云析 YunX-Desktop", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }

        // ---- Win32 ----
        delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
        [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr hWnd);
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
        [DllImport("user32.dll")] static extern bool SetLayeredWindowAttributes(IntPtr hWnd, uint crKey, byte alpha, uint dwFlags);
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        const int GWL_EXSTYLE = -20;
        const int WS_EX_LAYERED = 0x00080000;
        const uint LWA_ALPHA = 2;
    }

    /// 启动闪屏：Win11 圆角深色卡片，微渐变背景 + 流光进度条，缓动渐显
    class SplashForm : Form
    {
        readonly Icon appIcon;
        readonly Font titleFont = new Font("Microsoft YaHei UI", 16.5f, FontStyle.Bold);
        readonly Font subFont = new Font("Microsoft YaHei UI", 9.5f);
        readonly Font hintFont = new Font("Microsoft YaHei UI", 9f);
        readonly System.Windows.Forms.Timer anim = new System.Windows.Forms.Timer();
        double fadeT;   // 渐显进度 0..1
        int tick;       // 动画帧（进度条相位）

        // 主题色：与深色 UI 一致的底色 + 柔和蓝的强调色
        static readonly Color Accent = Color.FromArgb(0x5B, 0x8D, 0xFF);

        public SplashForm()
        {
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            ShowInTaskbar = false;
            TopMost = true;
            DoubleBuffered = true;
            Size = new Size(460, 280);
            BackColor = Color.FromArgb(27, 28, 31);
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

            // 背景：竖向微渐变
            using (var bg = new System.Drawing.Drawing2D.LinearGradientBrush(
                new Rectangle(0, 0, 1, h), Color.FromArgb(38, 39, 44), Color.FromArgb(26, 27, 31),
                System.Drawing.Drawing2D.LinearGradientMode.Vertical))
                g.FillRectangle(bg, 0, 0, w, h);

            // 1px 描边，在深色桌面上勾出卡片轮廓
            using (var pen = new Pen(Color.FromArgb(56, 58, 64)))
                g.DrawRectangle(pen, 0, 0, w - 1, h - 1);

            // 应用图标
            if (appIcon != null)
                g.DrawIcon(appIcon, new Rectangle(w / 2 - 40, 40, 80, 80));

            // 标题：云析 / YunX-Desktop 两行
            var sz = g.MeasureString("云析", titleFont);
            using (var b = new SolidBrush(Color.FromArgb(236, 236, 241)))
                g.DrawString("云析", titleFont, b, (w - sz.Width) / 2f, 126);
            var sz2 = g.MeasureString("YunX-Desktop", subFont);
            using (var b = new SolidBrush(Color.FromArgb(154, 160, 166)))
                g.DrawString("YunX-Desktop", subFont, b, (w - sz2.Width) / 2f, 160);

            // 流光进度条：轨道 + 两端羽化的移动高亮段
            int trackW = 200, trackH = 4, trackY = 202;
            int trackX = (w - trackW) / 2;
            using (var track = new SolidBrush(Color.FromArgb(44, 46, 51)))
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
            using (var b = new SolidBrush(Color.FromArgb(154, 160, 166)))
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
