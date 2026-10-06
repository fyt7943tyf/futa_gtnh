/*
 * 「现在可以做」的完成判定实测（无头 Edge + CDP）。
 *
 * 玩家报过：明明没做完，「现在可以做」却写「所有步骤都已完成 🎉」，只能翻到下面的
 * 合成顺序接着做。根因是那里拿「记下来的 rid 条数」和步骤数比 ——
 * 而进度是按键（目标 + 数量）存的，同一个键下面还留着**上一版计划**的 rid
 * （改配方、切库存模式、仓库里多了东西导致步骤变少，都会换掉一批 rid）。
 *
 * 这个脚本就是照这个场景写的：往那个键里塞一批「对不上当前步骤」的 rid，
 * 条数刚好等于步骤数，然后断言界面不能声称已完成。
 *
 * 用法：node tools/web-test/done-progress.js [--base http://127.0.0.1:8766]
 */
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 9449;
const BASE = argValue('--base') || 'http://127.0.0.1:8765';
const EDGE_CANDIDATES = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
];

function argValue(name) {
    const i = process.argv.indexOf(name);
    return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : '';
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;
function check(label, ok, detail) {
    if (ok) {
        passed++;
        console.log('  PASS ' + label + (detail ? '  ' + detail : ''));
    } else {
        failed++;
        console.log('  FAIL ' + label + (detail ? '  ' + detail : ''));
    }
}

class Session {

    constructor(ws) {
        this.ws = ws;
        this.id = 0;
        this.pending = new Map();
        ws.addEventListener('message', (event) => {
            const msg = JSON.parse(event.data);
            const entry = this.pending.get(msg.id);
            if (!entry) return;
            this.pending.delete(msg.id);
            if (msg.error) entry.reject(new Error(JSON.stringify(msg.error)));
            else entry.resolve(msg.result);
        });
    }

    send(method, params) {
        const id = ++this.id;
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            this.ws.send(JSON.stringify({ id, method, params: params || {} }));
            setTimeout(() => {
                if (this.pending.delete(id)) reject(new Error(method + ' 超时'));
            }, 30000);
        });
    }

    async eval(expression) {
        const result = await this.send('Runtime.evaluate', {
            expression, returnByValue: true, awaitPromise: true
        });
        if (result.exceptionDetails) {
            throw new Error('页面脚本报错: ' + JSON.stringify(result.exceptionDetails.exception || {}));
        }
        return result.result ? result.result.value : undefined;
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
    const session = new Session(ws);
    await session.send('Page.enable');
    await session.send('Runtime.enable');
    return session;
}

async function waitFor(session, expression, timeoutMs, label) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        let value = null;
        try {
            value = await session.eval(expression);
        } catch (e) {
            value = null;
        }
        if (value) return value;
        if (Date.now() > deadline) throw new Error('等待超时: ' + label);
        await sleep(200);
    }
}

async function launchEdge() {
    const exe = EDGE_CANDIDATES.find((p) => fs.existsSync(p));
    if (!exe) throw new Error('找不到 msedge.exe');
    const profile = path.join(os.tmpdir(), 'futa-edge-done-' + Date.now());
    const child = spawn(exe, [
        '--headless=new', '--disable-gpu', '--no-first-run',
        '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
        '--window-size=420,1400', 'about:blank'
    ], { stdio: 'ignore' });
    for (let i = 0; i < 60; i++) {
        try {
            const res = await fetch('http://127.0.0.1:' + PORT + '/json/version');
            if (res.ok) return child;
        } catch (e) {
            // 还没起来
        }
        await sleep(250);
    }
    throw new Error('无头 Edge 没起来');
}

/** 「现在可以做」那一块的现状 + 概览里的完成数。 */
const READ_READY = `(() => {
    const sections = Array.from(document.querySelectorAll('.section'));
    const sec = sections.find((s) => /现在可以做/.test(String((s.querySelector('.section-title') || {}).textContent || '')));
    const badges = Array.from(document.querySelectorAll('.summary .badge')).map((b) => String(b.textContent));
    return JSON.stringify({
        found: !!sec,
        text: sec ? String(sec.textContent) : '',
        readyItems: sec ? sec.querySelectorAll('.ready-item').length : 0,
        saysAllDone: sec ? /所有步骤都已完成/.test(String(sec.textContent)) : false,
        doneBadge: badges.find((b) => /已完成/.test(b)) || null
    });
})()`;

(async () => {
    const child = await launchEdge();
    let session;
    try {
        session = await connect();

        // 找一件能规划出好几步的东西（用 stock=0 探，保证有步骤）
        const queries = ['电路板', '外壳', '齿轮', '马达', '机器'];
        let target = null;
        let count = 8;
        for (const q of queries) {
            const probe = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent(q) + '&limit=40')).json();
            for (const item of probe.items || []) {
                if (!item.craftable) continue;
                const plan = await (await fetch(BASE + '/api/plan?id=' + item.id + '&count=' + count + '&stock=0')).json();
                const steps = plan.steps || [];
                if (steps.length >= 3) {
                    target = { id: item.id, name: item.name, steps: steps.map((s) => String(s.rid)) };
                    break;
                }
            }
            if (target) break;
        }
        if (!target) {
            console.log('没找到步骤 ≥3 的目标，没法验这个');
            process.exit(1);
        }
        console.log('目标：' + target.name + ' #' + target.id + '（' + target.steps.length + ' 步，count=' + count + '）');

        await session.send('Page.navigate', { url: BASE + '/#/plan/' + target.id + '?count=' + count });
        await waitFor(session, "document.body.textContent.length > 50", 30000, '页面起来');
        // 固定成「从零算」，步骤才不会因为仓库里刚好够而变少
        await session.eval(`(() => {
            localStorage.setItem('futa_gtnh.ignorestock', '1');
            localStorage.removeItem('futa_gtnh.done');
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.ready-item')", 30000, '首批可做步骤');

        const clean = JSON.parse(await session.eval(READ_READY));
        check('干净状态：列得出可做步骤、没说已完成',
            clean.readyItems > 0 && clean.saysAllDone === false, JSON.stringify({ items: clean.readyItems, done: clean.doneBadge }));

        // ★ 关键场景：同一个键下面塞一批**对不上当前步骤**的 rid，条数正好等于步骤数
        //   （真实成因：改过配方 / 切过库存模式 / 仓库变多导致步骤变少）
        const bogus = [];
        for (let i = 0; i < target.steps.length; i++) bogus.push(String(900000000 + i));
        await session.eval(`(() => {
            const done = {};
            done[${JSON.stringify(String(target.id) + '@' + String(count))}] = ${JSON.stringify(bogus)};
            localStorage.setItem('futa_gtnh.done', JSON.stringify(done));
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.ready-item')", 30000, '旧 rid 场景：步骤仍然可做');

        const stale = JSON.parse(await session.eval(READ_READY));
        check('进度里全是「对不上」的旧 rid 时，不能声称已完成',
            stale.saysAllDone === false, '提示=' + (stale.saysAllDone ? '所有步骤都已完成 🎉（错）' : '没说已完成'));
        check('这些步骤照样出现在「现在可以做」里', stale.readyItems > 0, stale.readyItems + ' 步可做');
        check('概览里的完成数不是 N/N（旧 rid 不该算数）',
            !new RegExp('已完成 ' + target.steps.length + '/' + target.steps.length).test(String(stale.doneBadge)),
            String(stale.doneBadge));

        // 反向验证：真把当前步骤全勾上时，那句话要出现（别把功能修没了）
        await session.eval(`(() => {
            const done = {};
            done[${JSON.stringify(String(target.id) + '@' + String(count))}] = ${JSON.stringify(target.steps)};
            localStorage.setItem('futa_gtnh.done', JSON.stringify(done));
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        const allDone = JSON.parse(await waitFor(session,
            `(() => {
                const m = ${READ_READY};
                if (!m) return null;
                return JSON.parse(m).saysAllDone ? m : null;
            })()`, 30000, '全部做完的提示'));
        check('真的全做完时仍然会说「所有步骤都已完成」', allDone.saysAllDone === true,
            String(allDone.doneBadge));

        // 再做一次：只勾第一步（用真界面上的勾），完成数应当是 1/N
        await session.eval(`(() => {
            const done = {};
            done[${JSON.stringify(String(target.id) + '@' + String(count))}] = [${JSON.stringify(target.steps[0])}];
            localStorage.setItem('futa_gtnh.done', JSON.stringify(done));
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.ready-item') || /所有步骤都已完成/.test(document.body.textContent)", 30000, '勾一步之后');
        const one = JSON.parse(await session.eval(READ_READY));
        check('只勾一步时：完成数是 1/N，且没说全部完成',
            new RegExp('已完成 1/' + target.steps.length).test(String(one.doneBadge)) && one.saysAllDone === false,
            String(one.doneBadge));

        console.log('\n结果: ' + passed + ' 通过 / ' + failed + ' 失败');
    } catch (e) {
        failed++;
        console.log('  FAIL 脚本异常: ' + (e && e.message ? e.message : e));
    } finally {
        if (child) child.kill();
    }
    process.exit(failed === 0 ? 0 : 1);
})();
