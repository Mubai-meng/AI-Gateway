// 供应商页「自定义供应商」深度巡检。
//
// 通用 ui_walk.js 只会点页面上已存在的按钮；本区块的编辑表单是「点了添加才出现」的
// 模态窗，不点开就永远测不到保存/拉模型/删除。这与 ui_groups_walk.js 是同一类问题。
//
// 覆盖：空态 → 打开表单 → 名称自动派生短名 → 保存 → 列表出现（含真实端点）
//      → 编辑（改名，账号跟着搬）→ 拉模型（预期失败：假地址）→ 删除（确认框）
//      → 删除后列表回到空 → 横向溢出检查。
//
// 用法: node ui_custom_walk.js <baseUrl> <outFile>
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const BASE = process.argv[2] || 'http://127.0.0.1:18108';
const OUT = process.argv[3] || 'custom-walk.json';
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9335;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.events = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }
  send(method, params) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params: params || {} }));
    });
  }
  async eval(expr, awaitPromise) {
    const r = await this.send('Runtime.evaluate', {
      expression: expr, awaitPromise: !!awaitPromise, returnByValue: true, userGesture: true,
    });
    if (r.exceptionDetails) {
      return { __err: r.exceptionDetails.exception?.description || r.exceptionDetails.text };
    }
    return r.result?.value;
  }
}

const WALK = `
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const steps = [];
  const errors = [];
  const modalOpen = () => {
    const m = document.getElementById('modalMask');
    return m && !m.hidden;
  };
  const modalTitle = () => (document.getElementById('modalTitle') || {}).textContent || '';
  const toasts = () => Array.from(document.getElementById('toasts').children)
    .map((t) => t.textContent);
  const closeModal = () => { document.getElementById('modalMask').hidden = true; };
  const setVal = (id, v) => {
    const n = document.getElementById(id);
    if (!n) return false;
    n.value = v;
    n.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  };
  const text = (id) => (document.getElementById(id) || {}).textContent || '';
  // 本页是异步渲染（首帧只画「正在读取」，数据到了才重建），固定 sleep 不可靠：
  // 必须轮询到条件成立，否则会在按钮还没生成时点空。
  async function waitFor(fn, ms = 6000) {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const v = fn();
      if (v) return v;
      await sleep(150);
    }
    return null;
  }
  const btnByText = (sel, re) => Array.from(document.querySelectorAll(sel))
    .find((b) => re.test((b.textContent || '').trim()));

  // ---- 进供应商页
  const nav = document.querySelector('.nav-item[data-page="providers"]');
  if (nav) nav.click(); else go('providers');
  await sleep(600);
  const gotBtn = await waitFor(() => document.getElementById('btnNewCustom'));
  steps.push('切到供应商页：' + (document.getElementById('pageTitle') || {}).textContent
    + ' / 新区块就绪：' + (!!gotBtn));

  const need = (cond, msg) => { if (!cond) errors.push('STEP-FAIL ' + msg); };

  // ---- 0 列表形态（可能是空态，也可能已有条目：这一页不能假设空目录）
  const rowsBefore = document.querySelectorAll('#customList .custom-item').length;
  const emptyBefore = document.querySelector('#customList .empty');
  steps.push('列表形态：已有 ' + rowsBefore + ' 条 / 空态=' + (!!emptyBefore));
  if (rowsBefore === 0) need(!!emptyBefore, '没有条目时应该显示空态');

  // ---- 1 打开添加表单，验证短名自动派生
  const bNew = document.getElementById('btnNewCustom');
  need(!!bNew, '找不到「添加自定义供应商」按钮');
  if (!bNew) return { steps, errors: errors.concat(['ABORT 按钮未出现']), overflow: [], bodyW: 0, bodyScrollW: 0 };
  bNew.click();
  await sleep(400);
  need(modalOpen(), '点了添加但没弹窗');
  need(/自定义供应商/.test(modalTitle()), '弹窗标题不对：' + modalTitle());
  steps.push('打开表单：' + modalTitle());

  setVal('ccName', '探针 中转');
  await sleep(200);
  const derived = (document.getElementById('ccKey') || {}).value || '';
  steps.push('名称「探针 中转」→ 短名自动派生：' + derived);
  need(/^[a-z0-9_-]+$/.test(derived), '派生短名非法：' + derived);

  // ---- 2 只填地址，先不填 Key：应当保存成功（Models 为空也可）
  setVal('ccUrl', 'https://api.probe.test');
  setVal('ccModels', 'probe-model-a\\nprobe-model-b');
  const saveBtn = btnByText('#modalFoot button', /^保存$/);
  need(!!saveBtn, '找不到「保存」按钮');
  saveBtn.click();
  await sleep(1800);

  // 保存成功会关窗 + 刷新列表（列表重建是异步的，轮询等它）
  await waitFor(() => document.querySelectorAll('#customList .custom-item').length > 0);
  const itemAfter = document.querySelectorAll('#customList .custom-item').length;
  steps.push('保存后列表条目数：' + itemAfter + '（前 ' + rowsBefore + '）');
  need(itemAfter === rowsBefore + 1, '保存后条目数没有 +1（前 ' + rowsBefore + ' 后 ' + itemAfter + '）');
  need(itemAfter >= 1, '保存后列表里没有条目（保存可能失败）');
  need(!modalOpen(), '保存成功后弹窗没有关闭');

  // 端点要显示规范化后的真实地址
  // 找刚建的那一条（列表里可能本来就有别的条目）
  const newRow = Array.from(document.querySelectorAll('#customList .custom-item'))
    .find((n) => (n.querySelector('.custom-pid') || {}).textContent === 'custom:' + derived);
  const urlNode = newRow ? newRow.querySelector('.custom-url') : null;
  const shownUrl = urlNode ? urlNode.textContent : '';
  need(!!newRow, '列表里找不到刚建的 custom:' + derived);
  steps.push('列表显示的 provider id：'
    + (newRow ? newRow.querySelector('.custom-pid').textContent : '—')
    + ' / 实际端点：' + shownUrl);
  steps.push('列表显示的实际端点：' + shownUrl);
  need(/\\/v1\\/chat\\/completions$/.test(shownUrl), '端点没有被补 /v1：' + shownUrl);

  // ---- 3 后端口径核对：供应商表要出现 custom:xxx、账号数为 0
  const st = await (await fetch('/ui/custom')).json();
  const probe = (st.items || []).find((x) => x.key === derived);
  steps.push('服务端读回：' + (probe ? JSON.stringify({
    key: probe.key, registered: probe.registered, baseUrl: probe.baseUrl,
    models: probe.models, accounts: probe.accountCount,
  }) : 'NOT FOUND'));
  need(probe && probe.baseUrl === 'https://api.probe.test/v1', '后端没保存规范化地址');
  need(probe && probe.registered === true, '后端没登记进 registry');
  need(probe && probe.modelCount === 2, '模型数不是 2');

  // ---- 4 编辑：改名（短名也改），保存后旧条目必须消失
  const editBtn = newRow ? newRow.querySelector('.mini-btn') : null;
  need(!!editBtn, '找不到编辑按钮');
  editBtn.click();
  await sleep(400);
  need(modalOpen(), '点编辑没弹窗');
  steps.push('打开编辑：' + modalTitle());
  setVal('ccName', '探针改名');
  setVal('ccKey', 'probe-renamed');
  setVal('ccUrl', 'https://api.probe.test/v1/chat/completions');
  const save2 = btnByText('#modalFoot button', /^保存$/);
  save2.click();
  await waitFor(() => !document.getElementById('modalMask') || document.getElementById('modalMask').hidden, 8000);
  await sleep(400);
  const st2 = await (await fetch('/ui/custom')).json();
  const keys2 = (st2.items || []).map((x) => x.key);
  steps.push('改名后 keys：' + JSON.stringify(keys2));
  need(keys2.indexOf('probe-renamed') >= 0, '改名后的新 key 没出现：' + JSON.stringify(keys2));
  need(keys2.indexOf(derived) < 0, '改名后旧 key 还在：' + JSON.stringify(keys2));
  const hit2 = (st2.items || []).filter((x) => x.key === 'probe-renamed')[0] || {};
  const base2 = hit2.baseUrl || '';
  need(base2 === 'https://api.probe.test/v1', '贴了 /chat/completions 的地址没被剥干净：' + base2);

  // ---- 5 拉模型：假地址必须给出可读原因（不能是「拉取失败」四个字）
  const renRow = Array.from(document.querySelectorAll('#customList .custom-item'))
    .find((n) => (n.querySelector('.custom-pid') || {}).textContent === 'custom:probe-renamed');
  const edit2 = renRow ? renRow.querySelector('.mini-btn') : null;
  if (edit2) { edit2.click(); await sleep(400); }
  const pull = document.getElementById('btnPullModels');
  need(!!pull, '表单里没有「拉取模型列表」按钮');
  if (pull) {
    pull.click();
    await sleep(6000);
    const stText = text('ccStatus');
    steps.push('拉模型结果：' + stText);
    need(stText.length > 0, '拉模型没有给出任何反馈');
    need(!/^拉取失败$/.test(stText.trim()), '拉模型失败原因被压成一句话：' + stText);
    // 关掉弹窗（keepOpen 的保存按钮不该被这里点到）
    closeModal();
  }

  // ---- 6 删除：确认框 → 确认 → 列表回空
  const delRow = renRow || document.querySelector('#customList .custom-item');
  const delBtn = delRow ? delRow.querySelector('.mini-btn.danger') : null;
  need(!!delBtn, '找不到删除按钮');
  delBtn.click();
  await sleep(400);
  need(modalOpen(), '删除没有二次确认');
  steps.push('删除确认框：' + modalTitle());
  const okBtn = btnByText('#modalFoot button', /^确定$/);
  if (okBtn) okBtn.click();
  await waitFor(() => document.querySelectorAll('#customList .custom-item').length <= rowsBefore, 8000);
  closeModal();
  await sleep(300);
  const st3 = await (await fetch('/ui/custom')).json();
  const keys3 = (st3.items || []).map((x) => x.key);
  steps.push('删除后服务端 items：' + (st3.items || []).length + ' ' + JSON.stringify(keys3));
  need(keys3.indexOf('probe-renamed') < 0, '删除后 probe-renamed 还在：' + JSON.stringify(keys3));
  need((st3.items || []).length === rowsBefore, '删除后条目数不是「前 ' + rowsBefore + '」：' + JSON.stringify(keys3));

  // ---- 7 溢出检查（本页表格 + 新区块）
  const over = [];
  document.querySelectorAll('#pageBody *').forEach((n) => {
    if (n.scrollWidth > n.clientWidth + 2 && n.clientWidth > 0) {
      over.push((n.className || n.tagName) + ' ' + n.scrollWidth + '>' + n.clientWidth);
    }
  });
  const bodyEl = document.getElementById('pageBody');
  return {
    steps,
    errors,
    overflow: over.slice(0, 10),
    bodyW: bodyEl.clientWidth,
    bodyScrollW: bodyEl.scrollWidth,
  };
})()
`;

async function main() {
  const profile = path.join(os.tmpdir(), 'aigw-cdp-profile-custom');
  const chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--window-size=1240,820', 'about:blank',
  ], { stdio: 'ignore' });

  let result = { steps: [], errors: [], fatal: null };
  try {
    let wsUrl = null;
    for (let i = 0; i < 100 && !wsUrl; i++) {
      try {
        const r = await fetch('http://127.0.0.1:' + PORT + '/json/list');
        const list = await r.json();
        const page = list.find((x) => x.type === 'page');
        if (page) wsUrl = page.webSocketDebuggerUrl;
      } catch (_) { /* 还没起来 */ }
      if (!wsUrl) await sleep(200);
    }
    if (!wsUrl) throw new Error('chrome devtools endpoint not ready');

    const ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => {
      ws.addEventListener('open', res);
      ws.addEventListener('error', rej);
    });
    const cdp = new Cdp(ws);
    await cdp.send('Runtime.enable');
    await cdp.send('Log.enable');
    await cdp.send('Page.enable');
    await cdp.send('Page.navigate', { url: BASE + '/' });
    await sleep(3800);
    await cdp.eval('document.getElementById("modalMask").hidden = true; 1');

    const res = await cdp.eval(WALK, true);
    if (res && res.__err) result.fatal = res.__err;
    else Object.assign(result, res || {});

    const exceptions = [];
    const consoleErrors = [];
    for (const ev of cdp.events) {
      if (ev.method === 'Runtime.exceptionThrown') {
        const d = ev.params.exceptionDetails;
        exceptions.push((d.exception && d.exception.description) || d.text);
      }
      if (ev.method === 'Log.entryAdded' && ev.params.entry.level === 'error') {
        consoleErrors.push(ev.params.entry.text + ' @ ' + (ev.params.entry.url || ''));
      }
    }
    result.exceptions = exceptions;
    result.consoleErrors = consoleErrors;
    ws.close();
  } finally {
    try { chrome.kill(); } catch (_) { }
  }

  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({
    steps: (result.steps || []).length,
    errors: result.errors || [],
    exceptions: (result.exceptions || []).length,
    consoleErrors: (result.consoleErrors || []).length,
    overflow: (result.overflow || []).length,
    fatal: result.fatal,
  }, null, 2));
  (result.steps || []).forEach((s) => console.log('  ' + s));
  if ((result.exceptions || []).length) {
    console.log('EXCEPTIONS:');
    result.exceptions.forEach((e) => console.log('  ' + e.slice(0, 300)));
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
