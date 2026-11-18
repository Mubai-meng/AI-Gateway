// 路由组页的深度巡检：通用 ui_walk.js 只会点「页面上已经存在的按钮」，
// 而本页的编辑区是「点了组之后才出现」的——不点开就永远测不到成员/规则/保存。
//
// 覆盖：新建组 → 编辑组名 → 保存 → 加成员（真实模型目录）→ 加规则（弹窗全字段）
//      → 保存 → 删除 → auto 组只读态 → 溢出检查。
//
// 用法: node ui_groups_walk.js <baseUrl> <outFile>
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const BASE = process.argv[2] || 'http://127.0.0.1:18216';
const OUT = process.argv[3] || 'groups-walk.json';
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9334;

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
  const out = { steps: [], errors: [] };
  const step = (name, detail) => out.steps.push(name + (detail ? ' -> ' + detail : ''));
  const q = (s) => document.querySelector(s);
  const modalOpen = () => { const m = q('#modalMask'); return m && !m.hidden; };
  const toastText = () => {
    const box = q('#toasts');
    return box && box.children.length ? box.children[box.children.length - 1].textContent : '';
  };
  const closeModal = () => { q('#modalMask').hidden = true; };
  // 「从模型目录添加」在目录未加载时会先拉一次上游（实测 3–8 秒），
  // 固定 sleep 必然偶发失败。等条件而不是等时间。
  const waitFor = async (fn, ms = 20000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      if (fn()) return true;
      await sleep(200);
    }
    return false;
  };

  // 进页
  q('.nav-item[data-page="groups"]').click();
  await sleep(1200);
  step('进入路由组页', '行数 ' + q('#tblGroups tbody').children.length);

  // ---- 1. 新建组
  q('#btnNewGroup').click();
  await sleep(600);
  if (!q('#grId')) throw new Error('新建后没有出现编辑区（#grId 缺失）');
  step('新建组', '编辑区已出现');
  const idIn = q('#grId');
  idIn.value = 'walk-grp';
  idIn.dispatchEvent(new Event('input', { bubbles: true }));
  const nameIn = q('#grName');
  nameIn.value = '巡检组';
  nameIn.dispatchEvent(new Event('input', { bubbles: true }));
  step('填组名', idIn.value + ' / ' + nameIn.value);

  // ---- 2. 保存（此时无成员，应被前端拦住）
  q('#btnSaveGroup').click();
  await sleep(700);
  step('空成员保存', 'toast=' + toastText());

  // ---- 3. 加成员：从模型目录添加（真拉一次目录）
  q('#pageBody button.btn-pad').click();
  if (!await waitFor(modalOpen)) throw new Error('添加成员弹窗没出现');
  await waitFor(() => q('#pickList') && q('#pickList').querySelectorAll('button').length > 0);
  step('打开成员选择弹窗', '标题=' + (q('#modalTitle') || {}).textContent);
  const addBtns = Array.from(q('#pickList').querySelectorAll('button'));
  step('可选模型数', addBtns.length);
  if (addBtns.length === 0) throw new Error('模型池是空的，无法加成员');
  addBtns[0].click();
  await sleep(500);
  step('加入第一个模型', '剩 ' + toastText());
  if (modalOpen()) closeModal();
  await sleep(300);
  // 再加一个，便于后面测规则指向第二个成员
  q('#pageBody button.btn-pad').click();
  if (await waitFor(modalOpen, 10000)) {
    await waitFor(() => q('#pickList') && q('#pickList').querySelectorAll('button').length > 0);
    const b2 = Array.from(q('#pickList').querySelectorAll('button')).filter((b) => !b.disabled);
    step('剩余可加模型', b2.length);
    if (b2.length) { b2[0].click(); await sleep(500); }
    if (modalOpen()) closeModal();
  }
  await sleep(400);
  step('成员数', String((S.groupDraft || {}).members.length));

  // ---- 4. 调整权重 / 优先级（改完必须标脏）
  const prIn = q('#pageBody .weight-in');
  if (prIn) {
    prIn.value = '250';
    prIn.dispatchEvent(new Event('change', { bubbles: true }));
    const wt = q('#pageBody .member-line2 input.weight-in:last-of-type');
    step('改优先级', '250 · 脏标记=' + (S.groupDirty ? '是' : '否'));
  }

  // ---- 5. 加规则（弹窗全字段：token / 时段 / 目录 / 星期 / 图片）
  const addRule = Array.from(q('#pageBody').querySelectorAll('button.btn-pad')).find((b) => /加规则/.test(b.textContent));
  if (!addRule) throw new Error('找不到「添加规则」按钮');
  addRule.click();
  await sleep(600);
  if (!modalOpen()) throw new Error('规则弹窗没出现');
  step('打开规则弹窗', '标题=' + (q('#modalTitle') || {}).textContent);

  // 目标：成员里的第一个
  const m0 = S.groupDraft.members[0];
  q('#ruleProv').value = m0.providerId;
  q('#ruleModel').value = m0.model;
  q('#ruleTokens').value = '1500';
  q('#ruleTime').value = '22:00-02:00';
  q('#ruleFolders').value = '~/work/acme, D:/proj/site';
  // 勾图片 + 压缩 + 周一三五
  q('#ruleImg').click();
  await sleep(120);
  const chips = Array.from(q('.day-pick').children);
  step('星期可选数', chips.length);
  [0, 2, 4].forEach((i) => chips[i].click());
  await sleep(120);
  // 点「确定」= 最后一个按钮
  const footBtns = Array.from(q('#modalFoot').querySelectorAll('button'));
  footBtns[footBtns.length - 1].click();
  await sleep(700);
  step('提交规则', '弹窗还开着=' + modalOpen() + ' toast=' + toastText());
  if (modalOpen()) {
    // 校验失败会留在原地：把原因记下来再关掉
    step('规则未通过校验', toastText());
    closeModal();
  }
  const r0 = (S.groupDraft.rules || [])[0];
  step('规则落库', r0 ? JSON.stringify(r0) : '无');

  // ---- 6. 保存组（真实写 settings）
  q('#btnSaveGroup').click();
  await sleep(1600);
  step('保存组', 'toast=' + toastText() + ' 脏=' + (S.groupDirty ? '是' : '否'));
  step('保存后列表', q('#tblGroups tbody').children.length + ' 行');

  // ---- 7. 切走再回来：草稿不该丢
  q('.nav-item[data-page="overview"]').click();
  await sleep(600);
  q('.nav-item[data-page="groups"]').click();
  await sleep(1200);
  step('重进页面', '编辑区=' + (q('#grId') ? '在' : '无') + ' 行数=' + q('#tblGroups tbody').children.length);

  // ---- 8. auto 组：**按 0.9.4 的既定设计断言**，不在表里出现
  //
  // 0.9.4 起 groups 表**刻意不再列 auto 行**（它曾同时出现在路由组页和智能路由页，
  // 用户以为重复），改为在卡片底部给一行脚注 .group-foot 说明「它归智能路由页维护」。
  // 上一版这里仍在断言「表里必须有一行 auto」，那是按旧设计写的，于是永远报
  // 「启用智能路由后仍没有 auto 组」——这是巡检自己的 bug，不是产品 bug。
  //
  // 正确断言三件事（都能独立成立）：
  //   a. auto 组在服务端确实建起来了（/ui/groups 里有 id=auto 的条目）
  //   b. 表里**没有** auto 行（设计如此）
  //   c. 底部脚注存在且带「去智能路由页」的跳转按钮
  const groupsApi = await (await fetch('/ui/groups')).json();
  const autoInApi = (groupsApi.groups || []).find((g) => g.id === 'auto');
  const autoRow = Array.from(q('#tblGroups tbody').querySelectorAll('tr'))
    .find((tr) => /auto/.test(tr.textContent));

  if (!autoInApi) {
    // 干净目录里 auto 要等智能路由启用才建，先去打开开关（真实用户路径）
    step('auto 组不存在', '去智能路由页启用开关');
    q('.nav-item[data-page="smartRoute"]').click();
    await sleep(900);
    const sw = q('#pageBody .switch input[type=checkbox]');
    if (sw && !sw.checked) { sw.click(); await sleep(1600); }
    q('.nav-item[data-page="groups"]').click();
    await sleep(1400);
  }
  const st2 = await (await fetch('/ui/groups')).json();
  const autoNow = (st2.groups || []).find((g) => g.id === 'auto');
  if (!autoNow) {
    out.errors.push('启用智能路由后服务端仍没有 auto 组（这是真问题，不是巡检问题）');
    step('auto 组检查', '服务端无 auto 组');
  } else {
    step('auto 组在服务端', '成员=' + autoNow.memberCount + ' 可用=' + autoNow.usableMembers
      + ' 启用=' + autoNow.enabled);
    // b. 表里不该有 auto 行（0.9.4 起的设计）
    const rowNow = Array.from(q('#tblGroups tbody').querySelectorAll('tr'))
      .find((tr) => /auto/.test(tr.textContent));
    if (rowNow) out.errors.push('groups 表里出现了 auto 行（0.9.4 起应为脚注，不是表格行）');
    step('表里无 auto 行', rowNow ? 'FAIL 出现了' : 'OK');
    // c. 脚注 + 跳转
    const foot = q('#pageBody .group-foot');
    step('底部脚注', foot ? ('有，' + (foot.textContent || '').trim().slice(0, 60)) : '无');
    if (!foot) out.errors.push('缺少 auto 组的底部脚注（用户会以为自己的组少了一个）');
    const jump = foot ? foot.querySelector('button.mini-btn') : null;
    if (foot && !jump) out.errors.push('脚注里没有「去智能路由页」的跳转按钮');
  }

  // ---- 9. 删除刚建的组
  q('.nav-item[data-page="groups"]').click();
  await sleep(900);
  const row = Array.from(q('#tblGroups tbody').querySelectorAll('tr'))
    .find((tr) => /walk-grp/.test(tr.textContent));
  if (!row) { step('找不到巡检组', '跳过删除'); }
  else {
    row.querySelector('button.mini-btn.danger').click();
    await sleep(500);
    if (modalOpen()) {
      const ok = Array.from(q('#modalFoot').querySelectorAll('button')).find((b) => b.textContent === '确定');
      if (ok) ok.click();
      await sleep(1400);
    }
    step('删除巡检组', 'toast=' + toastText() + ' 剩余行=' + q('#tblGroups tbody').children.length);
  }

  // ---- 10. 溢出检查（两栏与弹窗都要看）
  const over = [];
  const scan = (root, tag) => {
    root.querySelectorAll('*').forEach((n) => {
      if (n.scrollWidth > n.clientWidth + 2 && n.clientWidth > 0) {
        over.push(tag + ' ' + (n.className || n.tagName) + ' ' + n.scrollWidth + '>' + n.clientWidth);
      }
    });
  };
  scan(q('#pageBody'), 'body');
  scan(q('#tblGroups'), 'table');
  out.overflow = over.slice(0, 12);
  step('横向溢出', out.overflow.length + ' 处');
  return out;
})()
`;

async function main() {
  const profile = path.join(os.tmpdir(), 'aigw-cdp-groups');
  const chrome = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--window-size=1240,820', 'about:blank',
  ], { stdio: 'ignore', detached: false });

  let result = null;
  try {
    let wsUrl = null;
    for (let i = 0; i < 100; i++) {
      try {
        const r = await fetch('http://127.0.0.1:' + PORT + '/json/list');
        const list = await r.json();
        const page = list.find((x) => x.type === 'page');
        if (page) { wsUrl = page.webSocketDebuggerUrl; break; }
      } catch (_) { /* 还没起来 */ }
      await sleep(200);
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
    await sleep(3500);
    await cdp.eval('document.getElementById("modalMask").hidden = true; 1');

    const res = await cdp.eval(WALK, true);
    result = (res && res.__err) ? { fatal: res.__err } : (res || {});

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
    exceptions: (result.exceptions || []).length,
    consoleErrors: (result.consoleErrors || []).length,
    overflow: (result.overflow || []).length,
    issues: result.errors || [],
    fatal: result.fatal,
  }, null, 2));
  if (process.env.GROUPS_VERBOSE === '1') {
    (result.steps || []).forEach((s) => console.log('  ' + s));
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
