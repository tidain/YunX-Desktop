/**
 * 云析下载助手 —— 页面脚本
 *
 * 只做一件事：按需（popup 打开时）把页面里「看起来可以下载」的资源收集出来交给后台。
 * 不注入任何界面元素，不自动发起任何请求。
 */

(function () {
  'use strict';

  if (window.__yunxCollectorInstalled) return;
  window.__yunxCollectorInstalled = true;

  const EXT_RE = new RegExp(
    '\\.(mp4|m4v|mkv|webm|flv|f4v|mov|avi|ts|m3u8|mpd|mp3|m4a|aac|flac|wav|ogg|opus|' +
    'zip|rar|7z|tar|gz|bz2|xz|iso|exe|msi|apk|ipa|dmg|pkg|deb|rpm|' +
    'pdf|epub|mobi|azw3|doc|docx|xls|xlsx|ppt|pptx|torrent|img|bin|dat|jar|crx|' +
    'jpg|jpeg|png|gif|webp|bmp|svg|ico|avif|' +
    'ttf|otf|woff|woff2)(\\?|#|$)', 'i');

  function abs(u) {
    if (!u) return '';
    try { return new URL(u, location.href).href; } catch (e) { return ''; }
  }

  function nameFromUrl(u) {
    try {
      const seg = new URL(u).pathname;
      return decodeURIComponent(seg.substring(seg.lastIndexOf('/') + 1)) || '';
    } catch (e) {
      return '';
    }
  }

  function collect() {
    const out = [];
    const seen = new Set();

    const push = (raw, mime) => {
      const u = abs(raw);
      if (!u || !/^https?:/i.test(u)) return; // 跳过 blob: / data:
      if (seen.has(u)) return;
      seen.add(u);
      out.push({ url: u, fileName: nameFromUrl(u), mime: mime || '', size: -1, source: 'dom' });
    };

    document.querySelectorAll('video, audio').forEach((m) => push(m.currentSrc || m.src, ''));
    document.querySelectorAll('video > source, audio > source').forEach((s) => push(s.src, s.type || ''));
    document.querySelectorAll('a[href]').forEach((a) => {
      const u = abs(a.href);
      if (!u) return;
      if (a.hasAttribute('download') || EXT_RE.test(u)) push(u, a.type || '');
    });
    document.querySelectorAll('img[src]').forEach((img) => {
      const u = abs(img.currentSrc || img.src);
      // 页面图片不依赖扩展名（CDN 常见无后缀），统一按图片收集，供分类与预览
      if (u) push(u, 'image/*');
    });

    return out.slice(0, 200);
  }

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (!msg || msg.type !== 'yunx-dom-resources') return;
    try { sendResponse({ ok: true, resources: collect() }); }
    catch (e) { sendResponse({ ok: false, resources: [] }); }
  });
})();
