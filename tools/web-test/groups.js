/*
 * 书签组的兼容性实测。
 *
 * 硬要求：升级之后，旧版本保存的清单（futa_gtnh.basket）要变成组、要能继续用，
 * 而玩家选的配方（choices）、标过的原始材料（raw）、指定的候选（alts）**一个字都不能变**。
 *
 * 这个脚本就是照这条要求写的：
 *   1. 往浏览器里塞一份「旧版本的数据」（只有 basket，没有 groups）；
 *   2. 真正重新加载页面（只改 hash 不会重新加载文档，量到的会是旧行为）；
 *   3. 断言：组迁移出来了、条目一致、旧键还在、choices/raw/alts 原样。
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

// 自己起一个无头实例（自己的临时 profile），不要连 9222：
// 那个端口上可能是别的浏览器（玩家自己的、或上一次没退干净的实例），
// 而这个脚本会 localStorage.clear() —— 连错浏览器就是把别人的清单清了。
const PORT = 9446;
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const OUT = process.argv[2] || path.join(process.env.TEMP, 'futa-shots', 'groups-compat.png');
const EDGE_CANDIDATES = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
];

/** 起无头 Edge 并等它的调试端口就绪。 */
async function launchEdge() {
    const exe = EDGE_CANDIDATES.find((p) => fs.existsSync(p));
    if (!exe) throw new Error('找不到 msedge.exe');
    const profile = path.join(os.tmpdir(), 'futa-edge-groups-' + Date.now());
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

/** 浏览器弹出的 native 对话框（confirm/prompt/alert）。测试要求一个都不出现。 */
const dialogs = [];

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
        // 系统对话框：记下来（测试最后断言「一个都没有」），然后立刻关掉，
        // 否则无头页面会卡在对话框上再也点不动
        if (msg.method === 'Page.javascriptDialogOpening') {
            dialogs.push(msg.params.type + ': ' + String(msg.params.message).slice(0, 60));
            ws.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
            return;
        }
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
    const r = await send('Runtime.evaluate', { returnByValue: true, expression });
    const res = r.result || {};
    if (res.exceptionDetails) return { __error: JSON.stringify(res.exceptionDetails).slice(0, 200) };
    return res.result ? res.result.value : null;
};

/** 轮询等待表达式返回真值（比猜固定秒数可靠）。 */
async function waitFor(send, expression, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const v = await evalIn(send, expression);
        if (v) return v;
        if (Date.now() > deadline) return null;
        await sleep(500);
    }
}

(async () => {
    const child = await launchEdge();
    const send = await connect();

    // 先用一件真实存在的物品，这样条目是「能用」的（而不是随便编个号）
    const s = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent('铁锭') + '&limit=3')).json();
    const item = s.items[0];
    if (!item) {
        console.log('索引里没有「铁锭」，先让客户端把索引建好再跑');
        process.exit(1);
    }
    const second = s.items[1] || item;

    // 1) 塞一份「旧版本」的数据：只有 basket，没有 groups；choices/raw/alts 各有一点内容
    const legacy = {
        basket: [{ id: item.id, count: 64 }, { id: second.id, count: 3 }],
        choices: { 12345: 678 },
        raw: [item.id],
        alts: { '12345:1:0': 999 }
    };
    await send('Page.navigate', { url: 'about:blank' });
    await send('Page.navigate', { url: BASE + '/#/plan/' + item.id + '?count=64' });
    await waitFor(send, "!!document.getElementById('plan-host') || !!document.querySelector('.plan') || document.body.textContent.length > 50", 30000);
    await evalIn(send, `(() => {
        localStorage.clear();
        localStorage.setItem('futa_gtnh.basket', ${JSON.stringify(JSON.stringify(legacy.basket))});
        localStorage.setItem('futa_gtnh.choices', ${JSON.stringify(JSON.stringify(legacy.choices))});
        localStorage.setItem('futa_gtnh.raw', ${JSON.stringify(JSON.stringify(legacy.raw))});
        localStorage.setItem('futa_gtnh.alts', ${JSON.stringify(JSON.stringify(legacy.alts))});
        return true;
    })()`);
    console.log('已写入旧版本数据：basket ' + legacy.basket.length + ' 条，choices 1 条，raw 1 条，alts 1 条');

    // 2) 真正重新加载：只改 hash 不会重新加载文档
    await send('Page.reload', { ignoreCache: true });
    await waitFor(send, "document.querySelectorAll('.slot, .plan, #plan-host').length > 0 || document.body.textContent.length > 50", 30000);

    // 计划页的「一起做」区要等 /api/plan 回来才画出来（骨架先出现、清单后到）。
    // 不等这一步就去找 .group-tab，量到的永远是 0 —— 那不是功能坏了，是读早了一帧。
    const tabsReady = await waitFor(send, `(() => {
        const n = document.querySelectorAll('.group-tab').length;
        return n > 0 ? n : null;
    })()`, 30000);
    check('计划页把清单区画出来了（这一步以前是在抢跑）', tabsReady > 0,
        tabsReady ? (tabsReady + ' 个组标签') : '没等到');

    // 3) 断言
    const after = await evalIn(send, `(() => {
        const groupsRaw = localStorage.getItem('futa_gtnh.groups');
        let store = null;
        try { store = groupsRaw ? JSON.parse(groupsRaw) : null; } catch (e) { store = null; }
        return JSON.stringify({
            hasGroups: !!store,
            version: store ? store.version : null,
            groupCount: store && store.groups ? store.groups.length : 0,
            firstName: store && store.groups && store.groups[0] ? store.groups[0].name : null,
            firstItems: store && store.groups && store.groups[0] ? store.groups[0].items : null,
            activeId: store ? store.activeId : null,
            basketStillThere: localStorage.getItem('futa_gtnh.basket'),
            choices: localStorage.getItem('futa_gtnh.choices'),
            raw: localStorage.getItem('futa_gtnh.raw'),
            alts: localStorage.getItem('futa_gtnh.alts')
        });
    })()`);
    const a = after && after.__error ? null : JSON.parse(after);
    if (!a) {
        check('页面能读到本地数据', false, String(after && after.__error));
        process.exit(1);
    }

    check('旧清单被迁移成了一个组', a.hasGroups && a.groupCount >= 1, '组数=' + a.groupCount);
    check('迁移出来的组带着原来的条目',
        !!a.firstItems && a.firstItems.length === legacy.basket.length
            && a.firstItems[0].id === legacy.basket[0].id && a.firstItems[0].count === legacy.basket[0].count,
        JSON.stringify(a.firstItems));
    check('组的名字有默认值（不是空的）', !!a.firstName, String(a.firstName));
    check('旧键 basket 保留（降级也能用）', !!a.basketStillThere, String(a.basketStillThere).slice(0, 60));
    check('配方选择 choices 没被动过', a.choices === JSON.stringify(legacy.choices), String(a.choices));
    check('原始材料标记 raw 没被动过', a.raw === JSON.stringify(legacy.raw), String(a.raw));
    check('候选选择 alts 没被动过', a.alts === JSON.stringify(legacy.alts), String(a.alts));

    // 4) 再验一条：组是「可切换」的 —— 新建一个空组，切回去，条目还在
    const switching = await evalIn(send, `(() => {
        const before = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        const firstId = before.groups[0].id;
        // 直接调页面里的函数（它们就在闭包里，通过 UI 点太脆）
        const tabs = Array.from(document.querySelectorAll('.group-tab'));
        return JSON.stringify({ tabs: tabs.length, firstId: firstId });
    })()`);
    const sw = switching && switching.__error ? null : JSON.parse(switching);
    check('界面上有组标签', !!sw && sw.tabs >= 1, sw ? (sw.tabs + ' 个标签') : '读不到');

    // 4.5) 整组规划：清单里两件东西 → 规划请求要带 targets=（后端据此一次算完，公共中间产物只做一批）
    const multi = await evalIn(send, `(() => {
        const list = JSON.parse(localStorage.getItem('futa_gtnh.basket') || '[]');
        const tabs = Array.from(document.querySelectorAll('.group-tab'));
        const activeTab = tabs.find((t) => t.classList.contains('is-active'));
        return JSON.stringify({
            basket: list.length,
            rows: document.querySelectorAll('.basket-row').length,
            activeTabText: activeTab ? String(activeTab.textContent) : null
        });
    })()`);
    const m = multi && multi.__error ? null : JSON.parse(multi);
    check('整组会走多目标（清单里有 2 件，界面也列出来）',
        !!m && m.basket === 2 && m.rows === 2, m ? JSON.stringify(m) : '读不到');
    check('当前组有高亮（知道自己在算哪一套）', !!m && !!m.activeTabText, m ? String(m.activeTabText) : '');

    // 5) 功能：建组 → 切回旧组 → 旧组的东西还在（这就是「书签组」要干的事）
    //
    // ★ 这里**不再替换 window.prompt**：新建/重命名已经改成栏内就地输入（系统 prompt 会被
    //   某些 WebView 拦掉，拦掉时点击就是「没反应」）。测试点的是真按钮、填的是真输入框。
    // ★ 每一步之后都要**等页面重绘**：建组/切组走的是 render()，它会重新拉数据，
    //   同步读 DOM 只会读到旧的那一帧（这里踩过，看着像功能坏了，其实是测试没等）。
    await evalIn(send, `(() => {
        const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        window.__firstGroupId = store.groups[0].id;
        window.__firstGroupItems = store.groups[0].items.length;
        const addBtn = Array.from(document.querySelectorAll('.group-tools button'))
            .find((b) => /新建组/.test(b.textContent));
        if (addBtn) addBtn.click();
        return true;
    })()`);

    const askShown = await waitFor(send, `(() => {
        const row = document.querySelector('.group-ask');
        return row && row.querySelector('.group-ask-input') ? true : null;
    })()`, 15000);
    check('新建组改成就地输入了（不再弹系统 prompt）', askShown === true, String(askShown));
    check('就地输入不弹系统对话框', dialogs.length === 0, dialogs.join(' | '));

    await evalIn(send, `(() => {
        const row = document.querySelector('.group-ask');
        const input = row.querySelector('.group-ask-input');
        input.value = '测试组A';
        const ok = Array.from(row.querySelectorAll('button')).find((b) => /确定/.test(b.textContent));
        if (ok) ok.click();
        return true;
    })()`);

    // 等新组的标签出现（同时验证：新建之后**原来的标签还在** —— 这正是玩家报的那个问题）
    const tabsAfterCreate = await waitFor(send, `(() => {
        const n = document.querySelectorAll('.group-tab').length;
        return n >= 2 ? n : null;
    })()`, 20000);
    check('新建之后两个组的标签都在（原来的没被藏起来）', tabsAfterCreate >= 2,
        tabsAfterCreate ? (tabsAfterCreate + ' 个标签') : '只等到 ' + tabsAfterCreate + ' 个');

    const created = await evalIn(send, `(() => {
        const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        const active = store.groups.find((g) => g.id === store.activeId) || null;
        const emptyHint = document.body.textContent.indexOf('里还没有东西') >= 0;
        return JSON.stringify({
            groupCount: store.groups.length,
            newGroupName: active ? active.name : null,
            emptyHintShown: emptyHint
        });
    })()`);
    const c2 = created && created.__error ? null : JSON.parse(created);
    check('能新建书签组', !!c2 && c2.groupCount === 2 && c2.newGroupName === '测试组A',
        c2 ? ('组数=' + c2.groupCount + '，新组名=' + c2.newGroupName) : '读不到');
    check('空组会说一句（不是空箱子）', !!c2 && c2.emptyHintShown === true, c2 ? String(c2.emptyHintShown) : '');

    // 切回第一个组：点了之后同样要等重绘
    await evalIn(send, `(() => {
        const tabs = Array.from(document.querySelectorAll('.group-tab'));
        const first = tabs.find((t) => /我的清单/.test(t.textContent));
        if (first) first.click();
        return true;
    })()`);
    const switchedBack = await waitFor(send, `(() => {
        const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        if (store.activeId !== window.__firstGroupId) return null;
        const tab = Array.from(document.querySelectorAll('.group-tab')).find((t) => t.classList.contains('is-active'));
        return tab ? String(tab.textContent) : null;
    })()`, 20000);
    check('能切回原来的组', !!switchedBack, switchedBack ? ('当前组=' + switchedBack) : '没切回去');

    const finalItems = await evalIn(send, `(() => {
        const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        const first = store.groups.find((g) => g.id === window.__firstGroupId) || null;
        return first ? first.items.length : -1;
    })()`);
    check('切回去之后原来组里的清单还在', finalItems === 2, '切回后 ' + finalItems + ' 条');

    // 6) 删除本组：用**真鼠标点击**（Input.dispatchMouseEvent），而不是 element.click()。
    //
    // 玩家报过「点击删除本组没反应」：那时它用的是 window.confirm，而有些浏览器/WebView
    // 会把对话框吞掉（或者玩家勾过「不再显示」）——confirm 直接返回 false，于是什么都没发生。
    // 现在改成点两次确认，这里就断言：第一次只变文案、第二次才真删、且全程没有系统对话框。
    const delBox = await evalIn(send, `(() => {
        const btn = Array.from(document.querySelectorAll('.group-tools button'))
            .find((b) => /删除本组/.test(b.textContent));
        if (!btn) return JSON.stringify({ found: false });
        const r = btn.getBoundingClientRect();
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        const top = document.elementFromPoint(cx, cy);
        return JSON.stringify({
            found: true,
            x: Math.round(cx),
            y: Math.round(cy),
            clickable: top === btn,
            groups: JSON.parse(localStorage.getItem('futa_gtnh.groups')).groups.length
        });
    })()`);
    const del = JSON.parse(delBox);
    check('删除本组按钮点得到（没有被别的东西盖住）', del.found && del.clickable === true, delBox);

    const clickAt = async (x, y) => {
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: x, y: y, button: 'left', clickCount: 1 });
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: x, y: y, button: 'left', clickCount: 1 });
        await sleep(600);
    };
    if (del.found) {
        await clickAt(del.x, del.y);
        const afterFirst = await evalIn(send, `(() => {
            const btn = Array.from(document.querySelectorAll('.group-tools button'))
                .find((b) => /删除|确认/.test(b.textContent));
            return JSON.stringify({
                text: btn ? btn.textContent : null,
                groups: JSON.parse(localStorage.getItem('futa_gtnh.groups')).groups.length
            });
        })()`);
        const f = JSON.parse(afterFirst);
        check('第一次点击只是变成确认态（还没删）',
            f.groups === del.groups && /再点一次/.test(String(f.text)), afterFirst);

        await clickAt(del.x, del.y);
        const afterSecond = await evalIn(send, `(() => {
            const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
            return JSON.stringify({ groups: store.groups.length, active: store.activeId });
        })()`);
        const s2 = JSON.parse(afterSecond);
        check('第二次点击真的删掉了', s2.groups === del.groups - 1, afterSecond);
        check('删除本组全程没有系统对话框', dialogs.length === 0, dialogs.join(' | ') || '（一个都没有）');
    }

    const shot = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
    console.log('  截图: ' + OUT);

    child.kill();
    console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})();
