/**
 * 云析下载助手 —— 后台服务（MV3 Service Worker）
 *
 * 职责：
 *  1. 嗅探：用 webRequest.onHeadersReceived 只读观察响应头，记录可下载资源（体积/MIME/文件名）；
 *  2. 接管：chrome.downloads.onCreated → cancel + erase，把请求 POST 到云析本地回环桥，
 *     由云析弹窗询问「保存到哪里 / 用多少线程」，决定后再分流处理；
 *  3. 归还：用户在云析弹窗点「归还浏览器下载」时，用 chrome.downloads.download 重新发起，
 *     并对该根域名（eTLD+1）写入 5 分钟冷却，冷却期内不再接管该域名及以下子域名的下载。
 *
 * 与云析的通信：云析在本机 127.0.0.1 的 37021-37030 区间监听（见 App 侧 BrowserBridgeServer）。
 * 扩展逐个端口 GET /ping 探测，命中后拿到本次进程的随机 token；之后 POST /hook 带 token 提交请求。
 * 云析没运行时探测不到 → 完全不接管，浏览器下载照常。
 */

'use strict';

// ---------------------------------------------------------------- 常量

const PORT_START = 37021;
const PORT_END = 37030;

/** 归还浏览器下载后的冷却时长（毫秒）：该根域名及以下 5 分钟内不再接管 */
const COOLDOWN_MS = 5 * 60 * 1000;

/** 常见二段后缀（eTLD+1 启发式用）：命中时根域名取最后三段 */
const TWO_LEVEL_TLDS = new Set([
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn', 'ac.cn',
  'co.uk', 'org.uk', 'gov.uk', 'ac.uk', 'me.uk',
  'co.jp', 'ne.jp', 'or.jp', 'ac.jp',
  'com.hk', 'com.tw', 'com.au', 'net.au', 'org.au',
  'com.sg', 'com.my', 'com.ph', 'com.vn', 'com.id', 'com.th',
  'co.kr', 'co.in', 'co.nz', 'co.za', 'co.il',
  'com.br', 'com.mx', 'com.ar', 'com.tr', 'com.ua', 'com.ru',
]);

/** 可下载资源的扩展名特征 */
const DOWNLOAD_EXT_RE = new RegExp(
  '\\.(mp4|m4v|mkv|webm|flv|f4v|mov|avi|wmv|ts|m3u8|mpd|mp3|m4a|aac|flac|wav|ogg|opus|' +
  'zip|rar|7z|tar|gz|bz2|xz|iso|exe|msi|apk|ipa|dmg|pkg|deb|rpm|' +
  'pdf|epub|mobi|azw3|doc|docx|xls|xlsx|ppt|pptx|torrent|img|bin|dat|jar|crx|' +
  'ttf|otf|woff|woff2)(\\?|#|$)', 'i');

/** 视为可下载的 MIME */
const DOWNLOAD_MIME_RE = new RegExp(
  '^(video/|audio/|font/|application/(octet-stream|x-msdownload|x-msdos-program|' +
  'x-7z-compressed|x-rar-compressed|x-tar|x-gzip|zip|x-bittorrent|x-apple-diskimage|' +
  'vnd\\.android\\.package-archive|vnd\\.apple\\.installer|pdf|epub\\+zip|' +
  'msword|vnd\\.openxmlformats-officedocument|vnd\\.ms-excel|vnd\\.ms-powerpoint))', 'i');

const DEFAULT_SETTINGS = { enabled: true, hijack: true, sniff: true };

// ---------------------------------------------------------------- 资源分类

/** 分类显示名（popup 侧同步用） */
const CATEGORY_LABELS = {
  image: '图片', video: '视频', audio: '音频', document: '文档',
  archive: '压缩包', program: '程序', font: '字体', other: '其他',
};

/** MIME → 分类（优先） */
const MIME_CATEGORY = [
  [/^image\//, 'image'],
  [/^video\//, 'video'],
  [/^audio\//, 'audio'],
  [/^font\//, 'font'],
  [/^application\/(pdf|epub\+zip|rtf|msword|vnd\.openxmlformats-officedocument|vnd\.ms-excel|vnd\.ms-powerpoint|json|xml)|^text\//, 'document'],
  [/^application\/(zip|x-7z-compressed|x-rar-compressed|x-tar|x-gzip|x-bzip2|x-xz-compressed|x-iso9660-image|x-bittorrent|x-zstd)/, 'archive'],
  [/^application\/(octet-stream|x-msdownload|x-msdos-program|vnd\.android\.package-archive|vnd\.apple\.installer|x-apple-diskimage|x-deb|java-archive|x-chrome-extension)/, 'program'],
];

/** 扩展名 → 分类（MIME 拿不到时的兜底） */
const EXT_CATEGORY = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif', 'tiff', 'heic'],
  video: ['mp4', 'm4v', 'mkv', 'webm', 'flv', 'f4v', 'mov', 'avi', 'wmv', 'ts', 'm3u8', 'mpd', '3gp', 'mpg', 'mpeg', 'rmvb'],
  audio: ['mp3', 'm4a', 'aac', 'flac', 'wav', 'ogg', 'opus', 'wma', 'ape', 'amr'],
  document: ['pdf', 'epub', 'mobi', 'azw3', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md', 'csv', 'rtf'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso', 'torrent', 'zst', 'lz4'],
  program: ['exe', 'msi', 'apk', 'ipa', 'dmg', 'pkg', 'deb', 'rpm', 'bat', 'sh', 'jar', 'crx', 'appimage'],
  font: ['ttf', 'otf', 'woff', 'woff2', 'eot'],
};

/** 从 URL 路径的扩展名推断分类；取不到返回空串 */
function categoryFromExt(url) {
  const clean = String(url || '').split('?')[0].split('#')[0];
  const seg = clean.substring(clean.lastIndexOf('/') + 1);
  const dot = seg.lastIndexOf('.');
  if (dot < 0) return '';
  const ext = seg.substring(dot + 1).toLowerCase();
  for (const cat in EXT_CATEGORY) {
    if (EXT_CATEGORY[cat].indexOf(ext) >= 0) return cat;
  }
  return '';
}

/** 资源分类：MIME 优先，扩展名兜底，都拿不到归 other */
function categorize(mime, url) {
  const m = String(mime || '').split(';')[0].trim().toLowerCase();
  if (m) {
    for (const pair of MIME_CATEGORY) {
      if (pair[0].test(m)) return pair[1];
    }
  }
  return categoryFromExt(url) || 'other';
}

// ---------------------------------------------------------------- 状态

/** { port, token } | null */
let bridge = null;
/** 每个标签页嗅探到的资源：tabId -> [entry] */
const tabResources = new Map();
/** url -> entry（供接管时补全大小/MIME） */
const sniffByUrl = new Map();

// ---------------------------------------------------------------- 小工具

const log = (...a) => console.log('[YunX]', ...a);

/** service worker 保活：长时间等待云析弹窗时定期调用一个扩展 API 重置空闲计时器 */
function keepAlive() {
  const id = setInterval(() => {
    try { chrome.runtime.getPlatformInfo(() => void chrome.runtime.lastError); } catch (e) { /* ignore */ }
  }, 15000);
  return { stop: () => clearInterval(id) };
}

/** 根域名（eTLD+1）提取：朴素启发式 + 二段后缀白名单 */
function rootDomainOf(host) {
  if (!host) return '';
  host = String(host).toLowerCase().replace(/\.$/, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return host; // IPv4 原样
  if (host.startsWith('[')) return host;                 // IPv6
  const parts = host.split('.');
  if (parts.length <= 2) return host;
  const last2 = parts.slice(-2).join('.');
  if (TWO_LEVEL_TLDS.has(last2)) return parts.slice(-3).join('.');
  return last2;
}

function hostOf(url) {
  try { return new URL(url).hostname; } catch (e) { return ''; }
}

function fileNameFromUrl(url) {
  try {
    const u = new URL(url);
    const seg = u.pathname.substring(u.pathname.lastIndexOf('/') + 1);
    const name = decodeURIComponent(seg || '');
    return name || '';
  } catch (e) {
    return '';
  }
}

/** 从 Content-Disposition 里取文件名（支持 filename*=UTF-8'' 与 filename="..."） */
function fileNameFromDisposition(disp) {
  if (!disp) return '';
  let m = /filename\*\s*=\s*([^;]+)/i.exec(disp);
  if (m) {
    let v = m[1].trim().replace(/^["']|["']$/g, '');
    const idx = v.indexOf("''");
    if (idx >= 0) {
      try { return decodeURIComponent(v.substring(idx + 2)); } catch (e) { /* ignore */ }
    }
    return v;
  }
  m = /filename\s*=\s*"([^"]*)"/i.exec(disp) || /filename\s*=\s*([^;]+)/i.exec(disp);
  return m ? m[1].trim() : '';
}

/** 去掉路径分隔符与非法字符，只保留文件名 */
function safeFileName(name) {
  if (!name) return '';
  return String(name).replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^\.+/, '').slice(0, 180);
}

// ---------------------------------------------------------------- 设置

async function getSettings() {
  try {
    const o = await chrome.storage.local.get('settings');
    return Object.assign({}, DEFAULT_SETTINGS, o.settings || {});
  } catch (e) {
    return Object.assign({}, DEFAULT_SETTINGS);
  }
}

async function setSettings(patch) {
  const cur = await getSettings();
  const next = Object.assign({}, cur, patch);
  await chrome.storage.local.set({ settings: next });
  return next;
}

// ---------------------------------------------------------------- 冷却（归还后 5 分钟内不再接管该根域名）

function cooldownStore() {
  return chrome.storage.session || chrome.storage.local;
}

/**
 * 冷却锚定的根域名：优先「发起下载的页面域名」，取不到再退回「下载资源域名」。
 *
 * 必须与「查询/清除冷却」用同一套口径，否则对不上：视频等资源常来自第三方 CDN
 * （如 bilivideo.com），若按资源域名冷却，用户在页面域名（bilibili.com）下打开插件
 * 就查不到冷却，点「立即重新开始」也解不掉真正挡住接管的那条冷却。
 */
function cooldownRoot(pageUrl, downloadUrl) {
  return rootDomainOf(hostOf(pageUrl)) || rootDomainOf(hostOf(downloadUrl));
}

async function isCoolingDown(root) {
  if (!root) return false;
  const key = 'cd:' + root;
  try {
    const o = await cooldownStore().get(key);
    const exp = o[key] || 0;
    if (exp > Date.now()) return true;
    if (exp) await cooldownStore().remove(key);
  } catch (e) { /* ignore */ }
  return false;
}

async function setCooldown(root, ms) {
  if (!root) return;
  const key = 'cd:' + root;
  try {
    await cooldownStore().set({ [key]: Date.now() + (ms || COOLDOWN_MS) });
    log('归还浏览器下载，冷却根域名', root, '至', new Date(Date.now() + (ms || COOLDOWN_MS)).toLocaleTimeString());
  } catch (e) { /* ignore */ }
}

/** 冷却剩余毫秒数；不在冷却期返回 0，并顺手清掉过期记录 */
async function cooldownRemaining(root) {
  if (!root) return 0;
  const key = 'cd:' + root;
  try {
    const o = await cooldownStore().get(key);
    const exp = o[key] || 0;
    const remain = exp - Date.now();
    if (remain > 0) return remain;
    if (exp) await cooldownStore().remove(key);
  } catch (e) { /* ignore */ }
  return 0;
}

/** 手动解除冷却，立即恢复对该根域名的接管 */
async function clearCooldown(root) {
  if (!root) return;
  try {
    await cooldownStore().remove('cd:' + root);
    log('手动恢复捕获，解除根域名冷却', root);
  } catch (e) { /* ignore */ }
}

// ---------------------------------------------------------------- 与云析的本地桥

/** 扫描端口区间探测云析；命中后缓存 { port, token } */
async function ensureBridge(force) {
  if (bridge && !force) return bridge;
  bridge = null;
  for (let p = PORT_START; p <= PORT_END; p++) {
    try {
      const r = await fetch('http://127.0.0.1:' + p + '/ping', { method: 'GET', cache: 'no-store' });
      if (!r.ok) continue;
      const j = await r.json();
      if (j && j.app === 'yunx') {
        bridge = { port: p, token: j.token || '' };
        log('已连接云析本地桥：端口', p);
        return bridge;
      }
    } catch (e) {
      // 端口未监听，继续下一个
    }
  }
  return null;
}

function invalidateBridge() {
  bridge = null;
}

/** 用在线状态刷新徽标 */
function setBadge(text, color) {
  try {
    chrome.action.setBadgeText({ text: text || '' });
    if (text && color) chrome.action.setBadgeBackgroundColor({ color });
  } catch (e) { /* ignore */ }
}

/** 组装 Cookie 头 */
async function cookieHeader(url) {
  try {
    const list = await chrome.cookies.getAll({ url });
    if (!list || !list.length) return '';
    return list.map((c) => c.name + '=' + c.value).join('; ');
  } catch (e) {
    return '';
  }
}

/** 组装发给云析的请求体 */
async function buildPayload(info) {
  const url = info.url;
  const host = hostOf(url);
  const sniffed = sniffByUrl.get(url) || null;
  let size = info.fileSize;
  if (size == null || size <= 0) size = sniffed && sniffed.size > 0 ? sniffed.size : -1;
  const fileName = safeFileName(info.fileName || (sniffed && sniffed.fileName) || fileNameFromUrl(url));
  return {
    url: url,
    fileName: fileName,
    fileSize: size,
    pageUrl: info.pageUrl || '',
    referer: info.referer || info.pageUrl || '',
    userAgent: (self.navigator && self.navigator.userAgent) || '',
    cookie: await cookieHeader(url),
    mime: info.mime || (sniffed && sniffed.mime) || '',
    rootDomain: rootDomainOf(host),
  };
}

/**
 * POST /hook：把请求交给云析弹窗，挂起等待用户决定（云析侧最长等 120 秒，超时按「归还」处理）。
 * @returns {Promise<{action:'accept'|'cancel'|'return', saveDir?:string, threads?:number}>}
 */
async function postHook(payload) {
  const b = await ensureBridge(false);
  if (!b) return { action: 'return' };
  const keep = keepAlive();
  try {
    const r = await fetch('http://127.0.0.1:' + b.port + '/hook', {
      method: 'POST',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-YunX-Token': b.token },
      body: JSON.stringify(payload),
    });
    if (r.status === 401) { invalidateBridge(); return { action: 'return' }; }
    if (!r.ok) return { action: 'return' };
    const d = await r.json();
    if (d && (d.action === 'accept' || d.action === 'cancel' || d.action === 'return')) return d;
    return { action: 'return' };
  } catch (e) {
    log('hook 失败：', e && e.message);
    invalidateBridge();
    return { action: 'return' };
  } finally {
    keep.stop();
  }
}

/**
 * POST 静默提交：把视频下载请求交给云析直接加入下载队列。
 *
 * 与 /hook 不同，这里**不挂起等用户决定**：清晰度/下载方式都已在插件页定好（或交给云析按
 * 平台最高档解析），云析收到即返回，解析与入队都在它后台完成，不弹窗、不打断浏览。
 * @param {string} path 桥接端点：`/bili`（BVID + 清晰度 + 方式）或 `/video`（页面链接）
 * @returns {Promise<{ok:boolean, error?:string}>}
 */
async function postSilent(path, payload) {
  const b = await ensureBridge(false);
  if (!b) return { ok: false, error: '未检测到云析，请先启动云析' };
  try {
    const r = await fetch('http://127.0.0.1:' + b.port + path, {
      method: 'POST',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-YunX-Token': b.token },
      body: JSON.stringify(payload),
    });
    if (r.status === 401) { invalidateBridge(); return { ok: false, error: '云析连接已失效，请重试' }; }
    if (!r.ok) return { ok: false, error: '云析拒绝请求（' + r.status + '）' };
    const d = await r.json().catch(() => null);
    if (d && d.ok) return { ok: true };
    return { ok: false, error: (d && d.error) || '云析处理失败' };
  } catch (e) {
    log(path + ' 提交失败：', e && e.message);
    invalidateBridge();
    return { ok: false, error: '提交失败：请确认云析正在运行' };
  }
}

/** 归还浏览器下载：重新发起下载（带 Referer，失败则退回不带请求头） */
async function redownload(payload) {
  const base = { url: payload.url, conflictAction: 'uniquify' };
  if (payload.fileName) base.filename = 'YunX/' + payload.fileName;
  const headers = [];
  if (payload.referer) headers.push({ name: 'Referer', value: payload.referer });
  try {
    if (headers.length) await chrome.downloads.download(Object.assign({ headers }, base));
    else await chrome.downloads.download(base);
  } catch (e) {
    try { await chrome.downloads.download(base); } catch (e2) { log('归还下载失败：', e2 && e2.message); }
  }
}

// ---------------------------------------------------------------- 通用下载流程

/**
 * 走一遍「交给云析 → 按决定分流」的流程。
 * @param info {url, fileName?, fileSize?, pageUrl?, referer?, mime?}
 * @returns 决定结果
 */
async function handOverToYunx(info) {
  const payload = await buildPayload(info);
  const decision = await postHook(payload);
  if (decision.action === 'accept') {
    setBadge('✓', '#2e86ff');
    setTimeout(() => setBadge(''), 3000);
    log('已交给云析下载', payload.fileName, decision.threads ? '(' + decision.threads + ' 线程)' : '');
  } else if (decision.action === 'return') {
    // 冷却按「发起下载的页面域名」锚定（取不到才退回资源域名），与后续接管检查、
    // popup 查询/清除保持同一口径，避免只冷却到 CDN 域名导致对不上。
    await setCooldown(cooldownRoot(payload.pageUrl, payload.url));
    await redownload(payload);
  }
  // cancel：用户主动放弃，不重新下载
  return decision;
}

// ---------------------------------------------------------------- 嗅探（响应头观察）

chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const url = details.url;
    if (!/^https?:/i.test(url)) return;

    let mime = '';
    let size = -1;
    let disp = '';
    for (const h of details.responseHeaders || []) {
      const n = h.name.toLowerCase();
      if (n === 'content-type') mime = String(h.value || '').split(';')[0].trim();
      else if (n === 'content-length') { const v = parseInt(h.value, 10); if (!isNaN(v)) size = v; }
      else if (n === 'content-disposition') disp = String(h.value || '');
    }

    const clean = url.split('?')[0].split('#')[0];
    const looksDownloadable =
      DOWNLOAD_EXT_RE.test(clean) || DOWNLOAD_MIME_RE.test(mime) || /attachment/i.test(disp);
    if (!looksDownloadable) return;

    const entry = {
      url: url,
      fileName: fileNameFromDisposition(disp) || fileNameFromUrl(url),
      mime: mime,
      size: size,
      category: categorize(mime, url),
      source: 'net',
      time: Date.now(),
    };
    rememberSniff(details.tabId, entry);
  },
  { urls: ['http://*/*', 'https://*/*'] },
  ['responseHeaders']
);

function rememberSniff(tabId, entry) {
  sniffByUrl.set(entry.url, entry);
  if (sniffByUrl.size > 2000) {
    const oldest = sniffByUrl.keys().next().value;
    if (oldest) sniffByUrl.delete(oldest);
  }
  let arr = tabResources.get(tabId);
  if (!arr) { arr = []; tabResources.set(tabId, arr); }
  if (!arr.some((e) => e.url === entry.url)) {
    arr.unshift(entry);
    while (arr.length > 300) arr.pop();
  }
}

// ---------------------------------------------------------------- 接管浏览器下载

chrome.downloads.onCreated.addListener((item) => {
  void onDownloadCreated(item);
});

async function onDownloadCreated(item) {
  try {
    // 自己用 downloads.download 重发的（归还 / 手动下载）放行，避免死循环
    if (item.byExtensionId && item.byExtensionId === chrome.runtime.id) return;
    const url = item.finalUrl || item.url || '';
    if (!/^https?:/i.test(url)) return;

    const st = await getSettings();
    if (!st.enabled || !st.hijack) return;

    const root = cooldownRoot(item.referrer || '', url);
    if (await isCoolingDown(root)) return; // 刚归还过，5 分钟内不再接管该站点

    // 探测云析；没运行就完全不干预浏览器下载
    const b = await ensureBridge(false);
    if (!b) return;

    // 等待期间可能已经下完了（小文件），已下完的不抢
    const found = await chrome.downloads.search({ id: item.id });
    const cur = found && found[0];
    if (!cur || cur.state !== 'in_progress') return;

    // 接管：取消浏览器下载并抹掉记录
    chrome.downloads.cancel(item.id, () => {
      void chrome.runtime.lastError;
      chrome.downloads.erase({ id: item.id }, () => void chrome.runtime.lastError);
    });

    const sniffed = sniffByUrl.get(url) || null;
    await handOverToYunx({
      url: url,
      fileName: sniffed && sniffed.fileName ? sniffed.fileName : fileNameFromUrl(url),
      fileSize: item.fileSize > 0 ? item.fileSize : (sniffed ? sniffed.size : -1),
      pageUrl: item.referrer || '',
      referer: item.referrer || '',
      mime: item.mime || (sniffed ? sniffed.mime : ''),
    });
  } catch (e) {
    log('接管异常：', e && e.message);
  }
}

// ---------------------------------------------------------------- 右键菜单

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    void chrome.runtime.lastError;
    chrome.contextMenus.create({ id: 'yunx-link', title: '用云析下载此链接', contexts: ['link'] });
    chrome.contextMenus.create({ id: 'yunx-media', title: '用云析下载此资源', contexts: ['video', 'audio'] });
    chrome.contextMenus.create({ id: 'yunx-image', title: '用云析下载此图片', contexts: ['image'] });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.linkUrl || info.srcUrl || '';
  if (!/^https?:/i.test(url)) return;
  const st = await getSettings();
  if (!st.enabled) return;
  await handOverToYunx({
    url: url,
    pageUrl: (tab && tab.url) || info.pageUrl || '',
    referer: (tab && tab.url) || info.pageUrl || '',
  });
});

// ---------------------------------------------------------------- 标签页清理

chrome.tabs.onRemoved.addListener((tabId) => tabResources.delete(tabId));

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo && changeInfo.status === 'loading' && changeInfo.url) tabResources.delete(tabId);
});

// ---------------------------------------------------------------- 消息（popup / content script）

/** 合并网络嗅探 + 页面 DOM 收集到的资源 */
async function collectResources(tabId) {
  const net = (tabResources.get(tabId) || []).slice();
  let dom = [];
  if (tabId >= 0) {
    dom = await new Promise((resolve) => {
      try {
        chrome.tabs.sendMessage(tabId, { type: 'yunx-dom-resources' }, (resp) => {
          if (chrome.runtime.lastError) { resolve([]); return; }
          resolve((resp && resp.resources) || []);
        });
      } catch (e) {
        resolve([]);
      }
    });
  }
  const seen = new Set();
  const out = [];
  for (const e of net.concat(dom)) {
    if (!e || !e.url || seen.has(e.url)) continue;
    seen.add(e.url);
    // DOM 缺大小 → 用网络嗅探补齐
    if ((e.size == null || e.size <= 0) && sniffByUrl.has(e.url)) e.size = sniffByUrl.get(e.url).size;
    // DOM 缺 MIME → 用网络嗅探补齐
    if (!e.mime && sniffByUrl.has(e.url)) e.mime = sniffByUrl.get(e.url).mime || '';
    // 两端都统一按 MIME 优先重算分类，保证一致
    e.category = categorize(e.mime, e.url);
    out.push(e);
  }
  return out;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || !msg.type) return;

  if (msg.type === 'yunx-get-settings') {
    getSettings().then((s) => sendResponse({ ok: true, settings: s }));
    return true;
  }

  if (msg.type === 'yunx-set-settings') {
    setSettings(msg.settings || {}).then((s) => sendResponse({ ok: true, settings: s }));
    return true;
  }

  if (msg.type === 'yunx-status') {
    ensureBridge(true).then((b) => {
      setBadge(b ? '' : '');
      sendResponse({ ok: true, connected: !!b, port: b ? b.port : 0 });
    });
    return true;
  }

  if (msg.type === 'yunx-get-resources') {
    const tabId = typeof msg.tabId === 'number' ? msg.tabId : -1;
    collectResources(tabId).then((list) => sendResponse({ ok: true, resources: list }));
    return true;
  }

  // 当前页面所在的根域名是否处于「归还后」的冷却期，以及还剩多久
  if (msg.type === 'yunx-cooldown-status') {
    (async () => {
      const root = rootDomainOf(hostOf(msg.url || msg.pageUrl || ''));
      const remainMs = await cooldownRemaining(root);
      sendResponse({ ok: true, rootDomain: root, cooling: remainMs > 0, remainMs: remainMs });
    })();
    return true;
  }

  // 立即恢复捕获：只解除当前页面根域名的冷却
  if (msg.type === 'yunx-cooldown-clear') {
    (async () => {
      const root = rootDomainOf(hostOf(msg.url || msg.pageUrl || ''));
      await clearCooldown(root);
      sendResponse({ ok: true, rootDomain: root });
    })();
    return true;
  }

  if (msg.type === 'yunx-download') {
    (async () => {
      const st = await getSettings();
      if (!st.enabled) { sendResponse({ ok: false, error: '插件未启用' }); return; }
      const d = await handOverToYunx({
        url: msg.url,
        fileName: msg.fileName || '',
        fileSize: typeof msg.size === 'number' ? msg.size : -1,
        pageUrl: msg.pageUrl || '',
        referer: msg.pageUrl || '',
        mime: msg.mime || '',
      });
      sendResponse({ ok: true, action: d.action });
    })();
    return true;
  }

  if (msg.type === 'yunx-bili-download') {
    (async () => {
      const st = await getSettings();
      if (!st.enabled) { sendResponse({ ok: false, error: '插件未启用' }); return; }
      const resp = await postSilent('/bili', {
        bvid: msg.bvid || '',
        pageUrl: msg.pageUrl || '',
        qn: typeof msg.qn === 'number' ? msg.qn : 0,
        mode: msg.mode || 'merge',
        title: msg.title || '',
      });
      sendResponse(resp);
    })();
    return true;
  }

  // 其他视频平台（抖音 / 微博 / AcFun …）：只把页面链接交给云析，由云析解析成可下载流
  if (msg.type === 'yunx-video-download') {
    (async () => {
      const st = await getSettings();
      if (!st.enabled) { sendResponse({ ok: false, error: '插件未启用' }); return; }
      const resp = await postSilent('/video', {
        platform: msg.platform || '',
        url: msg.url || msg.pageUrl || '',
        title: msg.title || '',
        pageUrl: msg.pageUrl || '',
      });
      sendResponse(resp);
    })();
    return true;
  }
});
