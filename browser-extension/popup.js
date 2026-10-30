/**
 * 云析下载助手 —— popup 脚本
 * 展示云析连接状态、开关设置、当前页面嗅探到的资源，并可一键交给云析下载。
 */

'use strict';

const $ = (id) => document.getElementById(id);

function send(msg) {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage(msg, (resp) => {
      if (chrome.runtime.lastError) resolve(null);
      else resolve(resp);
    });
  });
}

async function activeTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return (tabs && tabs[0]) || null;
}

function fmtSize(n) {
  if (!n || n <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v.toFixed(1)) + ' ' + units[i];
}

function hostOf(u) {
  try { return new URL(u).hostname; } catch (e) { return ''; }
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function setStatus(text, cls) {
  const el = $('status');
  el.textContent = text;
  el.className = 'status' + (cls ? ' ' + cls : '');
}

let currentTab = null;

// ---------------------------------------------------------------- 渲染

function renderResources(list) {
  const box = $('list');
  $('count').textContent = String(list.length);
  if (!list.length) {
    box.innerHTML = '<div class="empty">暂无嗅探到的资源<br />播放或点开页面上的资源后再试</div>';
    return;
  }

  const frag = document.createDocumentFragment();
  for (const r of list) {
    const row = document.createElement('div');
    row.className = 'item';

    const main = document.createElement('div');
    main.className = 'item-main';

    const name = document.createElement('div');
    name.className = 'item-name';
    name.title = r.url;
    name.textContent = r.fileName || r.url;

    const meta = document.createElement('div');
    meta.className = 'item-meta';
    const tag = r.source === 'dom' ? '页面' : '网络';
    const bits = [];
    if (r.size > 0) bits.push(fmtSize(r.size));
    if (r.mime) bits.push(r.mime);
    bits.push(hostOf(r.url));
    meta.innerHTML = '<span class="tag">' + tag + '</span>' + escapeHtml(bits.join(' · '));

    main.appendChild(name);
    main.appendChild(meta);

    const btn = document.createElement('button');
    btn.className = 'dl-btn';
    btn.textContent = '云析下载';
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      btn.textContent = '处理中';
      const resp = await send({
        type: 'yunx-download',
        url: r.url,
        fileName: r.fileName || '',
        size: r.size || -1,
        mime: r.mime || '',
        pageUrl: currentTab ? currentTab.url : '',
      });
      if (!resp || !resp.ok) {
        btn.disabled = false;
        btn.textContent = '云析下载';
        $('hint').textContent = (resp && resp.error) || '提交失败：请确认云析正在运行。';
        return;
      }
      if (resp.action === 'accept') {
        btn.textContent = '已交给云析';
        $('hint').textContent = '已交给云析下载（在云析弹窗里确认保存目录与线程数）。';
      } else if (resp.action === 'return') {
        btn.disabled = false;
        btn.textContent = '云析下载';
        $('hint').textContent = '云析未接管，已归还浏览器下载。';
      } else {
        btn.disabled = false;
        btn.textContent = '云析下载';
      }
    });

    row.appendChild(main);
    row.appendChild(btn);
    frag.appendChild(row);
  }
  box.innerHTML = '';
  box.appendChild(frag);
}

// ---------------------------------------------------------------- 数据加载

async function loadSettings() {
  const resp = await send({ type: 'yunx-get-settings' });
  const s = (resp && resp.settings) || { enabled: true, hijack: true, sniff: true };
  $('sw-enabled').checked = !!s.enabled;
  $('sw-hijack').checked = !!s.hijack;
  $('sw-sniff').checked = !!s.sniff;
}

async function loadStatus() {
  const resp = await send({ type: 'yunx-status' });
  if (resp && resp.connected) {
    setStatus('云析已连接（端口 ' + resp.port + '）', 'on');
  } else {
    setStatus('未检测到云析，请先启动云析', 'off');
  }
}

async function loadResources() {
  currentTab = await activeTab();
  const tabId = currentTab ? currentTab.id : -1;
  const resp = await send({ type: 'yunx-get-resources', tabId: tabId });
  renderResources((resp && resp.resources) || []);
}

// ---------------------------------------------------------------- 事件

function bindSwitches() {
  const bind = (id, key) => {
    $(id).addEventListener('change', async () => {
      const patch = {};
      patch[key] = $(id).checked;
      await send({ type: 'yunx-set-settings', settings: patch });
    });
  };
  bind('sw-enabled', 'enabled');
  bind('sw-hijack', 'hijack');
  bind('sw-sniff', 'sniff');
}

$('refresh').addEventListener('click', async () => {
  $('hint').textContent = '正在刷新…';
  await loadStatus();
  await loadResources();
  $('hint').textContent = '资源列表仅在本机与云析之间传递，不上传任何数据。';
});

(async function init() {
  bindSwitches();
  await loadSettings();
  await loadStatus();
  await loadResources();
})();
