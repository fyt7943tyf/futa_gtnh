/*
 * 「本地数据」页（#/settings）的行为断言。
 *
 * 这一页干的是破坏性操作（清空浏览器里存的清单/书签组/配方选择），
 * 所以不能只看「页面打开了」——必须真的点、真的清、真的核对剩下的东西：
 *   1. 「只清选择」清掉配方选择/原料标记，但清单和书签组必须还在；
 *   2. 「全部清空」才连清单一起清；
 *   3. 两个按钮都要点两次才算数（第一次只是变成确认态）；
 *   4. 导出/恢复能来回搬数据（换浏览器时用它）。
 *
 * 默认打 8766（tools/web-test/serve-live.js 的代理：新前端 + 真后端），
 * 这样改前端不用重启游戏就能验。
 *
 * 用法：node tools/web-test/settings.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('node:child_process');

const BASE = process.env.BASE || 'http://127.0.0.1:8766';
const PORT = Number(process.env.CDP_PORT || 9333);
const SHOT = path.join(process.env.TEMP, 'futa-shots', 'settings.png');
const EDGE_CANDIDATES = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0;
let fail = 0;
function check(name, ok, detail) {
    if (ok) {
        pass++;
        console.log('  PASS ' + name + (detail ? '  ' + detail : ''));
    } else {
        fail++;
        console.log('  FAIL ' + name + (detail ? '  ' + detail : ''));
    }
}

async function connect() {
    const res = await fetch('http://127.0.0.1:' + PORT + '/json/list');
    const targets = await res.json();
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('没有可用的页面目标');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        ws.addEventListener('open', resolve, { once: true });
        ws.addEventListener('error', reject, { once: true });
    });
    let id = 0;
    const pending = new Map();
    ws.addEventListener('message', (ev) => {
        const msg = JSON.parse(ev.data);
        const done = pending.get(msg.id);
        if (done) {
            pending.delete(msg.id);
            done(msg);
        }
    });
    const send = (method, params) => new Promise((resolve) => {
        const i = ++id;
        pending.set(i, resolve);
        ws.send(JSON.stringify({ id: i, method, params: params || {} }));
    });
    await send('Page.enable');
    await send('Runtime.enable');
    return send;
}

const evalIn = async (send, expression) => {
    const r = await send('Runtime.evaluate', { returnByValue: true, expression, awaitPromise: true });
    const res = r.result || {};
    if (res.exceptionDetails) return { __error: JSON.stringify(res.exceptionDetails).slice(0, 300) };
    return res.result ? res.result.value : null;
};

async function waitFor(send, expression, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const v = await evalIn(send, expression);
        if (v) return v;
        if (Date.now() > deadline) return null;
        await sleep(300);
    }
}

function launchEdge() {
    const exe = EDGE_CANDIDATES.find((p) => fs.existsSync(p));
    if (!exe) throw new Error('找不到 msedge.exe');
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'futa-settings-profile-'));
    spawn(
        exe,
        [
            '--headless=new',
            '--disable-gpu',
            '--hide-scrollbars',
            '--no-first-run',
            '--remote-debugging-port=' + PORT,
            '--user-data-dir=' + profile,
            '--window-size=420,1400',
            'about:blank'
        ],
        { stdio: 'ignore' }
    );
}

/** 按文字点按钮；点两次的那种，第二次按 .is-armed 找。 */
const clickText = (text) => `(() => {
    const b = Array.from(document.querySelectorAll('button')).find((x) => x.textContent.indexOf(${JSON.stringify(text)}) >= 0);
    if (!b) return 'no-button';
    b.click();
    return b.textContent;
})()`;
const clickArmed = `(() => {
    const b = document.querySelector('button.is-armed');
    if (!b) return 'no-armed';
    b.click();
    return 'clicked';
})()`;

const storage = `(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.indexOf('futa_gtnh.') === 0) out[k] = localStorage.getItem(k);
    }
    return out;
})()`;

(async () => {
    console.log('对象：' + BASE);
    const page = await (await fetch(BASE + '/app.js')).text();
    check('代理提供的是新前端（含 renderSettingsPage）', page.includes('renderSettingsPage'));

    launchEdge();
    let send = null;
    for (let i = 0; i < 40 && !send; i++) {
        await sleep(250);
        try {
            send = await connect();
        } catch (e) {
            /* 还没起来 */
        }
    }
    if (!send) throw new Error('连不上无头浏览器');

    await send('Page.navigate', { url: BASE + '/#/settings' });
    const listed = await waitFor(send, "document.querySelectorAll('.data-row').length > 0 || document.body.textContent.indexOf('存了什么') > 0", 30000);
    check('本地数据页能打开', !!listed);

    // 塞一份数据：选择类 3 项 + 清单/书签组 2 项
    await evalIn(send, `(() => {
        localStorage.clear();
        localStorage.setItem('futa_gtnh.choices', JSON.stringify({ 1: '2' }));
        localStorage.setItem('futa_gtnh.raw', JSON.stringify([1]));
        localStorage.setItem('futa_gtnh.done', JSON.stringify({ '1:1': true }));
        localStorage.setItem('futa_gtnh.basket', JSON.stringify([{ id: 1, count: 4 }]));
        localStorage.setItem('futa_gtnh.groups', JSON.stringify({ version: 1, activeId: 'g1', groups: [{ id: 'g1', name: '测试', items: [{ id: 1, count: 4 }] }] }));
        return Object.keys(localStorage).length;
    })()`);
    await send('Page.reload', { ignoreCache: true });
    await waitFor(send, "document.querySelectorAll('.data-row').length >= 5", 30000);
    const rows = await evalIn(send, "document.querySelectorAll('.data-row').length");
    check('页面把 5 项都列了出来', rows === 5, '实际 ' + rows + ' 行');
    const labels = await evalIn(send, "Array.from(document.querySelectorAll('.data-name')).map((x) => x.textContent).join(' / ')");
    console.log('    列出：' + labels);

    // 第一次点击只应该变成确认态，不能真清
    const first = await evalIn(send, clickText('只清选择'));
    const stillThere = await evalIn(send, storage);
    check('第一次点击不清（只变成确认态）', Object.keys(stillThere).length === 5, '点击后返回：' + first);

    await evalIn(send, clickArmed);
    const afterSel = await evalIn(send, storage);
    check('「只清选择」清掉了 choices/raw/done', !afterSel['futa_gtnh.choices'] && !afterSel['futa_gtnh.raw'] && !afterSel['futa_gtnh.done']);
    check('「只清选择」保留了清单和书签组', !!afterSel['futa_gtnh.basket'] && !!afterSel['futa_gtnh.groups']);
    const statusText = await evalIn(send, "(document.getElementById('local-status') || {}).textContent || ''");
    console.log('    状态行：' + statusText);

    // 导出 → 全清 → 恢复
    await evalIn(send, clickText('导出备份到文本框'));
    const boxed = await evalIn(send, "document.getElementById('local-backup').value");
    check('导出把剩余数据写进了文本框', !!boxed && boxed.includes('futa_gtnh.groups'), (boxed || '').slice(0, 60).replace(/\n/g, ' '));

    await evalIn(send, clickText('全部清空'));
    await evalIn(send, clickArmed);
    const afterAll = await evalIn(send, storage);
    check('「全部清空」把清单和书签组也清了', Object.keys(afterAll).length === 0, '剩下 ' + Object.keys(afterAll).join(','));

    await evalIn(send, "(() => { document.getElementById('local-backup').value = " + JSON.stringify(boxed) + "; return true; })()");
    await evalIn(send, clickText('从文本框恢复'));
    await sleep(500);
    const restored = await evalIn(send, storage);
    check('从文本框恢复能还原清单和书签组', !!restored['futa_gtnh.basket'] && !!restored['futa_gtnh.groups'], '恢复后 ' + Object.keys(restored).join(','));

    fs.mkdirSync(path.dirname(SHOT), { recursive: true });
    const shot = await send('Page.captureScreenshot', { format: 'png' });
    if (shot && shot.result && shot.result.data) {
        fs.writeFileSync(SHOT, Buffer.from(shot.result.data, 'base64'));
        console.log('    截图：' + SHOT);
    }

    // 搜索页上要有入口
    await send('Page.navigate', { url: BASE + '/#/search' });
    await waitFor(send, "!!document.querySelector('a[href=\"#/settings\"]')", 20000);
    const entry = await evalIn(send, "!!document.querySelector('a[href=\"#/settings\"]')");
    check('搜索页有「本地数据」入口', entry === true);

    console.log('\n' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})().catch((err) => {
    console.error('跑不下去了：' + err.message);
    process.exit(2);
});
