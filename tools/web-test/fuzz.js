// 批量规划压力测试：沿物品目录抽样，逐个规划并检查结构不变量。
//
// 用法：node tools/web-test/fuzz.js
//       SAMPLE=200 node tools/web-test/fuzz.js        # 抽更多样本
//
// 它盯的是那几类「一眼看不出、但会毁掉整份指导」的错误：
// 步骤顺序排错（材料的配方排在用它的那一步之后）、自产自销、
// 空材料步骤、数量算成负数或零、目标既在材料表又在步骤表里。
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const SAMPLE = Number(process.env.SAMPLE || 40);

let fail = 0;
function check(name, cond, extra) {
    if (!cond) { fail++; console.log('  FAIL ' + name + (extra ? '  ' + extra : '')); }
}

async function json(path) {
    const res = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
    const text = await res.text();
    try { return JSON.parse(text); } catch (e) { return { ok: false, error: 'JSON: ' + e.message + ' / ' + text.slice(0, 160) }; }
}

function validatePlan(p, item) {
    const tag = item.name + '#' + item.id;
    check('plan.ok ' + tag, p.ok === true, p.error);
    if (p.ok !== true) return;

    check('有目标 ' + tag, p.target && p.target.id === item.id);
    check('summary ' + tag, p.summary && typeof p.summary.steps === 'number');

    const seen = new Set();
    p.steps.forEach((s, i) => {
        check('步骤编号 ' + tag, s.n === i + 1);
        check('步骤不重复 ' + tag, !seen.has(s.rid), 'rid=' + s.rid);
        seen.add(s.rid);
        check('有物品材料 ' + tag + ' step' + s.n, s.inputs.length > 0);
        check('不自产自销 ' + tag + ' step' + s.n, !s.inputs.some(x => x.id === s.itemId));
        // 同名变体互换（另一个 meta 1:1 换成本物品）：数据上不是自循环，但玩家看到的
        // 是「做 64 次：藻类农场 → 藻类农场」。默认挑配方时必须避开。
        //
        // 只查 1:1 或更少的：1:N 的同名配方是有意义的加工
        //（实测「1 个红蘑菇 → 5 个棕蘑菇」的树场配方，两者中文名都叫「蘑菇」）
        check('不拿同名变体互换 ' + tag + ' step' + s.n,
            !(s.output.perCraft <= 1 && s.inputs.some(x => x.name === s.output.name)),
            s.inputs.map(x => x.name).join('/') + ' → ' + s.output.name + ' ×' + s.output.perCraft);
        check('数量为正 ' + tag + ' step' + s.n, s.crafts > 0 && s.output.perCraft > 0);
        for (const inp of s.inputs) {
            check('材料数量为正 ' + tag + ' step' + s.n, inp.perCraft > 0 && inp.need > 0);
            check('还缺不为负 ' + tag, inp.missing >= 0 && inp.have >= 0);
        }
    });

    // 依赖顺序：任何一步的材料，如果是本计划里做出来的，必须排在前面
    const at = new Map();
    p.steps.forEach((s, i) => at.set(s.itemId, i));
    p.steps.forEach((s, i) => {
        for (const inp of s.inputs) {
            const j = at.get(inp.id);
            if (j !== undefined) check('顺序 ' + tag + ' step' + s.n, j < i, inp.name + ' 在第' + (j + 1) + '步');
        }
    });

    for (const m of p.materials) {
        check('材料缺额 ' + tag, m.missing > 0 && m.missing <= m.need);
    }
    // 目标本身不该同时出现在「要准备的材料」和「要做的步骤」里
    check('目标不重复 ' + tag, !(p.materials.some(m => m.id === item.id) && p.steps.some(s => s.itemId === item.id)));
}

(async () => {
    const t0 = Date.now();
    // 从目录的多个区段取样：只取前 200 个的话，拿到的全是模组列表最前面那几家的东西，
    // 覆盖不到 GT 那些真正深的配方树
    const offsets = [0, 300, 900, 2500, 6000, 12000, 20000, 28000];
    const pool = [];
    for (const off of offsets) {
        const page = await json('/api/search?q=&limit=200&offset=' + off);
        if (!page.ok) continue;
        for (const it of page.items) if (it.craftable) pool.push(it);
    }
    check('能拿到物品列表', pool.length > 0);
    const picks = [];
    const step = Math.max(1, Math.floor(pool.length / SAMPLE));
    for (let i = 0; i < pool.length && picks.length < SAMPLE; i += step) picks.push(pool[i]);
    console.log('抽样 ' + picks.length + ' 个可合成物品（候选池 ' + pool.length + '）');

    let slowest = 0, slowestName = '', deepest = 0, deepestName = '';
    for (const item of picks) {
        const start = Date.now();
        const plan = await json('/api/plan?id=' + item.id + '&count=16&stock=1');
        const ms = Date.now() - start;
        if (ms > slowest) { slowest = ms; slowestName = item.name; }
        if (plan.ok && plan.summary && plan.summary.steps > deepest) {
            deepest = plan.summary.steps;
            deepestName = item.name;
        }
        validatePlan(plan, item);
    }
    console.log('总耗时 ' + (Date.now() - t0) + ' ms，最慢一次 ' + slowest + ' ms（' + slowestName + '）'
        + '，步骤最多 ' + deepest + ' 步（' + deepestName + '）');
    console.log(fail === 0 ? '全部通过' : (fail + ' 项失败'));
    process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.error('异常: ' + e.message + '\n' + e.stack); process.exit(2); });
