/*
 * 「做几套」的行为实测：无头 Edge + CDP，真的去点。
 *
 * 需要网页服务在跑。默认打 8765；改前端时可以配 8766（tools/web-test/serve-live.js 的
 * 实时代理：页面文件取新的、接口还是转给游戏）。
 *   node tools/web-test/basket-times.js [--base http://127.0.0.1:8766]
 *
 * 盯的是三件事，每一件都属于「看着对、其实是错的」那一类：
 *   1. 兼容：老的书签组数据里没有 times 字段，读出来必须是 1 套，数量一个都不能少；
 *   2. 语义：套数是「这套做几份」，3 → 2 要按比例回落（不是再乘一遍变成 6 套）；
 *   3. 落盘：套数记在组上 —— 切走再切回来、甚至重开页面，它和乘过的数量都得还在。
 *
 * 顺带断言「当前」那一件不受套数影响（它属于「你这次想看的东西」，不属于这一套）。
 */
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 9445;
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

    async goto(url) {
        await this.send('Page.navigate', { url });
        await sleep(400);
    }

    async shot(name) {
        const result = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const file = path.join(SHOT_DIR, name + '.png');
        fs.writeFileSync(file, Buffer.from(result.data, 'base64'));
        return file;
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

/**
 * 轮询等页面进入某个状态。
 *
 * <p>
 * **异常也要重试**：页面重绘时 {@code .group-times} 会短暂消失，
 * 表达式里读它的属性会抛 null —— 那不是"功能坏了"，是读早了一帧。
 */
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
    const profile = path.join(os.tmpdir(), 'futa-edge-basket-' + Date.now());
    const child = spawn(exe, [
        '--headless=new',
        '--disable-gpu',
        '--no-first-run',
        '--remote-debugging-port=' + PORT,
        '--user-data-dir=' + profile,
        '--window-size=420,1400',
        'about:blank'
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

/** 当前组的存档（测试全靠它核对「存进去的到底是什么」）。 */
const READ_STORE = `(() => {
    const store = JSON.parse(localStorage.getItem('futa_gtnh.groups') || 'null');
    if (!store) return null;
    const active = store.groups.find((g) => g.id === store.activeId) || null;
    return JSON.stringify({
        groupCount: store.groups.length,
        activeId: store.activeId,
        activeName: active ? active.name : null,
        times: active ? active.times : null,
        items: active ? active.items : null
    });
})()`;

/** 清单各行：编号从图标地址里取（不依赖名字，名字要等接口回来才有）。 */
const READ_ROWS = `(() => {
    const rows = Array.from(document.querySelectorAll('.basket-row')).map((row) => {
        const img = row.querySelector('img');
        const src = img ? String(img.getAttribute('src') || '') : '';
        const m = /\\/api\\/icon\\/(\\d+)\\.png/.exec(src);
        const input = row.querySelector('input.input-count');
        return {
            id: m ? Number(m[1]) : -1,
            value: input ? input.value : null,
            current: row.classList.contains('is-current')
        };
    });
    return JSON.stringify(rows);
})()`;

const READ_TIMES_UI = `(() => {
    const box = document.querySelector('.group-times');
    if (!box) return null;
    const input = box.querySelector('input');
    const buttons = box.querySelectorAll('button');
    return JSON.stringify({
        value: input ? input.value : null,
        minusDisabled: buttons[0] ? !!buttons[0].disabled : null,
        text: String(box.textContent || '')
    });
})()`;

const rowOf = (rows, id) => rows.find((r) => r.id === id) || null;

(async () => {
    const child = await launchEdge();
    let session;
    try {
        session = await connect();

        // 拿几件真实存在的物品：id 每个会话都会重排，写死 id 的断言会指向别的东西
        const search = async (q) => {
            const r = await fetch(BASE + '/api/search?q=' + encodeURIComponent(q) + '&limit=6');
            const j = await r.json();
            return j.items || [];
        };
        const iron = (await search('铁锭'))[0];
        const tin = (await search('锡锭'))[0];
        const pool = await search('铜锭');
        const target = pool.find((it) => it.id !== iron.id && it.id !== tin.id);
        if (!iron || !tin || !target) {
            console.log('索引里没搜到测试用的物品（铁锭/锡锭/铜锭），先让客户端把索引建好再跑');
            process.exit(1);
        }

        await session.goto(BASE + '/#/plan/' + target.id + '?count=64');
        await waitFor(session, "document.body.textContent.length > 50", 30000, '页面起来');
        await session.eval('localStorage.clear(); true');

        // 1) 老数据：甲组**没有** times 字段（升级前就是长这样），数量必须原样
        const legacyItems = [{ id: iron.id, count: 64 }, { id: tin.id, count: 3 }];
        const legacyStore = {
            version: 1,
            activeId: 'gA',
            groups: [
                { id: 'gA', name: '甲套', items: legacyItems },
                { id: 'gB', name: '乙套', times: 2, items: [{ id: tin.id, count: 5 }] }
            ]
        };
        await session.eval(`(() => {
            localStorage.setItem('futa_gtnh.groups', ${JSON.stringify(JSON.stringify(legacyStore))});
            localStorage.setItem('futa_gtnh.basket', ${JSON.stringify(JSON.stringify(legacyItems))});
            localStorage.setItem('futa_gtnh.choices', JSON.stringify({ 111: 222 }));
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.group-times')", 30000, '「做几套」控件出现');

        const legacyRead = JSON.parse(await session.eval(READ_STORE));
        check('老组（存档里没有 times 字段）在界面上就是 1 套',
            JSON.parse(await session.eval(READ_TIMES_UI)).value === '1',
            'times=' + legacyRead.times + ' 界面=' + JSON.parse(await session.eval(READ_TIMES_UI)).value);
        check('老组的数量一个都没变',
            JSON.stringify(legacyRead.items) === JSON.stringify(legacyItems), JSON.stringify(legacyRead.items));

        const ui0 = JSON.parse(await session.eval(READ_TIMES_UI));
        check('1 套时减号是禁用的', ui0.minusDisabled === true, 'disabled=' + ui0.minusDisabled);

        const rows0 = JSON.parse(await session.eval(READ_ROWS));
        check('三行都在：当前这一件 + 清单里的两件',
            rows0.length === 3 && rowOf(rows0, iron.id).value === '64' && rowOf(rows0, tin.id).value === '3',
            JSON.stringify(rows0));
        check('「当前」那一行是这次的目标（数量 64）',
            rowOf(rows0, target.id) !== null && rowOf(rows0, target.id).current === true,
            JSON.stringify(rowOf(rows0, target.id)));
        const badges = await session.eval(`Array.from(document.querySelectorAll('.group-tab')).map((t) => t.textContent).join(' | ')`);
        check('乙套（2 套）的标签上有 ×2 角标', /×2/.test(badges), badges);

        // 2) 点两次「＋」：3 套。数量要一起 ×3，而且规划请求里要带乘过的数量
        await session.eval(`(() => {
            window.__planUrls = [];
            const orig = window.fetch;
            window.fetch = function (u) {
                if (String(u).indexOf('/api/plan') >= 0) window.__planUrls.push(String(u));
                return orig.apply(this, arguments);
            };
            const box = document.querySelector('.group-times');
            if (box) box.querySelectorAll('button')[1].click();
            return true;
        })()`);
        await waitFor(session, `(() => {
            const box = document.querySelector('.group-times');
            return box && box.querySelector('input').value === '2' ? true : null;
        })()`, 15000, '第一次 + 生效');
        await session.eval(`(() => {
            const box = document.querySelector('.group-times');
            if (box) box.querySelectorAll('button')[1].click();
            return true;
        })()`);
        await waitFor(session, `(() => {
            const box = document.querySelector('.group-times');
            return box && box.querySelector('input').value === '3' ? true : null;
        })()`, 15000, '第二次 + 生效');
        await sleep(900);

        const times3 = JSON.parse(await session.eval(READ_STORE));
        check('套数写进了组（3 套）', times3.times === 3, 'times=' + times3.times);
        check('整组数量一起 ×3（64→192、3→9）',
            rowOf(times3.items, iron.id).count === 192 && rowOf(times3.items, tin.id).count === 9,
            JSON.stringify(times3.items));

        const rows3 = JSON.parse(await session.eval(READ_ROWS));
        check('页面上的数量也跟着变成 192 / 9',
            rowOf(rows3, iron.id).value === '192' && rowOf(rows3, tin.id).value === '9', JSON.stringify(rows3));

        const title = await session.eval(`(document.querySelector('.section-title') || {}).textContent || ''`);
        check('标题里写出了「×3」', /×3/.test(String(title)), String(title).trim());

        const plans = JSON.parse(await session.eval(`JSON.stringify(window.__planUrls || [])`));
        const lastPlan = decodeURIComponent(plans.length ? plans[plans.length - 1] : '');
        check('改套数会重新规划，请求里带的是乘过的数量',
            plans.length > 0 && lastPlan.indexOf(':' + iron.id + ':192') < 0 && /192/.test(lastPlan) && /(^|,|\D)9(,|$)/.test(lastPlan),
            '请求数=' + plans.length + ' 末条=' + lastPlan.slice(0, 160));

        // 3) 切到乙套：控件要显示它自己的 2 套，切回来甲套还是 3 套
        await session.eval(`(() => {
            const tab = Array.from(document.querySelectorAll('.group-tab')).find((t) => /乙套/.test(t.textContent));
            if (tab) tab.click();
            return true;
        })()`);
        const switched = await waitFor(session, `(() => {
            const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
            if (store.activeId !== 'gB') return null;
            const box = document.querySelector('.group-times');
            return box ? box.querySelector('input').value : null;
        })()`, 15000, '切到乙套');
        check('切到乙套：控件显示它自己的 2 套', switched === '2', 'value=' + switched);

        await session.eval(`(() => {
            const tab = Array.from(document.querySelectorAll('.group-tab')).find((t) => /甲套/.test(t.textContent));
            if (tab) tab.click();
            return true;
        })()`);
        const backValue = await waitFor(session, `(() => {
            const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
            if (store.activeId !== 'gA') return null;
            const box = document.querySelector('.group-times');
            return box ? box.querySelector('input').value : null;
        })()`, 15000, '切回甲套');
        check('切回甲套：还是 3 套（这就是「某个清单做几个」）', backValue === '3', 'value=' + backValue);

        // 4) 3 套改成 2 套：按比例回落，不是再乘一遍
        await session.eval(`(() => {
            const input = document.querySelector('.group-times input');
            if (input) {
                input.value = '2';
                input.dispatchEvent(new Event('change', { bubbles: true }));
            }
            return true;
        })()`);
        await sleep(900);
        const times2 = JSON.parse(await session.eval(READ_STORE));
        check('3 套改成 2 套是按比例回落（192→128、9→6），不是再乘一遍',
            times2.times === 2 && rowOf(times2.items, iron.id).count === 128 && rowOf(times2.items, tin.id).count === 6,
            'times=' + times2.times + ' items=' + JSON.stringify(times2.items));

        // 5) 手改一行：按新值存，另一行和套数都不动
        await session.eval(`(() => {
            const rows = Array.from(document.querySelectorAll('.basket-row'));
            const row = rows.find((r) => {
                const img = r.querySelector('img');
                const m = img ? /\\/api\\/icon\\/(\\d+)\\.png/.exec(String(img.getAttribute('src') || '')) : null;
                return m && Number(m[1]) === ${tin.id};
            });
            const input = row ? row.querySelector('input.input-count') : null;
            if (input) {
                input.value = '100';
                input.dispatchEvent(new Event('change', { bubbles: true }));
            }
            return true;
        })()`);
        await sleep(900);
        const edited = JSON.parse(await session.eval(READ_STORE));
        check('手改一行之后按新值存（另一行不动、套数不动）',
            rowOf(edited.items, tin.id).count === 100 && rowOf(edited.items, iron.id).count === 128
                && edited.times === 2,
            JSON.stringify(edited.items) + ' times=' + edited.times);

        // 6) 冷启动：重新加载页面，套数和数量都还在
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.group-times')", 30000, '重载后再出现控件');
        const reloaded = JSON.parse(await session.eval(READ_STORE));
        const reloadedUi = JSON.parse(await session.eval(READ_TIMES_UI));
        check('重开页面后套数还在（2 套）', reloaded.times === 2 && reloadedUi.value === '2',
            'times=' + reloaded.times + ' 界面=' + reloadedUi.value);
        check('重开页面后数量还是乘过的那些',
            rowOf(reloaded.items, iron.id).count === 128 && rowOf(reloaded.items, tin.id).count === 100,
            JSON.stringify(reloaded.items));

        // 7) 复制一份：套数跟着复制，副本不该显得少做了几份
        await session.eval(`(() => {
            window.prompt = function () { return '副本测试'; };
            const copy = Array.from(document.querySelectorAll('.group-tools button'))
                .find((b) => /复制一份/.test(b.textContent));
            if (copy) copy.click();
            return true;
        })()`);
        await sleep(900);
        const copied = JSON.parse(await session.eval(READ_STORE));
        check('复制出来的组带着套数（2 套）',
            copied.times === 2 && copied.groupCount === 3, 'times=' + copied.times + ' 组数=' + copied.groupCount);

        // 8) 「做几套」要把**正在看的那一件**也乘上，而且下面那张材料表真的会重算。
        //
        // 玩家实测报过一次「选了 n 套，下面材料数量好像没变」：那时只乘了清单里的东西，
        // 而他点开的那件东西往往就是这一套的主体 —— 材料表里几乎全是它，看着就像没反应。
        await session.eval(`(() => {
            const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
            store.groups = [{
                id: 'gC',
                name: '整套',
                times: 1,
                items: [{ id: ${target.id}, count: 4 }, { id: ${tin.id}, count: 2 }]
            }];
            store.activeId = 'gC';
            localStorage.setItem('futa_gtnh.groups', JSON.stringify(store));
            return true;
        })()`);
        await session.goto(BASE + '/#/plan/' + target.id + '?count=4');
        // 真重载一次：页面状态里的数量会归零，地址栏那个 count=4 才说了算
        // （同一个目标重画时是刻意保留页面状态的，见 renderPlanPage）
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.target .material-stat .need')", 30000, '计划页头部的数量出来');

        const READ_PLAN_VIEW = `(() => {
            const need = document.querySelector('.target .material-stat .need');
            const rows = Array.from(document.querySelectorAll('.basket-row')).map((row) => {
                const img = row.querySelector('img');
                const src = img ? String(img.getAttribute('src') || '') : '';
                const m = /\\/api\\/icon\\/(\\d+)\\.png/.exec(src);
                const input = row.querySelector('input.input-count');
                return {
                    id: m ? Number(m[1]) : -1,
                    value: input ? input.value : null,
                    current: row.classList.contains('is-current')
                };
            });
            return JSON.stringify({
                need: need ? String(need.textContent) : '',
                rows: rows,
                plans: (window.__planUrls || []).length,
                lastPlan: (window.__planUrls || []).slice(-1)[0] || ''
            });
        })()`;

        const before = JSON.parse(await session.eval(`(() => {
            window.__planUrls = [];
            const orig = window.fetch;
            window.fetch = function (u) {
                if (String(u).indexOf('/api/plan') >= 0) window.__planUrls.push(String(u));
                return orig.apply(this, arguments);
            };
            return ${READ_PLAN_VIEW};
        })()`));
        const beforeTimes = JSON.parse(await session.eval(READ_TIMES_UI));
        check('起点：1 套、目标 4 个', beforeTimes.value === '1' && before.need.trim() === '合成 4 个',
            '套数=' + beforeTimes.value + ' 头部=' + before.need.trim());

        await session.eval(`(() => {
            const box = document.querySelector('.group-times');
            if (box) box.querySelectorAll('button')[1].click();
            return true;
        })()`);
        await waitFor(session, `(() => {
            const box = document.querySelector('.group-times');
            return box && box.querySelector('input').value === '2' ? true : null;
        })()`, 15000, '＋ 到 2 套');
        await waitFor(session, `(() => {
            const need = document.querySelector('.target .material-stat .need');
            return need && /8/.test(String(need.textContent)) ? true : null;
        })()`, 20000, '材料表按 2 套重算');

        const after = JSON.parse(await session.eval(READ_PLAN_VIEW));
        check('正在看的那一件也一起乘了（头部数量 4 → 8）',
            before.need.trim() === '合成 4 个' && /8/.test(after.need), before.need.trim() + ' → ' + after.need.trim());
        check('下面的规划真的重算了一遍（不是只改了数字）',
            after.plans > 0 && /(^|[?&])count=8(&|$)/.test(decodeURIComponent(after.lastPlan)),
            '请求数=' + after.plans + ' 末条=' + decodeURIComponent(after.lastPlan).slice(0, 140));

        const rowNow = rowOf(after.rows, target.id);
        check('「当前」那一行也跟着显示 8（它和这套是一回事）',
            rowNow !== null && rowNow.current === true && rowNow.value === '8',
            JSON.stringify(rowNow));

        const storeNow = JSON.parse(await session.eval(READ_STORE));
        check('这一套里存的那件也跟着乘（组里 4 → 8、2 → 4）',
            rowOf(storeNow.items, target.id).count === 8 && rowOf(storeNow.items, tin.id).count === 4,
            JSON.stringify(storeNow.items));

        // 9) 兼容：老键和玩家的选择都不该被动
        const leftovers = JSON.parse(await session.eval(`(() => JSON.stringify({
            basket: localStorage.getItem('futa_gtnh.basket'),
            choices: localStorage.getItem('futa_gtnh.choices')
        }))()`));
        check('旧键 basket 还在（降级也能用）', !!leftovers.basket, String(leftovers.basket).slice(0, 60));
        check('玩家的配方选择 choices 没被动过', leftovers.choices === JSON.stringify({ 111: 222 }), String(leftovers.choices));

        // 10) 「目标不扣库存」这个模式：开关在、点了会带上参数、和「从零算」互斥
        //     （语义本身由 target-stock.js 打接口验，这里只验界面这条线）
        const tgtUI = JSON.parse(await session.eval(`(() => {
            const btn = Array.from(document.querySelectorAll('.target button'))
                .find((b) => /目标/.test(b.textContent));
            return JSON.stringify({
                found: !!btn,
                text: btn ? btn.textContent : null,
                pressed: btn ? btn.getAttribute('aria-pressed') : null,
                disabled: btn ? !!btn.disabled : null,
                store: localStorage.getItem('futa_gtnh.targetstock')
            });
        })()`));
        check('计划页有「目标…库存」开关', tgtUI.found === true, JSON.stringify(tgtUI));
        check('默认是「目标也扣库存」（老行为不变）',
            tgtUI.text === '目标也扣库存' && tgtUI.pressed === 'false', String(tgtUI.text));

        await session.eval(`(() => {
            window.__planUrls = [];
            const btn = Array.from(document.querySelectorAll('.target button'))
                .find((b) => /目标/.test(b.textContent));
            if (btn) btn.click();
            return true;
        })()`);
        await waitFor(session, `(() => {
            const btn = Array.from(document.querySelectorAll('.target button'))
                .find((b) => /目标/.test(b.textContent));
            return btn && /目标不扣库存/.test(btn.textContent) ? true : null;
        })()`, 15000, '切到「目标不扣库存」');
        const tgtAfter = JSON.parse(await session.eval(`(() => {
            const urls = window.__planUrls || [];
            const btn = Array.from(document.querySelectorAll('.target button'))
                .find((b) => /目标/.test(b.textContent));
            return JSON.stringify({
                text: btn ? btn.textContent : null,
                store: localStorage.getItem('futa_gtnh.targetstock'),
                lastPlan: urls.length ? urls[urls.length - 1] : ''
            });
        })()`));
        check('点一下就切到「目标不扣库存」并记在本地',
            tgtAfter.text === '目标不扣库存' && tgtAfter.store === '0', JSON.stringify(tgtAfter));
        check('重算的请求里带上了 targetstock=0',
            /targetstock=0/.test(decodeURIComponent(tgtAfter.lastPlan)), decodeURIComponent(tgtAfter.lastPlan).slice(0, 160));

        await session.eval(`(() => {
            localStorage.setItem('futa_gtnh.ignorestock', '1');
            localStorage.setItem('futa_gtnh.targetstock', '1');
            return true;
        })()`);
        await session.goto(BASE + '/#/plan/' + target.id + '?count=4');
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "!!document.querySelector('.target')", 30000, '从零算模式下重画');
        const exclusive = JSON.parse(await session.eval(`(() => {
            const btn = Array.from(document.querySelectorAll('.target button'))
                .find((b) => /目标/.test(b.textContent));
            return JSON.stringify({ disabled: btn ? !!btn.disabled : null, text: btn ? btn.textContent : null });
        })()`));
        check('「从零算」时这个开关是灰的（那时谁都不扣，它没有意义）',
            exclusive.disabled === true, JSON.stringify(exclusive));
        await session.eval(`localStorage.setItem('futa_gtnh.ignorestock', '0'); true`);

        const file = await session.shot('basket-times');
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
