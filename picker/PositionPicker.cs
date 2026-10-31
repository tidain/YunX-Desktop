// YunX-Desktop「主窗口位置」遮罩选择器（独立 C# 原生进程）
//
// 为什么不是 Compose：
//   Compose Desktop 的透明窗口在 Windows 上靠分层窗口逐像素 alpha 合成，而 OPENGL/ANGLE
//   交换链不把 alpha 交给分层窗口 —— 透明区一律被合成为不透明黑。SOFTWARE 后端正常但性能
//   代价太大。把遮罩整个搬出 Compose/OpenGL：GDI+ 画进一张 32bpp 预乘 ARGB 的 DIB，
//   再用 UpdateLayeredWindow 贴成逐像素透明的分层窗口 —— 遮罩背后就是【实时的桌面本身】，
//   不是快照、也没有任何黑底，遮罩期间在下面开别的软件照样看得见（透明像素还会自动穿透点击）。
//
// 与主程序的约定（长度单位均为 AWT 逻辑像素；物理像素 = 逻辑 × --scale）：
//   入参（命令行）：
//     --scale <float>    物理像素 / 逻辑像素（= Compose 的 density，125% 缩放下为 1.25）
//     --box-w <int>      站位框宽（逻辑像素）
//     --box-h <int>      站位框高（逻辑像素）
//     --x <int> --y <int> 站位框初始左上角（逻辑像素，AWT 屏幕坐标）
//     --color <RRGGBB>   主题主色
//     --reduce-motion 1  软件渲染 / 用户「减少动画」：去掉翻转弹簧与 hover 过渡，直接落位
//   出参（stdout，恰好一行；父进程按行读取）：
//     "SAVE <x> <y>"     用户点了「保存」，x/y 为逻辑像素
//     "CANCEL"           用户点了「取消」/按了 Esc / 直接关窗
//
// 由打包脚本 / run.ps1 用 csc 编译为 winexe（无控制台窗口；stdout 仍可被父进程重定向读取）。

using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace YunXDesktop
{
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            PickerForm.MakeDpiAware();
            Application.EnableVisualStyles();

            float scale = 1f;
            int boxW = 1100, boxH = 760, x = 0, y = 0;
            int rgb = 0x415F91;
            bool reduceMotion = false;

            for (int i = 0; i + 1 < args.Length; i++)
            {
                switch (args[i])
                {
                    case "--scale": float.TryParse(args[i + 1], NumberStyles.Float, CultureInfo.InvariantCulture, out scale); i++; break;
                    case "--box-w": int.TryParse(args[i + 1], out boxW); i++; break;
                    case "--box-h": int.TryParse(args[i + 1], out boxH); i++; break;
                    case "--x": int.TryParse(args[i + 1], out x); i++; break;
                    case "--y": int.TryParse(args[i + 1], out y); i++; break;
                    case "--color": int.TryParse(args[i + 1], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out rgb); i++; break;
                    case "--reduce-motion": reduceMotion = args[i + 1] != "0"; i++; break;
                }
            }
            if (scale <= 0.1f || scale > 8f) scale = 1f;

            var accent = Color.FromArgb(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);

            using (var form = new PickerForm(scale, boxW, boxH, x, y, accent, reduceMotion))
            {
                try
                {
                    form.Show();
                    Application.Run(form);
                }
                catch (Exception ex)
                {
                    Console.Out.WriteLine("CANCEL");
                    Console.Error.WriteLine(ex.ToString());
                    Console.Out.Flush();
                    return 1;
                }
                Console.Out.WriteLine(form.Saved
                    ? "SAVE " + form.ResultX.ToString(CultureInfo.InvariantCulture) + " "
                            + form.ResultY.ToString(CultureInfo.InvariantCulture)
                    : "CANCEL");
                Console.Out.Flush();
            }
            return 0;
        }
    }

    /// <summary>全屏逐像素透明遮罩：可拖动站位框 + 跟随翻转的「取消 / 保存」操作条。</summary>
    sealed class PickerForm : Form
    {
        // ---------- Win32 ----------
        const int WS_EX_LAYERED = 0x00080000;
        const int WS_EX_TOOLWINDOW = 0x00000080;
        const uint ULW_ALPHA = 0x00000002;
        const byte AC_SRC_OVER = 0x00;
        const byte AC_SRC_ALPHA = 0x01;

        const int SM_XVIRTUALSCREEN = 76;
        const int SM_YVIRTUALSCREEN = 77;
        const int SM_CXVIRTUALSCREEN = 78;
        const int SM_CYVIRTUALSCREEN = 79;

        [StructLayout(LayoutKind.Sequential)] struct POINT { public int x; public int y; }
        [StructLayout(LayoutKind.Sequential)] struct SIZE { public int cx; public int cy; }
        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        struct BLENDFUNCTION
        {
            public byte BlendOp, BlendFlags, SourceConstantAlpha, AlphaFormat;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct BITMAPINFOHEADER
        {
            public int biSize, biWidth, biHeight;
            public short biPlanes, biBitCount;
            public int biCompression, biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant;
        }

        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] static extern int GetSystemMetrics(int nIndex);
        [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr hWnd);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
        [DllImport("user32.dll", SetLastError = true)]
        static extern bool UpdateLayeredWindow(IntPtr hwnd, IntPtr hdcDst, ref POINT pptDst, ref SIZE psize,
            IntPtr hdcSrc, ref POINT pptSrc, int crKey, ref BLENDFUNCTION pblend, uint dwFlags);
        [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr hdc);
        [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr hdc, IntPtr h);
        [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr hdc);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
        [DllImport("gdi32.dll")]
        static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFOHEADER bmi, uint usage,
            out IntPtr bits, IntPtr section, uint offset);

        internal static void MakeDpiAware()
        {
            try { SetProcessDPIAware(); } catch { }
        }

        // ---------- 几何（全部物理像素） ----------
        readonly float s;
        readonly Color accent;
        readonly int vx, vy, vw, vh;       // 虚拟桌面（所有屏幕并集）
        readonly float boxW, boxH;
        float boxX, boxY;                  // 站位框左上角（屏幕坐标）

        // 操作条尺寸（逻辑像素 × s）
        readonly float barW, barH, gap, margin, pad, btnW, btnH, btnGap;

        // ---------- 绘制面 ----------
        Bitmap surface;
        Graphics canvas;
        IntPtr dib, bits;
        readonly Font titleFont, hintFont, coordFont, btnFont;
        readonly StringFormat center = new StringFormat
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center
        };

        // ---------- 交互 / 动画 ----------
        readonly Timer timer;
        readonly Stopwatch clock = Stopwatch.StartNew();
        bool dirty = true;

        int side;                          // 0=框下方 1=框上方 2=框内底部
        float barOffset, barVel;           // 翻转时相对目标位置的偏移（弹簧收敛到 0）
        bool animating;
        float barX, barY;
        readonly bool reduceMotion;        // 软件渲染 / 用户「减少动画」：装饰性动效一律去掉，直接落位
        readonly float[] hoverT = new float[2];   // 「取消 / 保存」的 hover 过渡量（0→1）
        bool hoverBusy;

        bool dragging;
        float dragDX, dragDY;
        int pressedButton = -1;            // 0=取消 1=保存
        int hotButton = -1;

        RectangleF btnCancel, btnSave;

        public bool Saved { get; private set; }
        public int ResultX { get; private set; }
        public int ResultY { get; private set; }

        public PickerForm(float scale, int boxWidthLogical, int boxHeightLogical,
                          int xLogical, int yLogical, Color accentColor, bool reduceMotion)
        {
            s = scale;
            accent = accentColor;
            this.reduceMotion = reduceMotion;

            vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
            vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
            vw = Math.Max(1, GetSystemMetrics(SM_CXVIRTUALSCREEN));
            vh = Math.Max(1, GetSystemMetrics(SM_CYVIRTUALSCREEN));

            boxW = Math.Max(80f, boxWidthLogical * s);
            boxH = Math.Max(80f, boxHeightLogical * s);
            boxX = xLogical * s;
            boxY = yLogical * s;
            ClampBox();

            barW = 206f * s;
            barH = 56f * s;
            gap = 12f * s;
            margin = 16f * s;
            pad = 9f * s;
            btnW = 88f * s;
            btnH = 38f * s;
            btnGap = 12f * s;

            // 窗口层
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            ShowInTaskbar = false;
            TopMost = true;
            KeyPreview = true;
            DoubleBuffered = true;
            AutoScaleMode = AutoScaleMode.None;
            SetStyle(ControlStyles.Opaque, true);
            SetBounds(vx, vy, vw, vh);
            Cursor = Cursors.SizeAll;

            var family = PickFontFamily();
            titleFont = new Font(family, 13f * s, FontStyle.Bold, GraphicsUnit.Point);
            hintFont = new Font(family, 10f * s, FontStyle.Regular, GraphicsUnit.Point);
            coordFont = new Font(family, 9f * s, FontStyle.Regular, GraphicsUnit.Point);
            btnFont = new Font(family, 10.5f * s, FontStyle.Regular, GraphicsUnit.Point);

            SetupSurface();

            timer = new Timer { Interval = 15 };
            timer.Tick += OnTick;
            timer.Start();
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= WS_EX_LAYERED | WS_EX_TOOLWINDOW;
                return cp;
            }
        }

        static string PickFontFamily()
        {
            foreach (var name in new[] { "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI" })
            {
                try
                {
                    using (var f = new FontFamily(name)) return name;
                }
                catch { }
            }
            return FontFamily.GenericSansSerif.Name;
        }

        // ---------- 逐像素透明绘制面 ----------

        void SetupSurface()
        {
            IntPtr hdcScreen = GetDC(IntPtr.Zero);
            IntPtr hdcMem = CreateCompatibleDC(hdcScreen);
            try
            {
                var bmi = new BITMAPINFOHEADER
                {
                    biSize = Marshal.SizeOf(typeof(BITMAPINFOHEADER)), // 40，无调色板
                    biWidth = vw,
                    biHeight = -vh,                                   // 负数 = 自上而下
                    biPlanes = 1,
                    biBitCount = 32,
                    biCompression = 0,                                // BI_RGB
                    biSizeImage = vw * vh * 4
                };
                dib = CreateDIBSection(hdcMem, ref bmi, 0, out bits, IntPtr.Zero, 0);
                if (dib == IntPtr.Zero) throw new InvalidOperationException("CreateDIBSection 失败");
                // 预乘 ARGB：UpdateLayeredWindow(AC_SRC_ALPHA) 要求源位图是预乘的
                surface = new Bitmap(vw, vh, vw * 4, System.Drawing.Imaging.PixelFormat.Format32bppPArgb, bits);
                canvas = Graphics.FromImage(surface);
            }
            finally
            {
                DeleteDC(hdcMem);
                ReleaseDC(IntPtr.Zero, hdcScreen);
            }
        }

        void Push()
        {
            if (canvas != null) canvas.Flush(FlushIntention.Flush);

            IntPtr hdcScreen = GetDC(IntPtr.Zero);
            IntPtr hdcMem = CreateCompatibleDC(hdcScreen);
            IntPtr oldDib = SelectObject(hdcMem, dib);
            try
            {
                var dst = new POINT { x = vx, y = vy };
                var size = new SIZE { cx = vw, cy = vh };
                var src = new POINT { x = 0, y = 0 };
                var blend = new BLENDFUNCTION
                {
                    BlendOp = AC_SRC_OVER,
                    BlendFlags = 0,
                    SourceConstantAlpha = 255,
                    AlphaFormat = AC_SRC_ALPHA
                };
                UpdateLayeredWindow(Handle, hdcScreen, ref dst, ref size, hdcMem, ref src, 0, ref blend, ULW_ALPHA);
            }
            finally
            {
                SelectObject(hdcMem, oldDib);
                DeleteDC(hdcMem);
                ReleaseDC(IntPtr.Zero, hdcScreen);
            }
            dirty = false;
        }

        protected override void OnPaintBackground(PaintEventArgs e) { }
        protected override void OnPaint(PaintEventArgs e) { }

        // ---------- 布局 ----------

        void ClampBox()
        {
            float maxX = vx + vw - boxW;
            float maxY = vy + vh - boxH;
            if (maxX < vx) maxX = vx;
            if (maxY < vy) maxY = vy;
            boxX = Math.Min(Math.Max(boxX, vx), maxX);
            boxY = Math.Min(Math.Max(boxY, vy), maxY);
        }

        /// <summary>某一侧对应的操作条 Y（缓冲区内坐标，screen 相对屏幕原点）</summary>
        float SideY(int which, float boxTop)
        {
            if (which == 0) return boxTop + boxH + gap;                    // 框下方
            if (which == 1) return boxTop - gap - barH;                    // 框上方
            float inside = boxTop + boxH - barH - 24f * s;                 // 框内底部
            return Math.Max(margin, inside);
        }

        /// <summary>
        /// 重算落位。上下翻转时用弹簧把「相对目标位置的偏移」从旧侧差值收敛到 0
        /// （阻尼比 0.92、固有频率 11 rad/s → 约 0.55s 安顿、几乎不过冲，比 MediumBouncy 柔和得多）；
        /// 其余切换直接对齐目标，拖拽时操作条严格跟手。
        /// </summary>
        void UpdateBar()
        {
            float boxTop = boxY - vy;
            float below = boxTop + boxH + gap;
            float above = boxTop - gap - barH;

            int next;
            if (below + barH <= vh - margin) next = 0;
            else if (above >= margin) next = 1;
            else next = 2;

            if (next != side)
            {
                int prev = side;
                float oldTarget = SideY(prev, boxTop);
                side = next;
                float newTarget = SideY(side, boxTop);
                bool flip = (prev == 0 && side == 1) || (prev == 1 && side == 0);
                if (flip && !reduceMotion)
                {
                    barOffset = oldTarget - newTarget;
                    barVel = 0f;
                    animating = true;
                }
                else
                {
                    barOffset = 0f;
                    barVel = 0f;
                    animating = false;
                }
            }

            if (animating)
            {
                double now = clock.Elapsed.TotalSeconds;
                float dt = (float)Math.Min(0.05, Math.Max(0.001, now - animLast));
                animLast = now;
                const float omega = 11f, zeta = 0.92f;
                float acc = -omega * omega * barOffset - 2f * zeta * omega * barVel;
                barVel += acc * dt;
                barOffset += barVel * dt;
                if (Math.Abs(barOffset) < 0.5f && Math.Abs(barVel) < 6f)
                {
                    barOffset = 0f;
                    barVel = 0f;
                    animating = false;
                }
            }

            barX = boxX - vx + (boxW - barW) / 2f;
            barY = SideY(side, boxTop) + barOffset;
        }

        double animLast;

        void OnTick(object sender, EventArgs e)
        {
            UpdateBar();
            StepHover();
            if (dirty || animating || hoverBusy) Render();
        }

        /// <summary>hover 过渡：降级动效时直接跳变，否则每帧指数逼近（约 4~5 帧到位）。</summary>
        void StepHover()
        {
            hoverBusy = false;
            for (int i = 0; i < 2; i++)
            {
                float target = hotButton == i ? 1f : 0f;
                if (reduceMotion) { hoverT[i] = target; continue; }
                float next = hoverT[i] + (target - hoverT[i]) * 0.3f;
                if (Math.Abs(target - next) < 0.004f) hoverT[i] = target;
                else { hoverT[i] = next; hoverBusy = true; }
            }
        }

        // ---------- 绘制 ----------

        static GraphicsPath Rounded(RectangleF r, float radius)
        {
            float d = Math.Min(radius * 2f, Math.Min(r.Width, r.Height));
            var p = new GraphicsPath();
            if (d <= 1f) { p.AddRectangle(r); return p; }
            p.AddArc(r.X, r.Y, d, d, 180f, 90f);
            p.AddArc(r.Right - d, r.Y, d, d, 270f, 90f);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0f, 90f);
            p.AddArc(r.X, r.Bottom - d, d, d, 90f, 90f);
            p.CloseFigure();
            return p;
        }

        void Render()
        {
            canvas.Clear(Color.Transparent);
            canvas.SmoothingMode = SmoothingMode.AntiAlias;
            canvas.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

            float ox = boxX - vx, oy = boxY - vy;

            // ---- 站位框 ----
            var boxRect = new RectangleF(ox + 1f, oy + 1f, boxW - 2f, boxH - 2f);
            using (var path = Rounded(boxRect, 12f * s))
            {
                using (var fill = new SolidBrush(Color.FromArgb(38, accent)))
                    canvas.FillPath(fill, path);
                using (var pen = new Pen(Color.FromArgb(230, accent), Math.Max(1.5f, 2f * s)))
                    canvas.DrawPath(pen, path);
            }

            // ---- 框内说明（居中三行；带 1px 暗色投影，保证任何壁纸上都读得清）----
            float cy = oy + boxH / 2f;
            string coordText = Math.Round(boxX / s) + ", " + Math.Round(boxY / s);
            DrawShadowed("主窗口位置", titleFont, accent, ox, cy - 34f * s, boxW, 26f * s);
            DrawShadowed("按住拖动到想要的位置", hintFont, Color.FromArgb(235, 255, 255, 255),
                ox, cy - 4f * s, boxW, 22f * s);
            DrawShadowed(coordText, coordFont, Color.FromArgb(200, 255, 255, 255),
                ox, cy + 22f * s, boxW, 20f * s);

            // ---- 操作条不再画任何底板 / 描边：两个按钮直接悬浮在桌面上（Fluent 的通透感）----
            float by = barY + (barH - btnH) / 2f;
            btnCancel = new RectangleF(barX + pad, by, btnW, btnH);
            btnSave = new RectangleF(barX + pad + btnW + btnGap, by, btnW, btnH);

            DrawButton(btnCancel, "取消", false);
            DrawButton(btnSave, "保存", true);

            Push();
        }

        /// <summary>用几层逐渐扩散、变淡的圆角矩形近似柔和投影（逐像素透明层拿不到 DWM 阴影）</summary>
        void DrawSoftShadow(RectangleF r, float radius, float depth, float alpha)
        {
            for (int i = 3; i >= 1; i--)
            {
                float grow = i * Math.Max(1f, s) * 0.85f;
                var rr = new RectangleF(
                    r.X - grow,
                    r.Y - grow * 0.30f + depth,
                    r.Width + grow * 2f,
                    r.Height + grow * 2f);
                int a = (int)(255f * alpha * (0.36f / i));
                if (a <= 0) continue;
                using (var path = Rounded(rr, radius + grow))
                using (var b = new SolidBrush(Color.FromArgb(a, 0, 0, 0)))
                    canvas.FillPath(b, path);
            }
        }

        void DrawShadowed(string text, Font font, Color color, float x, float y, float w, float h)
        {
            var rect = new RectangleF(x, y, w, h);
            var shadowRect = new RectangleF(x + Math.Max(1f, s), y + Math.Max(1f, s), w, h);
            using (var shadow = new SolidBrush(Color.FromArgb(160, 0, 0, 0)))
                canvas.DrawString(text, font, shadow, shadowRect, center);
            using (var brush = new SolidBrush(color))
                canvas.DrawString(text, font, brush, rect, center);
        }

        /// <summary>
        /// Fluent 风格胶囊按钮：纯色半透明填充，没有任何底板、渐变、内描边或高光。
        /// 次按钮＝极淡半透明白（alpha 30，悬浮 50）；主按钮＝半透明主题蓝（alpha 180，悬浮 220）。
        /// 圆角取 高/2 得到纯正胶囊形；底部一层极轻柔的黑色投影（约 alpha 20）把按钮从桌面上托起来。
        /// </summary>
        void DrawButton(RectangleF rect, string label, bool primary)
        {
            int idx = primary ? 1 : 0;
            float h = hoverT[idx];
            if (pressedButton == idx)
                rect = new RectangleF(rect.X, rect.Y + Math.Max(1f, s), rect.Width, rect.Height);

            float radius = rect.Height / 2f;      // 胶囊形
            DrawSoftShadow(rect, radius, Math.Max(1f, s) * 1.2f, 0.22f);   // 峰值约 20 alpha

            Color fill = primary
                ? Color.FromArgb((int)(180 + 40 * h), accent)          // 果冻蓝：180 → 悬浮 220
                : Color.FromArgb((int)(30 + 20 * h), 255, 255, 255);   // 极淡白：30 → 悬浮 50

            using (var path = Rounded(rect, radius))
            {
                using (var brush = new SolidBrush(fill))
                    canvas.FillPath(brush, path);
                using (var pen = new Pen(Color.FromArgb(40, 255, 255, 255), Math.Max(1f, s)))
                    canvas.DrawPath(pen, path);                        // 1px、alpha 40 的极细白边
            }

            using (var brush = new SolidBrush(primary
                    ? ReadableOn(accent) : Color.FromArgb(235, 255, 255, 255)))
                canvas.DrawString(label, btnFont, brush, rect, center);
        }

        /// <summary>在给定底色上可读的前景色（浅底压深字、深底用白字），与 Kotlin 侧 readableSeedForeground 同口径</summary>
        static Color ReadableOn(Color bg)
        {
            float lum = (0.2126f * bg.R + 0.7152f * bg.G + 0.0722f * bg.B) / 255f;
            return lum > 0.6f ? Color.FromArgb(255, 17, 19, 24) : Color.FromArgb(255, 255, 255, 255);
        }

        // ---------- 交互 ----------

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            UpdateBar();
            Render();
            Activate();
            Focus();
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button != MouseButtons.Left) return;
            var p = new PointF(e.X, e.Y);
            if (btnCancel.Contains(p)) { pressedButton = 0; dirty = true; return; }
            if (btnSave.Contains(p)) { pressedButton = 1; dirty = true; return; }
            if (p.X >= boxX - vx && p.X <= boxX - vx + boxW &&
                p.Y >= boxY - vy && p.Y <= boxY - vy + boxH)
            {
                dragging = true;
                dragDX = p.X - (boxX - vx);
                dragDY = p.Y - (boxY - vy);
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            var p = new PointF(e.X, e.Y);

            if (dragging)
            {
                boxX = vx + p.X - dragDX;
                boxY = vy + p.Y - dragDY;
                ClampBox();
                dirty = true;
                return;
            }

            int hot = btnCancel.Contains(p) ? 0 : (btnSave.Contains(p) ? 1 : -1);
            if (hot != hotButton)
            {
                hotButton = hot;
                Cursor = hot >= 0 ? Cursors.Hand : Cursors.SizeAll;
                dirty = true;
            }
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (e.Button != MouseButtons.Left) return;

            if (pressedButton >= 0)
            {
                var p = new PointF(e.X, e.Y);
                var rect = pressedButton == 0 ? btnCancel : btnSave;
                int pressed = pressedButton;
                pressedButton = -1;
                dirty = true;
                if (rect.Contains(p))
                {
                    if (pressed == 0) Cancel(); else Save();
                }
                return;
            }
            dragging = false;
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (e.KeyCode == Keys.Escape) { Cancel(); e.Handled = true; }
            else if (e.KeyCode == Keys.Enter) { Save(); e.Handled = true; }
        }

        void Save()
        {
            Saved = true;
            ResultX = (int)Math.Round(boxX / s);
            ResultY = (int)Math.Round(boxY / s);
            Close();
        }

        void Cancel()
        {
            Saved = false;
            Close();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                if (timer != null) { timer.Stop(); timer.Dispose(); }
                if (canvas != null) canvas.Dispose();
                if (surface != null) surface.Dispose();
                if (dib != IntPtr.Zero) { DeleteObject(dib); dib = IntPtr.Zero; }
                if (titleFont != null) titleFont.Dispose();
                if (hintFont != null) hintFont.Dispose();
                if (coordFont != null) coordFont.Dispose();
                if (btnFont != null) btnFont.Dispose();
                if (center != null) center.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}