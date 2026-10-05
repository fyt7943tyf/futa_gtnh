/*
 * 最小实验：候选选择的「读取」这一步到底通不通。
 *
 * 手工往本地存储塞一条记录，然后打开计划页，把那一格的 data-alt 诊断读回来。
 * 不经过任何点击流程 —— 这样能干净地区分「存没存进去」和「读没读出来」。
 */
const fs = require('fs');
const path = require('path');

const PORT = 9222;
const BASE = 'http://127.0.0.1:8765';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connect() {
    const res = await fetch('http://127.0.0.1:' + PORT + '/json/list');
    const page = (await res.json()).find((t) => t.type === 'page');
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

/** 轮询等待某个表达式返回真值（比猜一个固定秒数可靠）。 */
async function waitFor(send, expression, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const v = await evalIn(send, expression);
        if (v) return v;
        if (Date.now() > deadline) return null;
        await sleep(500);
    }
}
const evalIn = async (send, expression) => {
    const r = await send('Runtime.evaluate', { returnByValue: true, expression });
    const res = r.result || {};
    if (res.exceptionDetails) return { error: JSON.stringify(res.exceptionDetails).slice(0, 200) };
    return res.result ? res.result.value : null;
};

(async () => {
    const s = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent('藻类农场') + '&limit=2')).json();
    const item = s.items.find((i) => i.craftable) || s.items[0];
    const send = await connect();

    // 1) 先打开计划页，找出一个真正的多候选格子，读出它的 owner/x/y
    // ★ 必须先真正重载一次：只改 hash 导航不会重新加载文档，
    // 页面可能还跑着改动之前的 app.js —— 这个坑在排查里反复出现（量到的全是旧行为）。
    await send('Page.navigate', { url: 'about:blank' });
    await send('Page.navigate', { url: BASE + '/#/plan/' + item.id + '?count=64' });
    await send('Page.reload', { ignoreCache: true });
    const found = await waitFor(send, `(() => {
        const cells = Array.from(document.querySelectorAll('.step-grid .slot.is-clickable'));
        for (const cell of cells) {
            const raw = cell.getAttribute('data-alt');
            if (!raw) continue;
            const d = JSON.parse(raw);
            if (d.alts > 1) {
                return JSON.stringify({ owner: d.owner, x: d.x, y: d.y, alts: d.alts, shown: d.shown });
            }
        }
        return null;
    })()`, 30000);
    console.log('1) 找到多候选格子: ' + found);
    if (!found) {
        const state = await evalIn(send, `(() => JSON.stringify({
            url: location.href,
            body: String(document.body.textContent || '').slice(0, 80),
            slots: document.querySelectorAll('.slot').length,
            withDiag: document.querySelectorAll('[data-alt]').length,
            grids: document.querySelectorAll('.step-grid').length
        }))()`);
        console.log('   页面状态: ' + state);
    }
    if (!found) { console.log('没有多候选格子，退出'); process.exit(0); }
    const slot = JSON.parse(found);

    // 2) 手工塞一条「用第二个候选」的记录（第二个候选的 id 从页面上拿不到，就用后端问）
    const item2 = await (await fetch(BASE + '/api/item?id=' + slot.owner)).json();
    let altIds = null;
    for (const r of item2.recipes || []) {
        const f = (r.inputs || []).find((i) => i.x === slot.x && i.y === slot.y && i.alts && i.alts.length > 1);
        if (f) { altIds = f.alts.map((a) => a.id); break; }
    }
    console.log('   该格候选: ' + JSON.stringify(altIds));
    if (!altIds || altIds.length < 2) { console.log('拿不到候选列表，退出'); process.exit(0); }
    const want = altIds[1];
    const key = slot.owner + ':' + slot.x + ':' + slot.y;

    await evalIn(send, `(() => {
        localStorage.setItem('futa_gtnh.alts', JSON.stringify({ ${JSON.stringify(key)}: ${want} }));
        localStorage.removeItem('futa_gtnh.choices');
        return true;
    })()`);
    console.log('2) 已写入本地记录: ' + key + ' -> ' + want);

    // 3) 重新加载并读诊断
    await send('Page.reload', { ignoreCache: true });
    const after = await waitFor(send, `(() => {
        const stored = localStorage.getItem('futa_gtnh.alts');
        const cells = Array.from(document.querySelectorAll('.step-grid .slot.is-clickable'));
        const hits = [];
        for (const cell of cells) {
            const raw = cell.getAttribute('data-alt');
            if (!raw) continue;
            const d = JSON.parse(raw);
            if (String(d.owner) === ${JSON.stringify(String(slot.owner))} && d.x === ${Number(slot.x)} && d.y === ${Number(slot.y)}) {
                const img = cell.querySelector('img');
                hits.push({ raw: raw, src: img ? img.getAttribute('src') : null });
            }
        }
        return hits.length ? JSON.stringify({ stored: stored, hits: hits.slice(0, 3) }) : null;
    })()`, 30000);
    console.log('3) 重新加载后: ' + after);

    // 4) 同一时刻把「存的原始值 / 算出来的键 / 读到的选择」并排打出来
    const cmp = await evalIn(send, `(() => {
        const stored = localStorage.getItem('futa_gtnh.alts');
        const parsed = stored ? JSON.parse(stored) : {};
        const wantKey = ${JSON.stringify(key)};
        const cells = Array.from(document.querySelectorAll('.step-grid .slot.is-clickable'));
        let diag = null;
        for (const cell of cells) {
            const raw = cell.getAttribute('data-alt');
            if (!raw) continue;
            const d = JSON.parse(raw);
            if (String(d.owner) === ${JSON.stringify(String(slot.owner))} && Number(d.x) === ${Number(slot.x)} && Number(d.y) === ${Number(slot.y)}) {
                diag = raw; break;
            }
        }
        return JSON.stringify({
            存的原始值: stored,
            键存在吗: Object.prototype.hasOwnProperty.call(parsed, wantKey),
            该格诊断: diag,
            localStorage里的所有键: Object.keys(localStorage)
        });
    })()`);
    console.log('5) 并排对照: ' + cmp);

    // 4) 顺带把后端的规划结果也对照一下（它认得这条记录吗）
    const plan = await (await fetch(BASE + '/api/plan?id=' + item.id + '&count=64&alts=' + key + ':' + want)).json();
    const hit = (plan.steps || []).flatMap((st) => st.inputs || []).find((i) => String(i.id) === String(want));
    console.log('4) 后端按这条记录规划: ' + (hit ? ('用上了 ' + hit.name + ' ×' + hit.need) : '没找到这个候选'));
    process.exit(0);
})();
