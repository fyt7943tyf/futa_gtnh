// 合成向导网页接口的端到端自测。
//
// 用法：先开着游戏（客户端），确认网页服务已经在监听，然后
//     node tools/web-test/smoke.js
//     BASE=http://192.168.1.23:8765 node tools/web-test/smoke.js    # 换地址
//
// 只用 Node 18+ 自带的 fetch，没有任何依赖。它检查的是「接口契约」：
// 字段有没有、类型对不对、槽位坐不坐得进网格、步骤顺序排得对不对、
// 库存扣减算得对不对。改完后端跑一遍，比拿手机一条条点快得多。
const BASE = process.env.BASE || 'http://127.0.0.1:8765';

let pass = 0;
let fail = 0;
function ok(name, cond, extra) {
    if (cond) { pass++; console.log('  PASS ' + name + (extra ? '  ' + extra : '')); }
    else { fail++; console.log('  FAIL ' + name + (extra ? '  ' + extra : '')); }
}

async function json(path) {
    const res = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
    const text = await res.text();
    let data = null;
    try { data = JSON.parse(text); } catch (e) { throw new Error(path + ' -> JSON 解析失败: ' + e.message + ' / ' + text.slice(0, 200)); }
    return { status: res.status, type: res.headers.get('content-type'), data };
}

(async () => {
    console.log('== status ==');
    const st = (await json('/api/status')).data;
    console.log(JSON.stringify(st));
    ok('status.ok', st.ok === true);
    ok('indexed', st.indexed === true, 'handlers=' + st.handlers + ' recipes=' + st.recipes + ' items=' + st.items);

    console.log('== 静态页面 ==');
    for (const f of ['/', '/app.css', '/app.js']) {
        const res = await fetch(BASE + f);
        const body = await res.text();
        ok('static ' + f, res.status === 200 && body.length > 500, res.status + ' ' + body.length + 'B');
    }
    const html = await (await fetch(BASE + '/')).text();
    ok('html 引用 app.js', html.includes('app.js'));

    console.log('== 搜索 ==');
    const search = (await json('/api/search?q=' + encodeURIComponent('电路板') + '&limit=5')).data;
    ok('search.ok', search.ok === true, 'total=' + search.total);
    ok('search 有结果', search.items.length > 0);
    const first = search.items[0];
    ok('结果字段齐全', typeof first.id === 'number' && first.name && first.icon && typeof first.stock === 'number',
        JSON.stringify(first));

    const pinyin = (await json('/api/search?q=dianlu&limit=5')).data;
    ok('拼音搜索可用', pinyin.total > 0, 'total=' + pinyin.total);

    const mod = (await json('/api/search?q=' + encodeURIComponent('@gregtech') + '&limit=3')).data;
    ok('@模组 搜索可用', mod.total > 0, 'total=' + mod.total);

    console.log('== 物品与配方 ==');
    const item = (await json('/api/item?id=' + first.id)).data;
    ok('item.ok', item.ok === true);
    ok('有配方', item.recipes.length > 0, 'recipes=' + item.recipes.length);
    ok('usedIn 是数组', Array.isArray(item.usedIn));

    let slotOk = true, gridOk = true, resultOk = true;
    const overlaps = [];
    for (const r of item.recipes) {
        if (r.resultId !== first.id) resultOk = false;
        if (!r.grid || r.grid.w < 1 || r.grid.h < 1) gridOk = false;
        for (const s of [...r.inputs, ...r.outputs, ...r.extras]) {
            if (typeof s.x !== 'number' || typeof s.y !== 'number') slotOk = false;
            if (s.x >= r.grid.w || s.y >= r.grid.h) gridOk = false;
            if (!Array.isArray(s.alts) || s.alts.length === 0) slotOk = false;
            if (!s.primary || typeof s.primary.id !== 'number') slotOk = false;
        }
        // ★ 同一格里不能有两个材料槽。后端把 NEI 的像素坐标折算成格子，
        // 一旦算错（比如负数除法的截断），两行会被折进同一行、后写的覆盖先写的 ——
        // 表现就是「配方 7 个材料，页面上只显示 3 个」，而且接口字段全都是合法的。
        const cells = new Map();
        for (const s of r.inputs) {
            const key = s.x + ',' + s.y;
            if (cells.has(key)) {
                overlaps.push(r.machine + ' @' + key + '：' + cells.get(key) + ' 与 ' + s.primary.name);
            }
            cells.set(key, s.primary.name);
        }
    }
    ok('每条配方的 resultId 都指向该物品', resultOk);
    ok('槽位字段齐全', slotOk);
    ok('槽位都在网格内', gridOk);
    ok('同一格里没有两个材料槽', overlaps.length === 0, overlaps.slice(0, 4).join(' | '));

    // ★ 上面那条断言只覆盖了搜索结果里的第一个物品，不够 —— 出问题的配方
    // （槽位坐标为负的那些）不一定在抽样里。这里再扫一批物品，
    // 专门盯「格子折算」这类只在特定布局下才暴露的错误。
    console.log('== 批量抽查槽位布局 ==');
    const sweep = (await json('/api/search?q=&limit=12&offset=0')).data;
    let swept = 0, sweptOverlaps = [];
    for (const it of (sweep.items || [])) {
        const d = (await json('/api/item?id=' + it.id)).data;
        for (const r of (d.recipes || [])) {
            swept++;
            const cells = new Set();
            for (const s of r.inputs) {
                const key = s.x + ',' + s.y;
                if (cells.has(key)) sweptOverlaps.push(it.name + '/' + r.machine + ' @' + key);
                cells.add(key);
            }
        }
    }
    ok('抽查 ' + swept + ' 条配方的槽位都不重叠', sweptOverlaps.length === 0,
        sweptOverlaps.slice(0, 4).join(' | '));

    // ★ 定点回归：藻类农场的组装机配方（GT++ 多方块，槽位坐标里有负数）。
    // 这条当初就是被「负数除法截断」压掉了 4 个材料槽，页面上 7 个只显示 3 个。
    console.log('== 定点回归：藻类农场 ==');
    const algae = (await json('/api/search?q=' + encodeURIComponent('藻类农场') + '&limit=5')).data;
    let algaeOk = false, algaeDetail = '没找到';
    for (const it of (algae.items || [])) {
        const d = (await json('/api/item?id=' + it.id)).data;
        const asm = (d.recipes || []).find(r => String(r.handler).indexOf('assembler') >= 0);
        if (!asm) continue;
        const cells = new Set(asm.inputs.map(s => s.x + ',' + s.y));
        algaeOk = asm.inputs.length >= 5 && cells.size === asm.inputs.length;
        algaeDetail = '材料 ' + asm.inputs.length + ' 个，占 ' + cells.size + ' 格';
        break;
    }
    ok('组装机配方存在且材料槽不重叠', algaeOk, algaeDetail);

    console.log('== 图标 ==');
    const iconRes = await fetch(BASE + first.icon);
    const buf = Buffer.from(await iconRes.arrayBuffer());
    const isPng = buf.length > 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47;
    ok('图标是 PNG', iconRes.status === 200 && isPng, iconRes.status + ' ' + buf.length + 'B');

    console.log('== 规划 ==');
    const plan = (await json('/api/plan?id=' + first.id + '&count=8&stock=1')).data;
    ok('plan.ok', plan.ok === true, plan.error || '');
    ok('summary 齐全', plan.summary && typeof plan.summary.steps === 'number' && typeof plan.summary.depth === 'number');
    ok('有步骤', plan.steps.length > 0, 'steps=' + plan.steps.length);
    ok('步骤编号连续', plan.steps.every((s, i) => s.n === i + 1));
    let stepOk = true;
    for (const s of plan.steps) {
        if (!s.output || typeof s.output.perCraft !== 'number' || typeof s.crafts !== 'number') stepOk = false;
        if (!Array.isArray(s.inputs) || s.inputs.length === 0) stepOk = false;
        for (const i of s.inputs) {
            if (typeof i.perCraft !== 'number' || typeof i.need !== 'number' || typeof i.have !== 'number'
                || typeof i.missing !== 'number') stepOk = false;
        }
        if (s.extras === undefined) stepOk = false;
    }
    ok('步骤字段齐全', stepOk);
    ok('每一步都有物品形态的材料', plan.steps.every(s => s.inputs.length > 0),
        plan.steps.filter(s => s.inputs.length === 0).map(s => s.n + ':' + s.machine).join(','));
    ok('没有自产自销的步骤', plan.steps.every(s => !s.inputs.some(i => i.id === s.itemId)),
        plan.steps.filter(s => s.inputs.some(i => i.id === s.itemId)).map(s => s.n + ':' + s.itemId).join(','));
    ok('材料字段齐全', plan.materials.every(m => typeof m.need === 'number' && typeof m.have === 'number'
        && typeof m.missing === 'number'));

    // 规划里出现过的每一步都必须是「先做材料、后做成品」：不能出现 A 的输入是后面某步的产物
    const producedAfter = new Map();
    plan.steps.forEach((s, i) => producedAfter.set(s.itemId, i));
    let orderOk = true;
    plan.steps.forEach((s, i) => {
        for (const inp of s.inputs) {
            const at = producedAfter.get(inp.id);
            if (at !== undefined && at >= i) orderOk = false;
        }
    });
    ok('步骤顺序满足依赖（材料先做）', orderOk);

    console.log('== raw 参数（把某样东西当自己准备）==');
    if (plan.materials.length > 0) {
        const rawId = plan.materials[0].id;
        const rawPlan = (await json('/api/plan?id=' + first.id + '&count=8&stock=1&raw=' + rawId)).data;
        ok('raw 规划成功', rawPlan.ok === true);
        ok('raw 里的物品不再被展开', !rawPlan.steps.some(s => s.itemId === rawId));
    } else {
        ok('raw 规划成功', true, '(没有原材料，跳过)');
    }

    console.log('== choices 参数（指定配方）==');
    if (item.recipes.length >= 2) {
        const pick = item.recipes[1];
        const forced = (await json('/api/plan?id=' + first.id + '&count=4&stock=0&choices=' + first.id + ':' + pick.rid)).data;
        ok('choices 规划成功', forced.ok === true);
        ok('确实用了指定的配方', forced.steps.some(s => String(s.rid) === String(pick.rid)),
            'want=' + pick.rid + ' got=' + forced.steps.map(s => s.rid).join(','));
    } else {
        ok('choices 规划成功', true, '(只有一个配方，跳过)');
    }

    console.log('== 库存扣减 ==');
    const stockPlan = (await json('/api/plan?id=' + first.id + '&count=4&stock=1')).data;
    const noStockPlan = (await json('/api/plan?id=' + first.id + '&count=4&stock=0')).data;
    ok('stock=0 时 have 全为 0', noStockPlan.materials.every(m => m.have === 0 && m.missing === m.need));
    ok('stock=1 时 have 不为负', stockPlan.materials.every(m => m.have >= 0 && m.missing === Math.max(0, m.need - m.have)));

    console.log('== 错误处理 ==');
    const bad = (await json('/api/item?id=999999999')).data;
    ok('不存在的物品返回 ok=false', bad.ok === false, bad.error);
    const badPlan = (await json('/api/plan?id=999999999&count=1')).data;
    ok('不存在的物品规划返回 ok=false', badPlan.ok === false);

    console.log('\n结果: ' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.error('异常: ' + e.message); process.exit(2); });
