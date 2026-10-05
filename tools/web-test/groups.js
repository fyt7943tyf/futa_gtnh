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

const PORT = 9222;
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const OUT = process.argv[2] || path.join(process.env.TEMP, 'futa-shots', 'groups-compat.png');

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
    // prompt/confirm 在无头浏览器里会阻塞，先换成返回值。
    // ★ 每一步之后都要**等页面重绘**：建组/切组走的是 render()，它会重新拉数据，
    //   同步读 DOM 只会读到旧的那一帧（这里踩过，看着像功能坏了，其实是测试没等）。
    await evalIn(send, `(() => {
        window.prompt = function () { return '测试组A'; };
        window.confirm = function () { return true; };
        const store = JSON.parse(localStorage.getItem('futa_gtnh.groups'));
        window.__firstGroupId = store.groups[0].id;
        window.__firstGroupItems = store.groups[0].items.length;
        const addBtn = Array.from(document.querySelectorAll('.group-tools button'))
            .find((b) => /新建组/.test(b.textContent));
        if (addBtn) addBtn.click();
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

    const shot = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
    console.log('  截图: ' + OUT);

    console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})();
