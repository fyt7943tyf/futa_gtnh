/*
 * 页面交互自测：用 CDP 驱动无头 Edge，真的去点按钮，而不是只看静态截图。
 *
 * 需要游戏客户端在跑（网页服务在 8765）。
 *   node tools/web-test/ui.js [--base http://127.0.0.1:8765] [--shot 输出目录]
 *
 * 为什么要这么测：静态截图证明不了「点了会怎样」。这里验的是行为 ——
 * 选了配方之后步骤卡片上有没有标识、点图标能不能打开配方弹层。
 */
'use strict';

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const BASE = argValue('--base') || 'http://127.0.0.1:8765';
const SHOT_DIR = argValue('--shot') || path.join(os.tmpdir(), 'futa-ui-shots');
const PORT = 9333;
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

/** 一个极简的 CDP 客户端：够用就好，不引依赖。 */
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

    /** 在页面里跑一段表达式，返回它的值。 */
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

async function waitFor(session, expression, timeoutMs, label) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const value = await session.eval(expression);
        if (value) return value;
        if (Date.now() > deadline) throw new Error('等待超时: ' + label);
        await sleep(200);
    }
}

/**
 * 按名字查出物品 id。
 *
 * <p>
 * <b>不要在测试里写死物品 id</b>：id 是「这个会话里第几个被登记的物品」，
 * 而登记顺序取决于 NEI 注册了多少个配方处理器（在标题界面是 226 个、进过世界之后是 366 个，
 * 物品目录 31268 → 64932），换一次状态 id 就整体重排 —— 实测「藻类农场」从 18919
 * 变成了 35975，写死 id 的断言会指向完全不同的物品。
 *
 * @param name 显示名（可以只是其中一段）
 * @param pick 同名多个时挑哪个，默认挑第一个「可合成」的
 */
async function resolveId(name, pick) {
    // 物品名在中英文之间飘（GTNH 的中文语言文件覆盖不全，同一个物品这次叫「铁锭」、
    // 下次叫 Iron Ingot），所以允许传多个候选名，逐个试
    const names = Array.isArray(name) ? name : [name];
    for (const candidate of names) {
        const res = await fetch(BASE + '/api/search?q=' + encodeURIComponent(candidate) + '&limit=20');
        const data = await res.json();
        const items = data.items || [];
        if (items.length === 0) continue;
        if (pick) {
            const chosen = items.find(pick);
            if (chosen) return chosen.id;
            continue;
        }
        const chosen = items.find((i) => i.craftable) || items[0];
        if (chosen) return chosen.id;
    }
    throw new Error('找不到物品：' + names.join(' / '));
}

function launchEdge() {
    const exe = EDGE_CANDIDATES.find((p) => fs.existsSync(p));
    if (!exe) throw new Error('找不到 msedge.exe');
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'futa-ui-profile-'));
    const child = spawn(exe, [
        '--headless=new',
        '--disable-gpu',
        '--hide-scrollbars',
        '--no-first-run',
        '--remote-debugging-port=' + PORT,
        '--user-data-dir=' + profile,
        '--window-size=420,1400',
        'about:blank'
    ], { stdio: 'ignore' });
    return child;
}

async function main() {
    const status = await (await fetch(BASE + '/api/status')).json();
    if (!status.indexed) {
        console.error('索引还没就绪：' + status.phase);
        process.exit(2);
    }
    console.log('网页: ' + status.phase + ' / ' + status.recipes + ' 条配方');

    // 目标物品和它的组装机配方都**当场解析**，不写死：
    // 物品 id 随目录重排，配方号（rid）随索引重排 ——
    // 写死的话换一次索引状态就会指向完全不同的东西（踩过：藻类农场 18919 → 35975）
    const TARGET = await resolveId('藻类农场');
    const targetItem = await (await fetch(BASE + '/api/item?id=' + TARGET)).json();
    const assembler = (targetItem.recipes || []).find((r) => /assembler/i.test(String(r.handler)));
    if (!assembler) throw new Error('藻类农场没有组装机配方，测不了');
    const ASSEMBLER = String(assembler.rid);
    // 铝杆要**精确**匹配名字：模糊搜索里 "Long Aluminium Rod" 排在 "Aluminium Rod" 前面，
    // 只挑「第一个可合成的」会拿到长杆，而配方槽里用的是普通杆 —— 那样后面
    // 「标成原材料」的断言会静默失效（标记了一个计划里根本不存在的物品）
    const ROD = await resolveId(['Aluminium Rod', '铝杆'], (i) => /^(Aluminium Rod|铝杆)$/.test(i.name));
    console.log('目标 id=' + TARGET + '，组装机 rid=' + ASSEMBLER + '，铝杆 id=' + ROD);

    // 图标必须是**离屏渲染**出来的，不能是「读贴图」那条退路。
    // 退路是静默的：图标照样显示，只是变回平面贴图 —— 实测离屏渲染每次都抛异常
    //（LWJGL3 的 BufferUtils 给的是直接缓冲，array() 直接抛）时，
    // 光看图片根本发现不了。所以接口会带 X-Icon-Source 头，这里断言它是 render。
    const iconRes = await fetch(BASE + '/api/icon/' + TARGET + '.png');
    const iconSource = iconRes.headers.get('x-icon-source') || '';
    await iconRes.arrayBuffer();
    // 第一张可能是「还没轮到渲染」的超时退路，再要一次（渲染有缓存，第二次必中）
    const iconRes2 = await fetch(BASE + '/api/icon/' + TARGET + '.png');
    const iconSource2 = iconRes2.headers.get('x-icon-source') || '';
    await iconRes2.arrayBuffer();
    console.log('图标来源: 第一次=' + (iconSource || '?') + ' 第二次=' + (iconSource2 || '?'));

    const edge = launchEdge();
    let session;
    try {
        for (let i = 0; i < 40 && !session; i++) {
            await sleep(250);
            try {
                session = await connect();
            } catch (e) {
                /* 还没起来 */
            }
        }
        if (!session) throw new Error('连不上无头浏览器');

        // 从干净状态开始，免得上一次的 choices 影响判断
        await session.goto(BASE + '/');
        // 「忽略库存」是必须的：默认算法会扣掉玩家手头已有的东西，
        // 于是「计划里有没有这一步」取决于背包里有什么 —— 那种测试玩家一玩就假失败。
        // 打开这个开关（页面上也有这个按钮），计划就是确定的。
        await session.eval("localStorage.clear(); localStorage.setItem('futa_gtnh.ignorestock','1');");

        console.log('== 物品页：选中组装机那条配方 ==');
        await session.goto(BASE + '/#/item/' + TARGET);
        await waitFor(session, "document.querySelectorAll('.recipe').length > 0", 15000, '配方卡片');

        const picked = await session.eval(`(() => {
            // 机器名现在在「组标题」上（同类型配方合并成一张卡片了），
            // 卡片内部不再重复写机器名
            const groups = Array.from(document.querySelectorAll('.recipe-group'));
            for (const group of groups) {
                const title = String((group.querySelector('.group-title') || {}).textContent || '');
                if (/Assembler/i.test(title)) {
                    const b = Array.from(group.querySelectorAll('button'))
                        .find(x => x.textContent.trim() === '选它');
                    if (b) {
                        b.click();
                        return title.trim();
                    }
                }
            }
            return '';
        })()`);
        check('物品页能选中组装机配方', !!picked, picked);

        const stored = await session.eval("localStorage.getItem('futa_gtnh.choices') || ''");
        check('选择写进了 localStorage', new RegExp(String(TARGET)).test(stored), stored);

        console.log('== 物品页：配方槽位的提示与点击 ==');
        check('图标是离屏渲染出来的（不是退回读贴图）',
            iconSource2 === 'render' || iconSource === 'render',
            '第一次=' + (iconSource || '无') + ' 第二次=' + (iconSource2 || '无'));
        // 这条是「只有数量为 1 的那个槽有提示」那个 bug 的回归断言：
        // 提示必须挂在格子上，挂在数量角标上指针根本停不上去
        const slots = await session.eval(`(() => {
            // 只看有东西的格子：空格子本来就没有物品，不该有提示也不该能点
            const all = Array.from(document.querySelectorAll('.recipe .slot:not(.is-empty)'));
            return {
                total: all.length,
                withTitle: all.filter(s => String(s.title || '').trim().length > 0).length,
                withCount: all.filter(s => /×\\d/.test(String(s.title || ''))).length,
                clickable: all.filter(s => s.classList.contains('is-clickable')).length,
                chosen: document.querySelectorAll('.recipe .slot-chosen').length,
                badgeTitles: document.querySelectorAll('.recipe .slot-count[title]').length
            };
        })()`);
        check('每个配方槽位都有提示', slots.total > 0 && slots.withTitle === slots.total,
            slots.withTitle + '/' + slots.total);
        check('提示里带上了数量', slots.withCount > 0, slots.withCount + ' 个');
        check('提示没有挂在数量角标上', slots.badgeTitles === 0, slots.badgeTitles + ' 个');
        check('每个槽位都可以点', slots.clickable === slots.total,
            slots.clickable + '/' + slots.total);
        check('指定过配方的物品在网格里有勾', slots.chosen >= 1, slots.chosen + ' 个');

        // 把鼠标停到一个材料槽上再截图：能同时看到悬停高亮和网格里的勾
        const slotBox = await session.eval(`(() => {
            const s = document.querySelector('.recipe .slot.is-clickable');
            if (!s) return null;
            s.scrollIntoView({ block: 'center' });
            const r = s.getBoundingClientRect();
            return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
        })()`);
        if (slotBox) {
            await session.send('Input.dispatchMouseEvent',
                { type: 'mouseMoved', x: slotBox.x, y: slotBox.y, buttons: 0 });
            await sleep(200);
        }
        const hoveredSlot = await session.eval(
            "Array.from(document.querySelectorAll(':hover')).some(e => e.classList && e.classList.contains('slot'))");
        check('鼠标停在槽位上会命中它（悬停高亮生效）', hoveredSlot === true);
        console.log('  截图: ' + await session.shot('item-slot-hover'));

        const slotClicked = await session.eval(`(() => {
            const s = document.querySelector('.recipe .slot.is-clickable');
            if (!s) return 'none';
            window.__slotName = String(s.getAttribute('title') || s.getAttribute('data-tip') || '').split('\\n')[0];
            s.click();
            return window.__slotName;
        })()`);
        check('材料槽可点击', !!slotClicked && slotClicked !== 'none', slotClicked);
        // 手机上点格子的第一反应是「这是什么」，不是「给我换配方」：
        // 所以第一次点只弹信息条，要换配方在信息条上再点一步
        const slotInfo = await waitFor(session,
            "(() => { const b = document.getElementById('item-info'); return !!(b && !b.hidden); })()",
            8000, '物品信息条').catch(() => '');
        check('点材料槽弹出物品信息条', !!slotInfo);
        const slotInfoMatch = await session.eval(
            "String(document.getElementById('item-info').textContent).indexOf(String(window.__slotName).split(' ×')[0]) === 0");
        check('信息条说的正是被点的那个物品', slotInfoMatch === true, String(slotClicked));
        const slotModal = await session.eval(
            "(() => { const o = document.getElementById('overlay'); return !!(o && !o.hidden); })()");
        check('第一步不会直接跳进配方弹层', slotModal === false);
        await session.eval("document.getElementById('item-info').hidden = true");

        console.log('== 计划页：施工进度与可并行步骤 ==');
        await session.goto(BASE + '/#/plan/' + TARGET + '?count=64');
        await waitFor(session, "document.querySelectorAll('.step').length > 0", 20000, '步骤卡片');
        const ready0 = await session.eval(`(() => ({
            ready: document.querySelectorAll('.ready-item').length,
            unchosen: document.querySelectorAll('.unchosen-item').length,
            steps: document.querySelectorAll('.step').length,
            boxes: document.querySelectorAll('.step .done-box').length,
            progress: (document.querySelector('.summary') || {}).textContent || ''
        }))()`);
        check('步骤卡片上都有完成勾选框', ready0.boxes === ready0.steps,
            ready0.boxes + '/' + ready0.steps);
        check('列出了「还没指定配方」的步骤', ready0.unchosen > 0,
            ready0.unchosen + ' 条 / 共 ' + ready0.steps + ' 步');
        check('一开始就有可开工的步骤', ready0.ready > 0, ready0.ready + ' 步');
        check('概览里有进度', /已完成 \d+\/\d+/.test(ready0.progress),
            (ready0.progress.match(/已完成 \d+\/\d+/) || [''])[0]);

        // 依赖关系必须自洽：needs 指向的步骤号一定比它自己小（拓扑序在前）
        //
        // 注意要把 choices 一起带上：不带的话规划会自己挑一条简单配方
        // （藻类农场的无序合成只要 1 步），这份计划就没有前后依赖可验了
        const choiceParam = await session.eval(`(() => {
            const all = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            return Object.keys(all).map(k => k + ':' + all[k]).join(',');
        })()`);
        const planJson = await (await fetch(
            BASE + '/api/plan?id=' + TARGET + '&count=64&choices=' + encodeURIComponent(choiceParam))).json();
        let orderOk = true, needsOk = true, levelOk = true;
        const byNo = new Map();
        (planJson.steps || []).forEach((s) => byNo.set(s.n, s));
        for (const s of planJson.steps || []) {
            if (!Number.isInteger(s.n) || s.n < 1) orderOk = false;
            for (const need of s.needs || []) {
                const dep = byNo.get(need);
                if (!dep || need >= s.n) needsOk = false;
                if (dep && !(dep.level < s.level)) levelOk = false;
            }
            if (!Number.isInteger(s.level) || s.level < 1) levelOk = false;
        }
        check('步骤号从 1 连续编排', orderOk);
        check('依赖只指向更前面的步骤（顺序自洽）', needsOk);
        check('批次号随依赖递增', levelOk);

        // 关键行为：勾掉一步之后，等它的那一步应该现身
        // 关键行为：把当前这一批**整批**勾完，下一批必然露出来。
        // （不能只勾一步就要求放行：被依赖的那步可能还等着同批的其它步骤，
        //  那个断言会假失败 —— 踩过）
        const readyBefore = await session.eval(
            "Array.from(document.querySelectorAll('.ready-item')).map(e => Number(e.getAttribute('data-step')))");
        check('一开始的可做列表非空', readyBefore.length > 0, readyBefore.map((x) => '#' + x).join('、'));

        // 每勾一次整页都会重画，旧的 checkbox 会失效，所以每次重新查
        let firstTicked = null;
        for (let i = 0; i < readyBefore.length; i++) {
            const ticked = await session.eval(`(() => {
                const box = document.querySelector('.ready-item .done-box');
                if (!box) return null;
                const step = Number(box.closest('.ready-item').getAttribute('data-step'));
                box.click();
                return step;
            })()`);
            if (firstTicked === null) firstTicked = ticked;
            await sleep(150);
        }
        const release = { before: readyBefore, after: await session.eval(
            "Array.from(document.querySelectorAll('.ready-item')).map(e => Number(e.getAttribute('data-step')))") };
        const appeared = release.after.filter((x) => release.before.indexOf(x) < 0);
        check('整批做完后出现了新的可做步骤', appeared.length > 0,
            appeared.map((x) => '#' + x).join('、') || '（没有新步骤）');
        check('勾掉的步骤从可做列表里消失', release.after.indexOf(firstTicked) < 0,
            '勾掉 #' + firstTicked + '，之后列表=' + release.after.map((x) => '#' + x).join('、'));
        console.log('  截图: ' + await session.shot('plan-progress'));

        // 进度要能存下来：重新加载后仍然记得
        const progress = await session.eval("localStorage.getItem('futa_gtnh.done') || ''");
        check('进度写进了 localStorage', new RegExp(TARGET + "@64").test(progress), progress.slice(0, 80));
        await session.send('Page.reload', { ignoreCache: true });
        await waitFor(session, "document.querySelectorAll('.step').length > 0", 20000, '重载后的步骤');
        const kept = await session.eval(
            "document.querySelectorAll('.step.is-done').length + '/' + document.querySelectorAll('.step .done-box:checked').length");
        check('刷新后进度还在', kept !== '0/0', kept);

        // 清零：进度和可做列表都要回到初始状态
        const cleared = await session.eval(`(() => {
            const b = Array.from(document.querySelectorAll('button'))
                .find(x => x.textContent.trim() === '清零进度');
            if (!b) return 'no-button';
            b.click();
            return 'clicked';
        })()`);
        check('有清零进度按钮', cleared === 'clicked', cleared);
        await sleep(300);
        const afterClear = await session.eval(
            "document.querySelectorAll('.step.is-done').length + '/' + (localStorage.getItem('futa_gtnh.done') || '{}')");
        check('清零后进度归零', afterClear.indexOf('0/') === 0, afterClear.slice(0, 60));

        console.log('== 冲突状态：「选了配方」+「当作原始材料」==');
        // 这两个状态语义上是打架的（raw = 别展开，choice = 按这条做），
        // 同时存在时 raw 赢。玩家看到的是「我明明选了配方」，所以页面必须自己说清楚，
        // 并且给一条改回来的路。
        //
        // 状态直接写 localStorage：这样测的是「页面怎么解释这个状态」，
        // 而不是「怎么把状态点出来」（那是另一回事，下面单独验）
        await session.eval(`(() => {
            const choices = {};
            choices['${TARGET}'] = '${ASSEMBLER}';
            choices['${ROD}'] = '1';
            localStorage.setItem('futa_gtnh.choices', JSON.stringify(choices));
            localStorage.setItem('futa_gtnh.raw', JSON.stringify([${ROD}]));
            return true;
        })()`);

        // 后端这半边**必须带 stock=0**：材料表取决于玩家当前库存
        //（背包里有就不用做了，那一项根本不会出现在材料表里）。
        // 之前这里没带，测试随存档漂移，玩家一玩就假失败 —— 踩过。
        const api = await (await fetch(
            BASE + '/api/plan?id=' + TARGET + '&count=64&stock=0&' + 'choices=' + TARGET + ':' + ASSEMBLER + ',' + ROD + ':1&raw=' + ROD)).json();
        const rodAsMaterial = (api.materials || []).find((m) => m.id === ROD);
        const rodAsStep = (api.steps || []).find((s) => s.itemId === ROD);
        check('标成原始材料后它进了「需要准备」、不再是步骤',
            !!rodAsMaterial && !rodAsStep,
            '材料=' + (rodAsMaterial ? rodAsMaterial.reason : '否') + ' 步骤=' + (rodAsStep ? '#' + rodAsStep.n : '否'));
        check('后端给出了「为什么按原材料算」',
            !!rodAsMaterial && rodAsMaterial.reason === 'raw',
            rodAsMaterial ? rodAsMaterial.reason : 'undefined');

        // 页面这半边用**页面上真实出现的第一个材料行**来测，不写死物品：
        // 哪个东西是材料取决于库存，写死会随存档漂移
        await session.goto(BASE + '/#/plan/' + TARGET + '?count=64');
        await waitFor(session, "document.querySelectorAll('.material').length > 0", 20000, '需要准备');
        const rowPick = await session.eval(`(() => {
            const row = document.querySelector('.material');
            if (!row) return null;
            const img = row.querySelector('img');
            const m = String((img && img.getAttribute('src')) || '').match(/\\/(\\d+)\\.png/);
            return {
                id: m ? m[1] : '',
                name: String((row.querySelector('.material-name') || {}).textContent || '')
            };
        })()`);
        check('页面上至少有一个材料行可测', !!(rowPick && rowPick.id),
            rowPick ? rowPick.name + '#' + rowPick.id : '无');

        // 给它同时挂上「指定配方」和「当作原始材料」，制造出冲突状态
        await session.eval(`(() => {
            const id = ${JSON.stringify(rowPick.id)};
            const raw = JSON.parse(localStorage.getItem('futa_gtnh.raw') || '[]').map(String);
            if (raw.indexOf(id) < 0) raw.push(id);
            localStorage.setItem('futa_gtnh.raw', JSON.stringify(raw));
            const all = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            all[id] = '1';
            localStorage.setItem('futa_gtnh.choices', JSON.stringify(all));
            return true;
        })()`);
        await session.send('Page.reload', { ignoreCache: true });
        await sleep(500);
        await waitFor(session, "document.querySelectorAll('.material').length > 0", 20000, '需要准备');

        const explained = await session.eval(`(() => {
            const row = Array.from(document.querySelectorAll('.material'))
                .find(r => !!r.querySelector('img[src*="/${rowPick.id}.png"]'));
            if (!row) return { found: false };
            return {
                found: true,
                name: (row.querySelector('.material-name') || {}).textContent || '',
                note: !!row.querySelector('.material-note'),
                noteText: (row.querySelector('.material-note') || {}).textContent || '',
                why: (row.querySelector('.material-why') || {}).textContent || '',
                escape: Array.from(row.querySelectorAll('button'))
                    .some(b => /改回按配方合成/.test(b.textContent))
            };
        })()`);
        check('同一行既指定了配方又被勾成原始材料时，它还在材料表里', explained.found === true,
            String(explained.name));
        check('页面明说了这是什么情况', explained.note === true,
            String(explained.noteText).slice(0, 40));
        check('给了「改回按配方合成」的出口', explained.escape === true);
        check('每行都说明了「为什么按原材料算」', /为什么按原材料算/.test(String(explained.why)),
            String(explained.why).slice(0, 50));

        // 「任意一种都行」的槽位：候选要列出来，库存要按整组算
        const altInfo = await session.eval(`(() => {
            const notes = Array.from(document.querySelectorAll('.alts-note'));
            const rows = Array.from(document.querySelectorAll('.mat-line'))
                .filter(l => l.querySelector('.alts-note'));
            return {
                count: notes.length,
                first: notes.length ? String(notes[0].textContent || '').slice(0, 50) : '',
                rows: rows.length
            };
        })()`);
        const altApi = await (await fetch(BASE + '/api/plan?id=' + TARGET + '&count=64&stock=0')).json();
        const altIngredients = (altApi.steps || []).flatMap((s) => s.inputs || [])
            .filter((i) => i.alts && i.alts.length > 1);
        if (altIngredients.length === 0) {
            console.log('  （这份计划里没有多候选材料，跳过界面断言）');
        } else {
            check('多候选材料在界面上列出了「也可以用」', altInfo.count > 0,
                altInfo.count + ' 处：' + altInfo.first);
        }
        console.log('  → 后端多候选材料 ' + altIngredients.length + ' 行（含候选列表）');
        console.log('  截图: ' + await session.shot('plan-raw-conflict'));

        const fixed = await session.eval(`(() => {
            const row = Array.from(document.querySelectorAll('.material'))
                .find(r => !!r.querySelector('img[src*="/${rowPick.id}.png"]'));
            if (!row) return 'no-row';
            const b = Array.from(row.querySelectorAll('button'))
                .find(x => /改回按配方合成/.test(x.textContent));
            if (!b) return 'no-button';
            b.click();
            return 'clicked';
        })()`);
        check('能一键改回', fixed === 'clicked', String(fixed));
        await sleep(1200);
        const after = await session.eval(`(() => {
            const raw = JSON.parse(localStorage.getItem('futa_gtnh.raw') || '[]').map(String);
            return { stillRaw: raw.indexOf(${JSON.stringify(rowPick.id)}) >= 0, raw: raw };
        })()`);
        check('改回之后 raw 标记没了', after.stillRaw === false, JSON.stringify(after.raw));

        // 反向规则：主动给某个物品选配方 = 「我要做它」，应当自动摘掉 raw
        const reverseId = rowPick.id;
        await session.eval(`(() => {
            localStorage.setItem('futa_gtnh.raw', JSON.stringify([${JSON.stringify(reverseId)}]));
            const all = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            delete all[${JSON.stringify(reverseId)}];
            localStorage.setItem('futa_gtnh.choices', JSON.stringify(all));
            return true;
        })()`);
        await session.goto(BASE + '/#/item/' + reverseId);
        await waitFor(session, "document.querySelectorAll('.recipe').length > 0", 15000, '该物品的配方');
        await session.eval(`(() => {
            const b = Array.from(document.querySelectorAll('button')).find(x => x.textContent.trim() === '选它');
            if (b) b.click();
        })()`);
        await sleep(400);
        const auto = await session.eval(`(() => {
            const raw = JSON.parse(localStorage.getItem('futa_gtnh.raw') || '[]').map(String);
            const all = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            return { stillRaw: raw.indexOf(${JSON.stringify(reverseId)}) >= 0, chosen: !!all[${JSON.stringify(reverseId)}] };
        })()`);
        check('选配方会自动取消「当作原始材料」',
            auto.chosen === true && auto.stillRaw === false, JSON.stringify(auto));

        // 别把这份状态留给后面的段落
        await session.eval(`(() => {
            const all = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            delete all[${JSON.stringify(reverseId)}];
            localStorage.setItem('futa_gtnh.choices', JSON.stringify(all));
            localStorage.setItem('futa_gtnh.raw', '[]');
            return true;
        })()`);

        console.log('== 非消耗品不进合成链 ==');
        // 这一段的断言也只依赖「配方关系」，不依赖库存：
        // 非消耗品按一份算、不出现在步骤里，这两条和背包里有什么无关。
        // 唯一和库存有关的是「一次性工具需要几百个」——那条改成看候选池里有没有它。
        const planCat = await (await fetch(
            BASE + '/api/plan?id=' + TARGET + '&count=64&stock=0&' + 'choices=' + TARGET + ':' + ASSEMBLER + '&consumable=&catalyst=')).json();
        const catMats = (planCat.materials || []).filter((m) => m.reason === 'catalyst');
        check('后端把电路/模具这类标成非消耗品', catMats.length > 0,
            catMats.map((m) => m.name + '×' + m.need).slice(0, 4).join(', '));
        check('非消耗品只按一份算（不再乘合成次数）',
            catMats.length > 0 && catMats.every((m) => m.need < 100),
            catMats.map((m) => m.need).join(','));
        const catalystIds = new Set(catMats.map((m) => m.id));
        const catSteps = (planCat.steps || []).filter((s) => catalystIds.has(s.itemId));
        check('非消耗品不出现在合成步骤里', catSteps.length === 0,
            catSteps.map((s) => s.output.name).join(',') || '没有');
        // 反例必须保住：一次性工具是设计上要消耗的，不能被一起误伤
        const disposable = (planCat.materials || []).filter((m) => /一次性/.test(m.name));
        check('一次性工具仍然按消耗算', disposable.length > 0 && disposable.some((m) => m.need > 100),
            disposable.map((m) => m.name + '×' + m.need).slice(0, 3).join(', '));

        // 上一段结束时停在物品页，这里要真的回到计划页（不同 URL 才会触发路由渲染）
        await session.goto(BASE + '/#/plan/' + TARGET + '?count=64');
        await waitFor(session, "document.querySelectorAll('.material').length > 0", 20000, '需要准备');
        const catDom = await session.eval(`(() => {
            const rows = Array.from(document.querySelectorAll('.material')).filter(r =>
                /非消耗品/.test(String((r.querySelector('.material-why') || {}).textContent || '')));
            const first = rows[0];
            return {
                rows: rows.length,
                explain: first ? String(first.querySelector('.material-why').textContent) : '',
                need: first ? String((first.querySelector('.material-stat') || {}).textContent || '') : '',
                override: !!first && Array.from(first.querySelectorAll('button'))
                    .some(b => /这个其实会消耗/.test(b.textContent))
            };
        })()`);
        check('页面把非消耗品单独解释了', catDom.rows > 0 && /非消耗品/.test(catDom.explain),
            String(catDom.explain).slice(0, 40));
        check('页面上写的是「一份」而不是几百份', /需要 1\b|需要 1 /.test(catDom.need) || /需要 [1-9] /.test(catDom.need),
            String(catDom.need).replace(/\s+/g, ' ').slice(0, 44));
        check('给了「其实会消耗」的推翻入口', catDom.override === true);

        const overridden = await session.eval(`(() => {
            const row = Array.from(document.querySelectorAll('.material')).find(r =>
                /非消耗品/.test(String((r.querySelector('.material-why') || {}).textContent || '')));
            if (!row) return 'no-row';
            const img = row.querySelector('img');
            window.__catId = (String((img || {}).getAttribute('src') || '').match(/\\/(\\d+)\\.png/) || [])[1] || '';
            const b = Array.from(row.querySelectorAll('button')).find(x => /这个其实会消耗/.test(x.textContent));
            b.click();
            return window.__catId;
        })()`);
        await sleep(1200);
        const afterOverride = await session.eval(`(() => {
            const list = JSON.parse(localStorage.getItem('futa_gtnh.consumable') || '[]').map(String);
            return { id: String(window.__catId), recorded: list.indexOf(String(window.__catId)) >= 0 };
        })()`);
        check('推翻之后记进了「按消耗算」名单',
            overridden !== 'no-row' && afterOverride.recorded === true,
            JSON.stringify(afterOverride));
        // 收尾：别把推翻状态留给后面的段落
        await session.eval("localStorage.setItem('futa_gtnh.consumable','[]'); localStorage.setItem('futa_gtnh.catalyst','[]');");

        console.log('== 同类型配方合并 + 点格子看是什么 ==');
        // 找一个「同一种做法下不止一条配方」的物品。
        // 铁锭是最典型的：几十条熔炉配方（不同矿石烧出来都是铁锭）——
        // 中英文名都试一遍，因为这客户端的物品名中英混着来
        const candidates = ['Iron Ingot', '铁锭', 'Gold Ingot', '金锭'];
        let multiId = 0;
        let multiTitle = '';
        for (const name of candidates) {
            const found = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent(name) + '&limit=5'))
                .json();
            for (const it of (found.items || [])) {
                const d = await (await fetch(BASE + '/api/item?id=' + it.id)).json();
                const byTag = {};
                for (const r of (d.recipes || [])) {
                    const tag = String(r.handler || r.machine || '?');
                    byTag[tag] = (byTag[tag] || 0) + 1;
                }
                const hit = Object.keys(byTag).find((k) => byTag[k] > 1);
                if (hit) {
                    multiId = it.id;
                    multiTitle = it.name + '（' + hit.split('|').pop() + ' ×' + byTag[hit] + '）';
                    break;
                }
            }
            if (multiId) break;
        }
        if (!multiId) {
            check('找得到「同类型不止一条」的物品', false, '候选物品都没碰上，跳过');
        } else {
            await session.goto(BASE + '/#/item/' + multiId);
            await waitFor(session, "document.querySelectorAll('.recipe-group').length > 0", 15000, '配方组');
            console.log('  样本: ' + multiTitle);
            const grouped = await session.eval(`(() => {
                const groups = Array.from(document.querySelectorAll('.recipe-group'));
                const cards = document.querySelectorAll('.recipe-group .recipe');
                return {
                    groups: groups.length,
                    cards: cards.length,
                    // 合并的意义：卡片数应当少于后端给的配方数（除非每条各自一类）
                    withNav: groups.filter(g => g.querySelector('.recipe-nav-btn')).length,
                    withIcon: groups.filter(g => g.querySelector('.group-icon img, .group-icon .icon-ph')).length,
                    firstCount: String((groups[0].querySelector('.recipe-nav-count') || {}).textContent || '')
                };
            })()`);
            check('配方按同类型合并成组', grouped.groups > 0, grouped.groups + ' 组');
            check('每组只渲染一张卡片（不是把配方全铺开）', grouped.cards === grouped.groups,
                grouped.cards + ' 张卡 / ' + grouped.groups + ' 组');
            check('组内有左右翻页控件', grouped.withNav === grouped.groups, grouped.firstCount);
            // 类别图标：烧制是熔炉、组装机是组装机方块……认图比认字快
            check('组标题上有类别图标', grouped.withIcon > 0,
                grouped.withIcon + '/' + grouped.groups + ' 组有图标');

            // GT 的耗电/耗时：材料表说不清「要几级机器、够不够电」。
            // 这一页的前 80 条常常全是原版熔炉（EU/t=0 是**对的**），
            // 所以真正的断言放在计划页那一段（那里的步骤里有 GT 机器）
            const power = await session.eval(`(() => {
                const boxes = Array.from(document.querySelectorAll('.recipe-power'));
                return { count: boxes.length, first: boxes.length ? String(boxes[0].textContent || '').trim() : '' };
            })()`);
            console.log('  本页 GT 配方条目：' + power.count + (power.first ? '（' + power.first + '）' : ''));

            const navWorked = await session.eval(`(() => {
                const group = Array.from(document.querySelectorAll('.recipe-group'))
                    .find(g => /\\/ \\d+/.test(String((g.querySelector('.recipe-nav-count') || {}).textContent || ''))
                        && Number(String(g.querySelector('.recipe-nav-count').textContent).split('/')[1]) > 1);
                if (!group) return { skipped: true };
                const before = group.querySelector('.recipe-nav-count').textContent.trim();
                const rid = String(group.querySelector('.recipe').getAttribute('data-rid') || '');
                group.querySelector('.recipe-nav-btn:last-child').click();
                return { skipped: false, before: before };
            })()`);
            if (navWorked.skipped) {
                check('点右箭头能翻到下一条', false, '没有多条的组');
            } else {
                await sleep(200);
                const after = await session.eval(`(() => {
                    const group = Array.from(document.querySelectorAll('.recipe-group'))
                        .find(g => /\\/ \\d+/.test(String((g.querySelector('.recipe-nav-count') || {}).textContent || '')));
                    return String((group.querySelector('.recipe-nav-count') || {}).textContent || '').trim();
                })()`);
                check('点右箭头能翻到下一条', after !== navWorked.before,
                    navWorked.before + ' → ' + after);
            }
            console.log('  截图: ' + await session.shot('recipes-grouped'));

            // 手机上没有 hover：点格子应当弹出信息条，而不是直接跳进配方弹层
            const tapped = await session.eval(`(() => {
                const cell = document.querySelector('.recipe .slot.is-clickable');
                if (!cell) return 'no-slot';
                cell.click();
                return 'clicked';
            })()`);
            check('配方格子可点', tapped === 'clicked', tapped);
            await sleep(200);
            const info = await session.eval(`(() => {
                const bar = document.getElementById('item-info');
                const overlay = document.getElementById('overlay');
                return {
                    shown: !!bar && !bar.hidden,
                    text: bar ? String(bar.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60) : '',
                    modalOpen: !!overlay && !overlay.hidden,
                    hasRecipeButton: !!bar && Array.from(bar.querySelectorAll('button'))
                        .some(b => /看它的配方/.test(b.textContent))
                };
            })()`);
            check('点格子弹出「这是什么」信息条', info.shown === true, info.text);
            check('信息条里带「看它的配方」入口', info.hasRecipeButton === true);
            check('点格子不会直接跳进配方弹层（少一次整屏切换）', info.modalOpen === false);
            console.log('  截图: ' + await session.shot('slot-tap-info'));

            // 顺着信息条上的「看它的配方」进弹层 —— 顺便验证这条路真的通
            const viaInfo = await session.eval(`(() => {
                const b = Array.from(document.querySelectorAll('#item-info button'))
                    .find(x => /看它的配方/.test(x.textContent));
                if (!b) return false;
                b.click();
                return true;
            })()`);
            check('信息条上的「看它的配方」能进弹层', viaInfo === true);
            await sleep(600);
            // 这条路径只验「弹层开出来了」：格子里的材料很可能是原矿，本身没有配方，
            // 拿它验「同类型合并」是挑错了对象（踩过 —— 弹层里那句「这个物品没有配方」
            // 明明是对的，却被我当成了加载失败）
            const modalOpened = await session.eval(
                "(() => { const o = document.getElementById('overlay'); return !!o && !o.hidden; })()");
            check('点「看它的配方」弹层确实开了', modalOpened === true);
            await session.eval("document.getElementById('modal-close').click()");
            await sleep(200);

            // 「同类型合并」的完整流程换个入口验：去这个物品自己的计划页，
            // 点**它自己那一步**的图标（步骤卡片上有 data-item，按物品号精确定位 ——
            // 点「第一步」是不行的，第一步的产物往往是它的前置材料）
            await session.goto(BASE + '/#/plan/' + multiId + '?count=1');
            await waitFor(session, "document.querySelectorAll('.step[data-item]').length > 0", 20000, '计划步骤');
            const clickedSelf = await session.eval(`(() => {
                const step = document.querySelector('.step[data-item="${multiId}"]');
                if (!step) return false;
                const icon = step.querySelector('.icon-btn');
                if (!icon) return false;
                icon.click();
                return true;
            })()`);
            check('能点到该物品自己那一步', clickedSelf === true);
            await waitFor(session, "document.querySelectorAll('#modal-body .recipe-group').length > 0", 15000,
                '弹层里的配方组');

            const modalMulti = await session.eval(`(() => {
                const groups = Array.from(document.querySelectorAll('#modal-body .recipe-group'));
                const cards = document.querySelectorAll('#modal-body .recipe-group .recipe');
                const multi = groups.find(g => {
                    const t = String((g.querySelector('.recipe-nav-count') || {}).textContent || '');
                    const m = t.match(/\\/\\s*(\\d+)/);
                    return m && Number(m[1]) > 1;
                });
                if (!multi) return { groups: groups.length, cards: cards.length, multi: false };
                const before = String(multi.querySelector('.recipe-nav-count').textContent).trim();
                multi.querySelector('.recipe-nav-btn:last-child').click();
                const after = String(multi.querySelector('.recipe-nav-count').textContent).trim();
                const card = multi.querySelector('.recipe');
                const rid = String(card.getAttribute('data-rid') || '');
                window.__modalPickRid = rid;
                return { groups: groups.length, cards: cards.length, multi: true, before: before, after: after, rid: rid };
            })()`);
            check('弹层里的配方也按同类型合并', modalMulti.groups > 0 && modalMulti.cards === modalMulti.groups,
                modalMulti.cards + ' 张卡 / ' + modalMulti.groups + ' 组');
            check('弹层里的组能翻页', modalMulti.multi === true && modalMulti.before !== modalMulti.after,
                modalMulti.before + ' → ' + modalMulti.after);
            // 截图要在「选它」之前拍：选完弹层会自己关掉，那就拍到计划页了（踩过）
            console.log('  截图: ' + await session.shot('modal-grouped'));

            // 现在才点「选它」——验的是「翻到哪一条就选中哪一条」，不只是界面上动了
            const pickedNow = await session.eval(`(() => {
                const card = document.querySelector('#modal-body .recipe-group .recipe');
                if (!card) return false;
                const b = Array.from(card.querySelectorAll('button')).find(x => x.textContent.trim() === '选它');
                if (!b) return false;
                b.click();
                return true;
            })()`);
            check('翻页后点「选它」', pickedNow === true);
            await sleep(400);
            const chosenNow = await session.eval("localStorage.getItem('futa_gtnh.choices') || '{}'");
            check('翻页后选中的确实是翻到的那一条',
                !!modalMulti.rid && String(chosenNow).indexOf(modalMulti.rid) >= 0,
                'rid=' + modalMulti.rid + '，存储=' + String(chosenNow).slice(0, 60));
            check('选完自动关闭弹层', await session.eval(
                "(() => { const o = document.getElementById('overlay'); return !o || o.hidden; })()") === true);
        }

        console.log('== 立刻出现的悬停提示 ==');
        // 浏览器给 title 加了约一秒延迟，所以我们自己画浮层。
        // 验的是「鼠标一进去，title 就变成 data-tip 且浮层立刻可见」
        const tipState = await session.eval(`(() => {
            const cell = document.querySelector('.step-grid .slot[title], .step-grid .slot[data-tip]');
            if (!cell) return null;
            const rect = cell.getBoundingClientRect();
            return { x: Math.round(rect.left + rect.width / 2), y: Math.round(rect.top + rect.height / 2),
                     title: String(cell.getAttribute('title') || '').slice(0, 30) };
        })()`);
        if (!tipState) {
            check('找得到带提示的格子', false, '没有');
        } else {
            check('找得到带提示的格子', !!tipState.title, tipState.title);
            await session.send('Input.dispatchMouseEvent',
                { type: 'mouseMoved', x: tipState.x, y: tipState.y, buttons: 0 });
            await sleep(120);
            const tipShown = await session.eval(`(() => {
                const box = document.getElementById('tip');
                const cell = document.querySelector('.step-grid .slot[data-tip], .step-grid .slot[title]');
                return {
                    visible: !!box && !box.hidden,
                    text: box ? String(box.textContent || '').slice(0, 40) : '',
                    // 原生提示必须已经被摘掉，否则一秒后还会冒出来一个
                    nativeGone: !!cell && !cell.hasAttribute('title')
                };
            })()`);
            check('鼠标一进提示就出现（不等一秒）', tipShown.visible === true, tipShown.text);
            check('原生的慢提示已被摘掉', tipShown.nativeGone === true);

            // ★ 同一个格子悬停第二次也必须还出提示。
            //
            // 第一次悬停会把 title 挪成 data-tip，如果查元素时只认 [title]，
            // 第二次就什么都找不到 —— 玩家报的正是这个：「显示一次，第二次不显示」。
            await session.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 4, y: 4, buttons: 0 });
            await sleep(150);
            const hiddenAfterLeave = await session.eval(
                "(() => { const b = document.getElementById('tip'); return !!b && b.hidden; })()");
            check('鼠标移开后提示消失', hiddenAfterLeave === true);
            await session.send('Input.dispatchMouseEvent',
                { type: 'mouseMoved', x: tipState.x, y: tipState.y, buttons: 0 });
            await sleep(150);
            const secondTime = await session.eval(`(() => {
                const box = document.getElementById('tip');
                return { visible: !!box && !box.hidden, text: box ? String(box.textContent || '').slice(0, 30) : '' };
            })()`);
            check('同一个格子再悬停一次，提示仍然出现', secondTime.visible === true, secondTime.text);
        }

        console.log('== 多目标：一起做 A 和 B ==');
        // 直接打接口验「两个目标一次算完」
        const t1 = await resolveId(['Iron Ingot', '铁锭'], (i) => /^(Iron Ingot|铁锭)$/.test(i.name));
        const t2 = await resolveId(['Aluminium Ingot', '铝锭'], (i) => /^(Aluminium Ingot|铝锭)$/.test(i.name));
        const multi = await (await fetch(BASE + '/api/plan?id=' + t1 + '&count=64&stock=0&targets='
            + t1 + ':64,' + t2 + ':3')).json();
        const multiTargets = Array.isArray(multi.targets) ? multi.targets : [];
        check('一次规划可以带多个目标', multiTargets.length === 2,
            multiTargets.map((t) => t.name + '×' + t.count).join(' + '));
        check('两个目标都在计划里',
            multiTargets.some((t) => t.id === t1) && multiTargets.some((t) => t.id === t2),
            JSON.stringify(multiTargets.map((t) => t.id)));
        // 分开算的材料总量 ≥ 合起来算的（公共中间产物只算一次）
        const onlyA = await (await fetch(BASE + '/api/plan?id=' + t1 + '&count=64&stock=0')).json();
        const onlyB = await (await fetch(BASE + '/api/plan?id=' + t2 + '&count=3&stock=0')).json();
        const sumSteps = (onlyA.steps || []).length + (onlyB.steps || []).length;
        check('合起来算的步骤不会比分开算多', (multi.steps || []).length <= sumSteps,
            '合并=' + (multi.steps || []).length + ' 分开=' + sumSteps);

        // 界面：把 A 加进「一起做」，再打开 B 的计划页，应该看到两行
        await session.goto(BASE + '/#/item/' + t1);
        await waitFor(session, "document.querySelectorAll('.basket-add button').length > 0", 15000, '「一起做」按钮');
        await session.eval(`(() => {
            const b = Array.from(document.querySelectorAll('.basket-add button'))
                .find(x => /加进计划清单/.test(x.textContent));
            if (b) b.click();
            return !!b;
        })()`);
        await sleep(300);
        const basketStored = await session.eval("localStorage.getItem('futa_gtnh.basket') || ''");
        check('「一起做」写进了浏览器本地', basketStored.indexOf(String(t1)) >= 0, basketStored.slice(0, 60));
        await session.goto(BASE + '/#/plan/' + t2 + '?count=3');
        await waitFor(session, "document.querySelectorAll('.step').length > 0", 20000, '计划步骤');
        const basketDom = await session.eval(`(() => {
            const rows = document.querySelectorAll('.basket-row');
            const current = document.querySelectorAll('.basket-row.is-current').length;
            const title = Array.from(document.querySelectorAll('.section-title'))
                .map(e => String(e.textContent || '')).find(t => /一起做/.test(t)) || '';
            return { rows: rows.length, current: current, title: title.slice(0, 20) };
        })()`);
        check('计划页列出了「一起做」清单', basketDom.rows >= 2, basketDom.title + '，' + basketDom.rows + ' 行');
        check('当前这一件标了出来', basketDom.current === 1, String(basketDom.current));
        console.log('  截图: ' + await session.shot('plan-multi-target'));
        // 清掉清单，别影响后面的段落
        await session.eval("localStorage.setItem('futa_gtnh.basket','[]')");

        console.log('== 计划页：左侧物品图标 ==');
        await session.goto(BASE + '/#/plan/' + TARGET + '?count=64');
        await waitFor(session, "document.querySelectorAll('.step').length > 0", 20000, '步骤卡片');

        const layout = await session.eval(`(() => {
            const steps = document.querySelectorAll('.step');
            const first = steps[0];
            return {
                steps: steps.length,
                iconButtons: document.querySelectorAll('.step .icon-btn').length,
                firstHasIcon: !!(first && first.querySelector('.step-head .icon-btn img, .step-head .icon-btn .icon-ph')),
                matLinesWithIcon: Array.from(document.querySelectorAll('.mat-line'))
                    .filter(l => l.querySelector('.icon-btn')).length,
                matLines: document.querySelectorAll('.mat-line').length,
                tagOnStep: document.querySelectorAll('.step .tag-chosen').length,
                matRows: document.querySelectorAll('.material').length,
                matRowsWithIcon: Array.from(document.querySelectorAll('.material'))
                    .filter(r => r.querySelector('.material-main .icon-btn')).length
            };
        })()`);
        check('每个步骤左侧都有物品图标', layout.steps > 0 && layout.iconButtons >= layout.steps,
            layout.iconButtons + ' 个图标 / ' + layout.steps + ' 步');
        check('步骤图标里真的画出了东西', layout.firstHasIcon === true);
        check('材料行也都有图标', layout.matLines === 0 || layout.matLinesWithIcon === layout.matLines,
            layout.matLinesWithIcon + '/' + layout.matLines);
        check('要准备的物品行有图标', layout.matRows === 0 || layout.matRowsWithIcon === layout.matRows,
            layout.matRowsWithIcon + '/' + layout.matRows);
        check('用了手动指定配方的那一步有标识', layout.tagOnStep >= 1, layout.tagOnStep + ' 处');
        // 验语义而不是验某个具体物品：标识只该出现在「这一步用的正是你指定的那条」的步骤上。
        // 原来这里写死了藻类农场 —— 而前面的小节会给别的物品指定配方，
        // 断言就会莫名其妙地挂在一个"没错"的界面上（踩过）
        const tagged = await session.eval(`(() => {
            const step = Array.from(document.querySelectorAll('.step'))
                .find(s => s.querySelector('.tag-chosen'));
            if (!step) return null;
            const item = String(step.getAttribute('data-item') || '');
            const choices = JSON.parse(localStorage.getItem('futa_gtnh.choices') || '{}');
            return {
                item: item,
                rid: String(step.getAttribute('data-rid') || ''),
                chosen: String(choices[item] || ''),
                text: String((step.querySelector('.step-out') || {}).textContent || '').trim()
            };
        })()`);
        check('「已选配方」只出现在你确实指定过的那一步上',
            !!tagged && tagged.chosen !== '' && tagged.chosen === tagged.rid,
            tagged ? ('物品#' + tagged.item + ' 该步配方=' + tagged.rid + ' 你指定的=' + tagged.chosen + ' ' + tagged.text) : '没有标识');

        // 步骤里要能照着摆：展开的那几步必须带完整合成表（网格），
        // 只有汇总的材料清单是不够的 —— 清单里没有槽位坐标
        const stepGrids = await session.eval(`(() => {
            const steps = Array.from(document.querySelectorAll('.step'));
            const withGrid = steps.filter(s => s.querySelector('.step-grid .grid'));
            const cells = document.querySelectorAll('.step-grid .slot').length;
            const readyGrids = document.querySelectorAll('.ready-item .step-grid .grid').length;
            return { steps: steps.length, withGrid: withGrid.length, cells: cells, readyGrids: readyGrids };
        })()`);
        check('展开的步骤里有完整合成表', stepGrids.withGrid > 0,
            stepGrids.withGrid + ' 步带网格 / 共 ' + stepGrids.steps + ' 步，' + stepGrids.cells + ' 个槽位');
        check('「现在可以做」里也带合成表', stepGrids.readyGrids > 0, stepGrids.readyGrids + ' 个网格');

        // GT 的耗电/耗时：在计划页验 —— 这里的步骤里一定有 GT 机器。
        // 折叠状态也该显示，所以看的是标题上的短标签 .step-power
        const stepPower = await session.eval(`(() => {
            const boxes = Array.from(document.querySelectorAll('.step .step-power'));
            const steps = Array.from(document.querySelectorAll('.step')).slice(0, 4).map(s => ({
                machine: String((s.querySelector('.step-machine') || {}).textContent || ''),
                power: String((s.querySelector('.step-power') || {}).textContent || '')
            }));
            return { count: boxes.length, first: boxes.length ? String(boxes[0].textContent || '').trim() : '',
                     steps: steps };
        })()`);
        check('步骤里显示了 GT 的耗电/耗时', stepPower.count > 0,
            stepPower.first || ('前几步：' + JSON.stringify(stepPower.steps)));
        check('耗电文字里有电压等级', /EU\/t/.test(stepPower.first) && /(ULV|LV|MV|HV|EV|IV|LuV|ZPM|UV|UHV)/.test(stepPower.first),
            stepPower.first);

        // 步骤网格里的数字必须是「做 n 次」的总量，不能是 NEI 那种每次用量。
        // 这条断言拿后端自己的数字对：每个槽的 每次用量 × 做几次 应当等于角标上的数。
        const totals = await session.eval(`(() => {
            const steps = Array.from(document.querySelectorAll('.step'));
            for (const step of steps) {
                const grid = step.querySelector('.step-grid .grid');
                const line = String((step.querySelector('.step-line') || {}).textContent || '');
                const m = line.match(/做\\s*(\\d+)/);
                if (!grid || !m) continue;
                const times = Number(m[1]);
                if (times <= 1) continue;
                const badges = Array.from(grid.querySelectorAll('.slot-count'))
                    .map(b => String(b.textContent || '').trim());
                const titles = Array.from(grid.querySelectorAll('.slot[title]'))
                    .map(s => String(s.title || ''));
                const perCraft = titles.map(t => {
                    const mm = t.match(/每个配方 ×(\\d+)/);
                    return mm ? Number(mm[1]) : 0;
                });
                return { times: times, badges: badges.slice(0, 6), perCraft: perCraft.slice(0, 6) };
            }
            return null;
        })()`);
        if (!totals) {
            check('步骤网格显示的是「做 n 次」的总量', false, '没有找到做了多次的步骤');
        } else {
            // 每个有「每个配方 ⨯p」标注的槽，角标应当 ≥ p×times（大数量会写成「万」，只查小数量）
            let matched = 0;
            for (let i = 0; i < totals.badges.length; i++) {
                const p = totals.perCraft[i];
                if (!p) continue;
                const badge = totals.badges[i];
                if (/万/.test(badge)) continue;
                const want = p * totals.times;
                if (Number(badge) === want) matched++;
            }
            check('步骤网格显示的是「做 n 次」的总量', matched > 0,
                '做 ' + totals.times + ' 次；角标=' + totals.badges.join(',')
                    + ' 每次用量=' + totals.perCraft.join(','));
        }

        // 大数量要给出「几组零几个」：4570 这个数字本身说明不了要腾几格包
        await session.goto(BASE + '/#/plan/' + TARGET + '?count=6400');
        await waitFor(session, "document.querySelectorAll('.material, .step').length > 0", 20000, '大数量计划');
        const stacky = await session.eval(`(() => {
            const texts = Array.from(document.querySelectorAll('.material-stat, .step-line, .ready-name'))
                .map(e => String(e.textContent || ''));
            const hit = texts.find(t => /（\\d+×64/.test(t)) || '';
            const big = texts.find(t => /\\d{3,}/.test(t)) || '';
            return { hit: hit.replace(/\\s+/g, ' ').trim().slice(0, 50), big: big.replace(/\\s+/g, ' ').trim().slice(0, 50) };
        })()`);
        check('超过一组（64）的数量附带「n×64+m」', !!stacky.hit, stacky.hit || ('没找到，样例：' + stacky.big));

        // 同一个换算也得出现在「提示」和「点格子弹出的信息条」里 ——
        // 那两处才是玩家真正决定「要不要去腾背包」的地方
        const stackInTip = await session.eval(`(() => {
            const cells = Array.from(document.querySelectorAll('.step-grid .slot[title]'));
            const tip = cells.map(c => String(c.title || '')).find(t => /（\\d+×64/.test(t)) || '';
            const cell = cells.find(c => /（\\d+×64/.test(String(c.title || '')));
            if (!cell) return { tip: '', info: '' };
            cell.click();
            const bar = document.getElementById('item-info');
            return { tip: tip.split('\\n')[0].slice(0, 60), info: String((bar || {}).textContent || '').replace(/\\s+/g, ' ').slice(0, 60) };
        })()`);
        check('格子提示里的数量也带「n×64+m」', /（\d+×64/.test(String(stackInTip.tip)), String(stackInTip.tip));
        check('点格子弹出的信息条里也带「n×64+m」',
            /（\d+×64/.test(String(stackInTip.info)), String(stackInTip.info));
        console.log('  截图: ' + await session.shot('slot-tap-info'));
        await session.eval("document.getElementById('item-info').hidden = true");
        console.log('  截图: ' + await session.shot('step-with-grid'));

        const planShot = await session.shot('plan-chosen');
        console.log('  截图: ' + planShot);

        console.log('== 点图标能不能打开配方弹层 ==');
        // 先把鼠标移到图标上：CSS 的 :hover 只有真的悬停才生效，
        // 光看样式表写了不算数
        const box = await session.eval(`(() => {
            const b = document.querySelector('.step-head .icon-btn');
            if (!b) return null;
            // 先滚到视野里，再取坐标：CDP 的鼠标事件用的是**视口**坐标，
            // 加 scrollY 反而会指到屏幕外面去
            b.scrollIntoView({ block: 'center' });
            const r = b.getBoundingClientRect();
            return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
        })()`);
        if (box) {
            await session.send('Input.dispatchMouseEvent',
                { type: 'mouseMoved', x: box.x, y: box.y, buttons: 0 });
            await sleep(150);
        }
        const hovered = await session.eval(
            "Array.from(document.querySelectorAll(':hover')).some(e => e.classList && e.classList.contains('icon-btn'))");
        check('鼠标移上去确实命中图标（hover 会生效）', hovered === true, box ? '(' + box.x + ',' + box.y + ')' : '无图标');

        const opened = await session.eval(`(() => {
            const icon = document.querySelector('.step-head .icon-btn');
            if (!icon) return 'no-icon';
            icon.click();
            return 'clicked';
        })()`);
        check('步骤图标可点击', opened === 'clicked', opened);
        const modal = await waitFor(session,
            "(() => { const o = document.getElementById('overlay'); return !!(o && !o.hidden && document.getElementById('modal-title').textContent); })()",
            8000, '配方弹层').catch(() => '');
        check('点图标打开了配方弹层', !!modal, String(modal));

        // 弹层里的配方也得合并：23 条「Fluid Extractor Recycling」曾经是一条一张卡。
        // 注意弹层内容是异步拉的，得先等内容出来 —— 不然断言跑在空弹层上，
        // 「0 张卡 / 0 组」看着还挺"一致"（踩过）
        await waitFor(session, "document.querySelectorAll('#modal-body .recipe-group').length > 0", 15000,
            '弹层里的配方组');
        const modalGrouping = await session.eval(`(() => {
            const body = document.getElementById('modal-body');
            const groups = body.querySelectorAll('.recipe-group');
            const cards = body.querySelectorAll('.recipe-group .recipe');
            const navs = Array.from(groups).filter(g => g.querySelector('.recipe-nav-btn'));
            const multi = Array.from(groups).find(g => {
                const t = String((g.querySelector('.recipe-nav-count') || {}).textContent || '');
                const m = t.match(/\\/\\s*(\\d+)/);
                return m && Number(m[1]) > 1;
            });
            return {
                groups: groups.length,
                cards: cards.length,
                navs: navs.length,
                multi: !!multi,
                count: multi ? String(multi.querySelector('.recipe-nav-count').textContent).trim() : '',
                hasIcon: Array.from(groups).some(g => g.querySelector('.group-icon img, .group-icon .icon-ph'))
            };
        })()`);
        check('弹层里的配方也按同类型合并', modalGrouping.groups > 0 && modalGrouping.cards === modalGrouping.groups,
            modalGrouping.cards + ' 张卡 / ' + modalGrouping.groups + ' 组');
        check('弹层里的组也有类别图标', modalGrouping.hasIcon === true);
        // 「翻页 + 选中」的完整流程在上一段验过了（那里挑的物品保证有多条同类型配方）；
        // 这一步的物品五条配方分属五个处理器，没有可翻的组
        if (modalGrouping.navs !== modalGrouping.groups) {
            check('弹层里的组都能翻页', false, modalGrouping.navs + '/' + modalGrouping.groups);
        }
        const modalShot = await session.shot('modal-from-step');
        console.log('  截图: ' + modalShot);

        console.log('== 未选择配方时不该有标识 ==');
        await session.eval("localStorage.clear(); localStorage.setItem('futa_gtnh.ignorestock','1');");
        // 必须真的重新加载：只改 hash 不会重载页面，内存里的旧渲染会骗过断言
        await session.send('Page.reload', { ignoreCache: true });
        await sleep(500);
        await waitFor(session, "document.querySelectorAll('.step').length > 0", 20000, '步骤卡片');
        const noTag = await session.eval(
            "document.querySelectorAll('.tag-chosen').length + '/' + document.querySelectorAll('.icon-btn.is-chosen').length");
        check('清空选择后标识消失', noTag === '0/0', noTag);
    } finally {
        if (session) session.ws.close();
        edge.kill();
    }

    console.log('\n结果: ' + passed + ' 通过 / ' + failed + ' 失败');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
    console.error(err && err.stack ? err.stack : String(err));
    process.exit(1);
});
