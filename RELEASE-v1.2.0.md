# 云析桌面版 v1.2.0 发布说明

> 发布日期：2026-09-17
> 原安卓项目：[CYQawa/YunX](https://github.com/CYQawa/YunX) ｜ PC 移植版：[tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop)

## 亮点

v1.2.0 聚焦桌面端体验打磨与多平台稳定性修复：剪贴板分享链接智能检测、解析历史一键复用、下载完成「文件夹」定位、右键菜单材质统一，以及百度网盘 API 的连环修复。

## 新功能

### 剪贴板分享链接检测
- 复制网盘分享链接时，右下角自动弹出提示卡片（应用失焦时触发，不打扰当前操作）
- 卡片展示平台与文件名，点击「打开」一键切回应用并自动解析
- 10 秒自动消失；可在「设置 → 剪贴板分享链接检测」中关闭

### 解析链接历史
- 自动记录解析过的分享链接（同一链接 1 小时内去重）
- 历史弹窗支持按标题/链接搜索，复用提取码直接再次解析
- 支持单条删除与清空全部

### 下载完成「文件夹」按钮
- 已完成任务新增「文件夹」按钮，调用 `explorer /select` 在资源管理器中定位文件
- 与原有「打开文件」按钮并排，管理下载产物更便捷

### 右键菜单统一弹窗材质
- 下载任务右键菜单从原生 AlertDialog 改为窗口内覆盖层（FadeAlertDialog），消除原生窗口阻塞 UI 线程导致的卡顿
- 新增「复制分享链接」菜单项（解析来源的原始分享链接）

### 登出彻底清除 Cookie
- 退出网盘登录时，通过 CEF `CefCookieManager` 的 `delete` 标志清除该平台所有 Cookie
- 避免登出后 Cookie 残留导致的状态串扰

## 修复与优化

### 百度网盘 API 连环修复
- **域名迁移**：`yun.baidu.com` → `pan.baidu.com`（旧域名已不可用）
- **UA 替换**：网页接口统一使用桌面浏览器 UA（`UA_WEB`），仅 `locateDownload` 保留手机 UA（d.pcs.baidu.com 移动端直链接口要求）
- **bdstoken 自动刷新**：`errno=-6`（bdstoken 失效）时自动刷新并重试一次
- **限流重试**：`errno=8888`（请求过于频繁）时自动等待 2 秒重试，最多 3 次
- **超时收紧**：API 客户端 `readTimeout` 60s → 30s，新增 `callTimeout 45s`；下载连接池 `maxIdleConnections` 64 → 128

### 139 网盘登录兜底
- `JcefLoginPane` 新增 `anyOfKeys` 参数，139 网盘登录以 `authorization` 单键作为通过条件
- 解决仅 Web 端授权（无客户端 `token`）时无法登录的问题

### 下载启动失败修复（关键 Bug）
- 修复 `download_task` 表 INSERT 语句占位符数量错误（17 个 `?` 对应 15 列），导致解析后点击「开始下载」无反应
- 数据库迁移：旧库自动 `ALTER TABLE` 补 `shareUrl` 列

### 其他
- 下载直链弹窗从原生 `AlertDialog` 改为 `FadeAlertDialog`，修复桌面端按钮点击无响应
- 版本号统一更新至 `1.2.0`（`build.gradle.kts` / `UpdateChecker` / 打包脚本）
- 关于页与支持页新增「PC 移植版仓库」入口（`tidain/YunX-Desktop`），保留原安卓项目仓库
- 支持页新增两张赞赏码（原作者 CYQawa / 移植作者 tidain），可点击放大扫码

## 系统要求

- Windows 10 / 11 x64
- 首次启动自动下载 JDK 17 与 Gradle，无需手动配置环境

## 下载

前往 [GitHub Releases](https://github.com/tidain/YunX-Desktop/releases) 下载：
- **便携版**：解压即用，双击 `YunX-Desktop.exe`
- **安装版**：单文件安装程序，安装到 `%LOCALAPPDATA%\Programs\YunX-Desktop`（无需管理员权限）

## 反馈

使用中遇到问题或有功能建议，欢迎提交 [Issue](https://github.com/tidain/YunX-Desktop/issues)。

---

*本项目基于 [CYQawa/YunX](https://github.com/CYQawa/YunX) 移植，以 [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源。*
