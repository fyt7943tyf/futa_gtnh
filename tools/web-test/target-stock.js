/*
 * 「目标不扣库存」的接口实测（纯 HTTP，不需要浏览器）。
 *
 * 玩家要的语义：
 *   我要合成 64 个最终产物 A，仓库里有 2 个 —— 那还是要做 64 个，不是 62 个；
 *   但中间产物不受影响，手里有的照旧要用掉。
 *
 * 对应的接口参数：stock=1&targetstock=0（stock=0 是"全都不扣"，那是另一个模式）。
 *
 * 断言：
 *   1. targetstock=0 时，目标那一步的合成次数 = 从零算（stock=0）时的次数
 *      —— 也就是目标完全没被仓库里的存量削减；
 *   2. 同一份计划里，至少有一个中间产物仍然吃到了库存
 *      （表现为它比"从零算"时做得少，或它的"已有"大于 0）—— 中间产物没被牵连；
 *   3. 不带这个参数时（targetstock=1）目标仍然按库存扣 —— 老行为不变；
 *   4. 目标自己出现在材料表里时，"已有"是 0（不是被库存抵掉）。
 *
 * 用法：node tools/web-test/target-stock.js [物品名]
 *   默认挑一件仓库里有货、又能自己合成的东西当目标。
 */
const BASE = process.env.BASE || 'http://127.0.0.1:8765';
const COUNT = 64;

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

async function api(path) {
    const res = await fetch(BASE + path);
    if (!res.ok) throw new Error(path + ' -> HTTP ' + res.status);
    return res.json();
}

/** 目标那一步做了几次（找不到就算 0 —— 说明它压根不用做）。 */
function targetCrafts(plan, id) {
    const steps = plan.steps || [];
    for (const step of steps) {
        if (step.itemId === id) return step.crafts;
    }
    return 0;
}

/** 这份计划里所有中间产物（不含目标）的合成次数。 */
function otherCrafts(plan, targetId) {
    const out = {};
    for (const step of plan.steps || []) {
        if (step.itemId === targetId) continue;
        out[step.itemId] = (out[step.itemId] || 0) + step.crafts;
    }
    return out;
}

/** 「已有」大于 0 的条目数（说明这些确实按库存扣了）。 */
function stockUsedCount(plan) {
    let n = 0;
    for (const material of plan.materials || []) {
        if (material.have > 0) n++;
    }
    for (const step of plan.steps || []) {
        for (const input of step.inputs || []) {
            if (input.have > 0) n++;
        }
    }
    return n;
}

(async () => {
    console.log('对象：' + BASE);

    // 挑目标：仓库里**有一点点**（1~16 个，这样"扣不扣"看得出来）、能自己合成、
    // 而且真有一条链（拿 stock=0 探一下，免得选到"仓库里早就够了、计划 0 步"的东西）
    const queries = process.argv.slice(2).length
        ? process.argv.slice(2)
        : ['电路板', '机器', '外壳', '齿轮', '马达', '泵', '电路'];
    let target = null;
    let count = 0;
    for (const q of queries) {
        const probe = await api('/api/search?q=' + encodeURIComponent(q) + '&limit=40');
        for (const item of probe.items || []) {
            if (!item.craftable) continue;
            if (!(item.stock > 0 && item.stock <= 16)) continue;
            const plan = await api('/api/plan?id=' + item.id + '&count=64&stock=0');
            if ((plan.steps || []).length > 1) {
                target = item;
                // 要的数量比库存多几个：少了看不出差别，多了计划太大
                count = Math.max(2, item.stock + 8);
                break;
            }
        }
        if (target) break;
    }
    if (!target) {
        console.log('没找到「仓库里有 1~16 个、又能自己合成」的目标，先往仓库里放点东西再跑');
        process.exit(1);
    }
    console.log('目标：' + target.name + ' #' + target.id + '（仓库里有 ' + target.stock + '，要合成 ' + count + '）');

    const COUNT = count;
    const base = '/api/plan?id=' + target.id + '&count=' + COUNT;
    const withStock = await api(base + '&stock=1&targetstock=1');
    const targetOnly = await api(base + '&stock=1&targetstock=0');
    const fromZero = await api(base + '&stock=0');

    const wCrafts = targetCrafts(withStock, target.id);
    const tCrafts = targetCrafts(targetOnly, target.id);
    const zCrafts = targetCrafts(fromZero, target.id);

    check('老行为不变：不带参数时目标仍按库存扣',
        wCrafts > 0 && wCrafts < zCrafts,
        '按库存=' + wCrafts + ' 从零=' + zCrafts + '（仓库 ' + target.stock + '，要 ' + COUNT + '）');
    check('targetstock=0：目标做满，不被仓库里的存量削减',
        tCrafts === zCrafts && tCrafts > 0,
        '目标不扣=' + tCrafts + ' 从零=' + zCrafts);
    check('targetstock=0 时「已有」里没有目标自己',
        !(targetOnly.materials || []).some((m) => m.id === target.id && m.have > 0),
        '材料表条数=' + (targetOnly.materials || []).length);

    const others = otherCrafts(targetOnly, target.id);
    const zeroOthers = otherCrafts(fromZero, target.id);
    let stillUsingStock = 0;
    for (const key of Object.keys(others)) {
        if (zeroOthers[key] !== undefined && others[key] < zeroOthers[key]) stillUsingStock++;
    }
    check('中间产物照旧吃库存（有比"从零算"做得更少的）',
        stillUsingStock > 0 || stockUsedCount(targetOnly) > 0,
        '做得更少的中间产物=' + stillUsingStock + ' 条，已有>0 的条目=' + stockUsedCount(targetOnly));

    // 换个说法再验一遍：目标不扣库存这一份里，中间产物不能全都跟"从零算"一模一样
    const identical = Object.keys(others).length > 0
        && Object.keys(others).every((k) => zeroOthers[k] === others[k]);
    check('中间产物没有被"目标不扣"顺带改成从零算', !identical,
        identical ? '所有中间产物都和从零算一样（可疑）' : '至少有一处不同');

    console.log('\n' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})().catch((err) => {
    console.error('跑不下去了：' + err.message);
    process.exit(2);
});
