// 验证「首次直接进媒体实测页」时，出图空态归因能否正确引用自定义供应商。
// 关键：不经过供应商页（S.custom 未加载的状态），复现用户第一次打开就点媒体页的真实路径。
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9347;
const BASE = process.argv[2] || 'http://127.0.0.1:8792/';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const profile = path.join(os.tmpdir(), 'mediahint-' + Date.now());
  const p = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--window-size=1240,820', 'about:blank',
  ], { stdio: 'ignore' });

  let ws;
  for (let i = 0; i < 100; i++) {
    try {
      const l = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const pg = l.find((x) => x.type === 'page');
      if (pg) { ws = new WebSocket(pg.webSocketDebuggerUrl); break; }
    } catch (_) { /* 未就绪 */ }
    await sleep(200);
  }
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0;
  const pend = new Map();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id); }
  });
  const send = (method, params) => new Promise((r) => {
    const i = ++id; pend.set(i, r);
    ws.send(JSON.stringify({ id: i, method, params: params || {} }));
  });
  const ev = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, userGesture: true });
    if (r.exceptionDetails) return { __err: r.exceptionDetails.exception?.description };
    return r.result?.value;
  };

  await send('Runtime.enable');
  await send('Page.navigate', { url: BASE });
  await sleep(3200);

  // 关键：**从概览页直接跳到媒体页**，不先经过供应商页
  const step1 = await ev(`(() => {
    const before = window.S ? S.customLoaded : null;
    return { customLoadedBefore: before, page: (window.S ? S.page : null) };
  })()`);

  await ev(`document.querySelector('.nav-item[data-page="media"]').click()`);
  await sleep(2000);   // 等 loadCustomProviders 回来再重绘

  const after = await ev(`(() => {
    const hints = Array.from(document.querySelectorAll('.hint-stack span')).map(s => s.textContent.trim());
    return {
      customLoaded: window.S ? S.customLoaded : null,
      customCount: (window.S && S.custom ? S.custom.length : -1),
      customItems: (window.S && S.custom ? S.custom.map(c => c.key + ':' + (c.supportsImage ? 'img' : 'no-img')) : []),
      hints,
      imageSuppliers: ((window.S && S.state ? S.state.providers : []) || [])
        .filter(p => p.image && p.accountCount > 0).map(p => p.id),
    };
  })()`);

  console.log(JSON.stringify({ beforeEnteringMedia: step1, afterEnteringMedia: after }, null, 2));
  ws.close();
  try { p.kill(); } catch (_) { }
})();
