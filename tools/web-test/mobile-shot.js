/*
 * 手机视口下看「替代品选择」有没有被挡住。
 *
 * 这个脚本不改仓库里的任何东西，只是把页面按 390x844 的手机视口打开、
 * 点开一个多候选格子，然后把「信息条高度 vs 屏幕高度」打出来并截一张图。
 * 数字比眼睛可靠：条高 > 屏高就一定看不到候选。
 */
const fs = require('fs');
const path = require('path');

const PORT = 9222;
const BASE = 'http://127.0.0.1:8765';
const OUT = process.argv[2] || path.join(process.env.TEMP, 'futa-shots', 'mobile-alt.png');
const VW = Number(process.argv[3] || 390);
const VH = Number(process.argv[4] || 844);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connect() {
    const res = await fetch('http://127.0.0.1:' + PORT + '/json/list');
    const page = (await res.json()).find((t) => t.type === 'page');
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

(async () => {
    // 先找一个「计划里有多个候选材料」的物品，否则点不出候选条
    const s = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent('藻类农场') + '&limit=3')).json();
    const item = s.items.find((i) => i.craftable) || s.items[0];
    console.log('用物品 #' + item.id + ' ' + item.name);

    const send = await connect();
    // 手机视口：这一步才真的改掉布局视口（无头模式的 --window-size 不可靠）
    await send('Emulation.setDeviceMetricsOverride', {
        width: VW,
        height: VH,
        deviceScaleFactor: 2,
        mobile: true
    });
    await send('Page.navigate', { url: BASE + '/#/plan/' + item.id + '?count=64' });
    await sleep(3500);

    // 挨个点多候选格子，挑「候选最多」的那个来看 —— 候选越多条越高，越容易顶出屏幕
    const probe = await send('Runtime.evaluate', {
        returnByValue: true,
        expression: `(() => {
            const cells = Array.from(document.querySelectorAll('.slot.is-clickable'));
            let best = null;
            for (const cell of cells) {
                cell.click();
                const bar = document.getElementById('item-info');
                if (!bar) continue;
                const chips = bar.querySelectorAll('.alt-chip');
                if (chips.length < 2) continue;
                const r = bar.getBoundingClientRect();
                const chipBox = chips[0].getBoundingClientRect();
                const info = {
                    chips: chips.length,
                    barHeight: Math.round(r.height),
                    barTop: Math.round(r.top),
                    viewportHeight: window.innerHeight,
                    viewportWidth: window.innerWidth,
                    scrollable: bar.scrollHeight > bar.clientHeight,
                    firstChipTop: Math.round(chipBox.top),
                    firstChipVisible: chipBox.top >= 0 && chipBox.bottom <= window.innerHeight
                };
                if (!best || info.chips > best.chips) best = info;
                if (best.chips >= 4) break;
            }
            return best || { chips: 0 };
        })()`
    });
    const v = probe.result && probe.result.result ? probe.result.result.value : null;
    console.log('探测结果: ' + JSON.stringify(v));

    // 真问题出在「弹层里点格子」：遮罩 z-index 60 原来压着信息条。这里把那一步也走一遍，
    // 然后用 elementFromPoint 打在胶囊中心 —— 被盖住时返回的是遮罩，不是胶囊本身。
    const covered = await send('Runtime.evaluate', {
        returnByValue: true,
        expression: `(() => {
            const bar = document.getElementById('item-info');
            if (!bar) return { skipped: '没有信息条' };
            const btn = Array.from(bar.querySelectorAll('button'))
                .find((b) => String(b.textContent).indexOf('看它的配方') >= 0);
            if (!btn) return { skipped: '没有「看它的配方」按钮' };
            btn.click();
            return { opened: true };
        })()`
    });
    await sleep(900);
    const verdict = await send('Runtime.evaluate', {
        returnByValue: true,
        expression: `(() => {
            const overlay = document.querySelector('.overlay');
            const bar = document.getElementById('item-info');
            const chips = bar ? Array.from(bar.querySelectorAll('.alt-chip')) : [];
            const probe = (el) => {
                if (!el) return null;
                const r = el.getBoundingClientRect();
                const hit = document.elementFromPoint(Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2));
                return { text: String(el.textContent).slice(0, 14), hit: hit ? (hit.className || hit.tagName) : null,
                         ok: !!hit && (el === hit || el.contains(hit)) };
            };
            return {
                overlayOpen: !!overlay,
                chips: chips.length,
                first: probe(chips[0]),
                last: probe(chips[chips.length - 1]),
                barZ: bar ? getComputedStyle(bar).zIndex : null,
                overlayZ: overlay ? getComputedStyle(overlay).zIndex : null
            };
        })()`
    });
    const w = verdict.result && verdict.result.result ? verdict.result.result.value : null;
    console.log('弹层里可点性: ' + JSON.stringify(w));

    const shot = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(path.dirname(OUT), { recursive: true });
    fs.writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
    console.log('截图: ' + OUT);
    process.exit(0);
})();
