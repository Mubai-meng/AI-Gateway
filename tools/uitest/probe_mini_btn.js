// 量 mini-btn 的实际宽度：20px 固定宽 + 两字标签（「查看」）会溢 3px。
// 只读测量，不改任何数据。
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9344;
const BASE = 'http://127.0.0.1:18134/';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const profile = path.join(os.tmpdir(), 'probe-' + Date.now());
  const p = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--window-size=1240,820', 'about:blank',
  ], { stdio: 'ignore' });

  let ws;
  for (let i = 0; i < 80; i++) {
    try {
      const l = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const pg = l.find((x) => x.type === 'page');
      if (pg) { ws = new WebSocket(pg.webSocketDebuggerUrl); break; }
    } catch (_) { /* 还没起来 */ }
    await sleep(200);
  }
  await new Promise((r) => ws.addEventListener('open', r));

  let id = 0;
  const pend = new Map();
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
  });
  const send = (method, params) => new Promise((r) => {
    const i = ++id; pend.set(i, r);
    ws.send(JSON.stringify({ id: i, method, params: params || {} }));
  });

  await send('Runtime.enable');
  await send('Page.navigate', { url: BASE });
  await sleep(3200);

  const SCRIPT = fs.readFileSync(path.join(__dirname, '..', '..', '.tmp', 'v09', 'probe-over.js'), 'utf8');
  for (const page of ['groups', 'providers', 'models', 'calls']) {
    await send('Runtime.evaluate', {
      expression: `document.querySelector('.nav-item[data-page="${page}"]').click()`,
      userGesture: true,
    });
    await sleep(1600);
    const r = await send('Runtime.evaluate', { expression: SCRIPT, returnByValue: true });
    const rows = r.result.value || [];
    const bad = rows.filter((x) => x.sw > x.w + 2);
    console.log(`== ${page}: ${rows.length} 个 mini-btn，溢出 ${bad.length} 个`);
    bad.forEach((x) => console.log(`   "${x.text}" w=${x.w} sw=${x.sw}`));
  }
  ws.close();
  try { p.kill(); } catch (_) { }
})();
