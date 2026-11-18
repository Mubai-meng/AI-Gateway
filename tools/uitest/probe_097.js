// 定点验证 0.9.7 的两处前端改动（不点任何写操作）：
//   1. 供应商页「添加自定义供应商」表单里有「图像模型」多行框，且提示文案分两种
//   2. 媒体实测页的出图能力提示按「自定义供应商是否填了图像模型」分情况
// 只读：打开弹窗后立即关闭，不保存、不删除。
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9346;
const BASE = process.argv[2] || 'http://127.0.0.1:8792/';
const OUT = process.argv[3] || 'probe097.json';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const profile = path.join(os.tmpdir(), 'probe097-' + Date.now());
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
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
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
  await sleep(3500);

  const result = {};

  // ---- 供应商页：打开添加表单，量「图像模型」字段
  await ev(`document.querySelector('.nav-item[data-page="providers"]').click()`);
  await sleep(1200);
  result.providerPage = await ev(`(() => {
    const b = document.getElementById('btnNewCustom');
    return { hasButton: !!b, customCount: (window.S && S.custom ? S.custom.length : -1) };
  })()`);

  await ev(`document.getElementById('btnNewCustom').click()`);
  await sleep(500);
  result.addForm = await ev(`(() => {
    const ta = document.getElementById('ccImageModels');
    const hint = document.getElementById('ccImageHint');
    const rows = ta ? ta.rows : 0;
    return {
      modalTitle: (document.getElementById('modalTitle') || {}).textContent,
      hasImageField: !!ta,
      rows,
      placeholder: ta ? ta.placeholder : '',
      hint: hint ? hint.textContent.trim() : '',
      // 字段顺序：图像模型应该在「模型名」之后
      order: Array.from(document.querySelectorAll('.custom-form textarea, .custom-form input')).map(n => n.id),
    };
  })()`);
  // 关掉弹窗（不保存）
  await ev(`document.getElementById('modalMask').hidden = true; document.getElementById('modalFoot').innerHTML=''; 1`);

  // ---- 媒体实测页：出图能力提示
  await ev(`document.querySelector('.nav-item[data-page="media"]').click()`);
  await sleep(1200);
  result.mediaHint = await ev(`(() => {
    const hints = Array.from(document.querySelectorAll('.hint-stack span')).map(s => s.textContent.trim());
    const provs = (window.S && S.state ? S.state.providers : []) || [];
    return {
      hints,
      imageProviders: provs.filter(p => p.image).map(p => p.id + ':' + p.accountCount),
      customNoImage: (window.S && S.custom ? S.custom.filter(c => !c.supportsImage).map(c => c.key) : []),
    };
  })()`);

  // ---- mini-btn wide：「查看」按钮宽度（路由组页脚注只在有 auto 组时出现）
  await ev(`document.querySelector('.nav-item[data-page="groups"]').click()`);
  await sleep(1400);
  result.footBtn = await ev(`(() => {
    const b = document.querySelector('.group-foot .mini-btn');
    if (!b) return { found: false };
    return { found: true, text: b.textContent.trim(), w: b.clientWidth, sw: b.scrollWidth,
             cls: b.className };
  })()`);

  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
  ws.close();
  try { p.kill(); } catch (_) { }
})();
