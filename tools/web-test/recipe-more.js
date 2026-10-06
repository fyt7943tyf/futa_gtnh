/*
 * 「选择配方」弹层里的「加载更多配方」实测（无头 Edge + CDP，真的去点）。
 *
 * 玩家报过：规划页面点「选配方」，配方被卡在前 80 条，没有继续看的路。
 * 物品页早就有「加载更多配方」，弹层里漏了 —— 而弹层正是规划页换配方的地方。
 *
 * 用法：node tools/web-test/recipe-more.js [--base http://127.0.0.1:8766]
 */
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 9448;
const BASE = argValue('--base') || 'http://127.0.0.1:8765';
const SHOT_DIR = argValue('--shot') || path.join(os.tmpdir(), 'futa-ui-shots');
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
            expression,
            returnByValue: true,
            awaitPromise: true
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
    const profile = path.join(os.tmpdir(), 'futa-edge-more-' + Date.now());
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

/** 弹层里的现状：标题、元信息、加载更多按钮、配方组数。 */
const READ_MODAL = `(() => {
    const body = document.getElementById('modal-body');
    if (!body) return null;
    const meta = body.querySelector('.material-stat');
    const more = Array.from(body.querySelectorAll('button')).find((b) => /加载更多配方/.test(b.textContent));
    const modal = document.getElementById('modal');
    return JSON.stringify({
        title: (document.getElementById('modal-title') || {}).textContent || '',
        meta: meta ? String(meta.textContent) : '',
        moreText: more ? String(more.textContent) : null,
        groups: body.querySelectorAll('.recipe-group').length,
        hidden: modal ? !!modal.hidden : null
    });
})()`;

(async () => {
    const child = await launchEdge();
    let session;
    try {
        session = await connect();

        // 找一件配方条数超过默认返回上限的物品（后端默认 80，真实条数在 recipeTotal 里）
        const names = ['铁锭', '铜锭', '锡锭', '钢锭', '铝锭'];
        let pick = null;
        for (const name of names) {
            const search = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent(name) + '&limit=5')).json();
            for (const item of search.items || []) {
                if (!item.craftable) continue;
                const detail = await (await fetch(BASE + '/api/item?id=' + item.id)).json();
                const shown = (detail.recipes || []).length;
                const total = detail.recipeTotal !== undefined ? detail.recipeTotal : shown;
                if (total > shown && shown >= 40) {
                    pick = { id: item.id, name: item.name, shown: shown, total: total };
                    break;
                }
            }
            if (pick) break;
        }
        if (!pick) {
            console.log('没找到「配方条数超过一屏」的物品，没法验这个功能');
            process.exit(1);
        }
        console.log('测试物品：' + pick.name + ' #' + pick.id + '（默认只回 ' + pick.shown + ' 条，实际 ' + pick.total + ' 条）');

        await session.send('Page.navigate', { url: BASE + '/#/plan/' + pick.id + '?count=1' });
        await waitFor(session, "document.body.textContent.length > 50", 30000, '页面起来');
        // 按库存算时这件东西可能"已经够了"，计划里就没有步骤、也就没有「选配方」入口。
        // 切成从零算，保证计划里一定有步骤（这一步只影响这个临时浏览器 profile）。
        await session.eval(`(() => {
            localStorage.setItem('futa_gtnh.ignorestock', '1');
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "document.querySelectorAll('.unchosen-item').length > 0", 30000, '「还没指定配方」列表');
        const started = await session.eval(`(() => {
            const row = Array.from(document.querySelectorAll('.unchosen-item'))
                .find((r) => new RegExp(${JSON.stringify(pick.name)}).test(r.textContent));
            if (!row) return null;
            row.click();
            return true;
        })()`);
        if (!started) {
            console.log('计划里没有这件东西的「选配方」入口，跳过（可以换一件物品再跑）');
            process.exit(1);
        }

        const first = JSON.parse(await waitFor(session,
            `(() => {
                const m = ${READ_MODAL};
                return m && /共 /.test(JSON.parse(m).meta) ? m : null;
            })()`, 20000, '弹层里出现「/ 共 N 条」'));
        check('弹层里照实写了「只列出前 N 条 / 共 M 条」',
            /共 /.test(first.meta) && new RegExp('配方 ' + pick.shown).test(first.meta), first.meta.trim());
        check('弹层里有「加载更多配方」按钮', !!first.moreText && /还有/.test(first.moreText), String(first.moreText));

        const clickable = JSON.parse(await session.eval(`(() => {
            const body = document.getElementById('modal-body');
            const more = Array.from(body.querySelectorAll('button')).find((b) => /加载更多配方/.test(b.textContent));
            if (!more) return JSON.stringify({ found: false });
            // behavior:'instant' 不是多余：样式里有平滑滚动，滚到一半量出来的坐标是旧的，
            // 真鼠标点击就会落到别的元素上（这里踩过一次，看着像"点了没反应"）
            more.scrollIntoView({ block: 'center', behavior: 'instant' });
            const r = more.getBoundingClientRect();
            const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            const top = document.elementFromPoint(cx, cy);
            return JSON.stringify({
                found: true,
                x: Math.round(cx), y: Math.round(cy),
                clickable: top === more,
                topMost: top ? (top.tagName + '.' + String(top.className || '')) : null,
                groups: body.querySelectorAll('.recipe-group').length
            });
        })()`));
        check('「加载更多配方」点得到（没被盖住）', clickable.found && clickable.clickable === true, JSON.stringify(clickable));

        // 再量一次坐标（上面那次量完到点击之间可能发生重排）
        const spot = JSON.parse(await session.eval(`(() => {
            const body = document.getElementById('modal-body');
            const more = Array.from(body.querySelectorAll('button')).find((b) => /加载更多配方/.test(b.textContent));
            if (!more) return JSON.stringify({ found: false });
            const r = more.getBoundingClientRect();
            return JSON.stringify({ found: true, x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) });
        })()`));
        await session.send('Input.dispatchMouseEvent',
            { type: 'mousePressed', x: spot.x, y: spot.y, button: 'left', clickCount: 1 });
        await session.send('Input.dispatchMouseEvent',
            { type: 'mouseReleased', x: spot.x, y: spot.y, button: 'left', clickCount: 1 });

        const after = JSON.parse(await waitFor(session,
            `(() => {
                const m = ${READ_MODAL};
                if (!m) return null;
                const parsed = JSON.parse(m);
                const shown = Number(((parsed.meta.match(/配方 (\\d+)/) || [])[1]) || 0);
                return shown > ${pick.shown} ? m : null;
            })()`, 25000, '配方条数变多'));
        const shownAfter = Number(((after.meta.match(/配方 (\d+)/) || [])[1]) || 0);
        check('点一下真的从后端多拿了一批',
            shownAfter > pick.shown, pick.shown + ' → ' + shownAfter + '   ' + after.meta.trim());
        check('配方组数没有变少（多出来的也画出来了）',
            after.groups >= clickable.groups, clickable.groups + ' → ' + after.groups + ' 组');
        check('弹层没有因此被关掉', after.hidden === false, 'hidden=' + after.hidden);
        check('剩下的条数跟着变少（按钮文字更新）',
            !after.moreText || after.moreText !== first.moreText, String(first.moreText) + ' → ' + String(after.moreText));
        const shot = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const file = path.join(SHOT_DIR, 'recipe-more.png');
        fs.writeFileSync(file, Buffer.from(shot.data, 'base64'));
        console.log('  截图: ' + file);

        console.log('\n结果: ' + passed + ' 通过 / ' + failed + ' 失败');
    } catch (e) {
        failed++;
        console.log('  FAIL 脚本异常: ' + (e && e.message ? e.message : e));
    } finally {
        if (child) child.kill();
    }
    process.exit(failed === 0 ? 0 : 1);
})();
