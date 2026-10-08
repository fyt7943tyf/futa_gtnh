/*
 * 流体身份的唯一性实测（纯 HTTP，不需要浏览器）。
 *
 * 背景（这条规律是被反复踩出来的）：同一种流体在 GTNH 里有两套显示物品 ——
 * GT 的 gregtech:gt.GregTech_FluidDisplay（NBT 里带 mFluidMaterialName / mFluidDisplayAmount）
 * 和 NEI 自己的 NotEnoughItems:neiFluidDisplay（流体写在 damage 上，NBT 是空的）。
 * 它们在玩家眼里是同一个东西，以前却是两个物品编号：
 * 搜索里出现两条同名条目、配方只在其中一条上，而玩家的配方选择是按编号存的 ——
 * 于是「我明明给它选了合成方式，规划还是把它当原料」「还缺熔融焊锡」。
 *
 * 断言：
 *   1. 这个流体名在目录里找得到（同名条目至少一条）；
 *   2. 同名的几条**指向同一种流体**：库存数字必须一样（名字匹配到的就是那份流体）；
 *   3. 能规划的那一条在计划里是可用的 —— 认成流体、能展开出步骤，
 *      或者**已经被库存覆盖**（那时计划本来就是 0 步：不需要再做，这不是坏事）；
 *   4. 老计划里存的「重复条目」编号，规划结果要和正式那条**一模一样**
 *      （接口层会把编号指回正式那条，见 WebStore.canonicalId）。
 *
 * 用法：
 *   node fluid-identity.js                          # 用默认的几个流体名
 *   node fluid-identity.js 熔融焊锡 熔融坎塔尔合金      # 指定名字
 *   node fluid-identity.js --alias 43173 37052      # 额外核对「重复编号指回正式编号」
 */
const BASE = process.env.BASE || 'http://127.0.0.1:8765';

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

/**
 * 按名字找同名条目。
 *
 * <p>
 * 不能只看前 40 条：搜索是「名字 + 拼音」的子串匹配，搜「水」会命中一千多条
 * （紫水晶、水桶、粗水之魔晶矿石…），精确同名的那条可能排在很后面。
 * 所以翻几页找，找到就停。
 */
async function findExact(name, pages) {
    const hits = [];
    for (let page = 0; page < (pages || 5); page++) {
        const search = await api(
            '/api/search?q=' + encodeURIComponent(name) + '&limit=200&offset=' + (page * 200));
        const items = search.items || [];
        for (const it of items) {
            if (it.name === name) hits.push(it);
        }
        if (items.length < 200) break;
    }
    return hits;
}

const DEFAULTS = ['熔融焊锡', '熔融坎塔尔合金', '熔融镍铬合金', '熔融白铜', '水', '岩浆', '冷却液'];

/** 只比较「这个物品本身」的字段：编号规整之后应当完全一致。 */
function shape(plan) {
    const target = plan.target || {};
    const materials = (plan.materials || []).map((m) => m.id + ':' + m.need + ':' + m.fluid + ':' + m.missing);
    return JSON.stringify({
        id: target.id,
        count: target.count,
        steps: (plan.steps || []).length,
        materials: materials
    });
}

(async () => {
    const argv = process.argv.slice(2);
    let aliasPair = null;
    if (argv[0] === '--alias') {
        aliasPair = { dup: Number(argv[1]), canonical: Number(argv[2]) };
        argv.splice(0, 3);
    }
    const names = argv.length ? argv : DEFAULTS;

    console.log('对象：' + BASE);
    for (const name of names) {
        const exact = await findExact(name);
        check(
            name + ' 在目录里找得到',
            exact.length >= 1,
            '命中 ' + exact.length + ' 条：' + exact.map((i) => i.id + '/' + i.mod).join(', ')
        );
        if (exact.length === 0) continue;

        // 同名的几条必须是同一种流体：它们都要按名字匹配到那份库存，数字就得一样
        const stocks = exact.map((i) => i.stock);
        const sameStock = stocks.every((s) => s === stocks[0]);
        check(name + ' 的同名条目指向同一种流体（库存数字一致）', sameStock,
            'id/库存：' + exact.map((i) => i.id + '/' + i.stock).join(', '));

        // 挑「能规划的那一条」来验可用性（其余是别的模组里同名的真物品，craftable=false）
        const usableItem = exact.find((i) => i.craftable) || exact[0];
        const id = usableItem.id;
        const plan = await api('/api/plan?id=' + id + '&count=1');
        const mine = (plan.materials || []).find((m) => m.id === id);
        const covered = !!mine === false && (plan.steps || []).length === 0 && usableItem.stock > 0;
        const usable = plan.ok === true
            && ((mine && mine.fluid === true) || (plan.steps || []).length > 0 || covered);
        check(
            name + ' 的编号在计划里是可用的（认成流体、能展开，或已被库存覆盖）',
            usable,
            'id=' + id + ' steps=' + (plan.steps || []).length
                + ' fluid=' + (mine ? mine.fluid : '—') + ' 库存=' + usableItem.stock
                + (covered ? '（够用，所以不需要做）' : '')
        );
    }

    if (aliasPair) {
        const dup = await api('/api/plan?id=' + aliasPair.dup + '&count=1');
        const canonical = await api('/api/plan?id=' + aliasPair.canonical + '&count=1');
        check(
            '重复编号 ' + aliasPair.dup + ' 指回正式编号 ' + aliasPair.canonical,
            shape(dup) === shape(canonical),
            'dup=' + shape(dup) + '  canonical=' + shape(canonical)
        );
    }

    // ── 不许「借」别的流体的库存 ───────────────────────────────────────────
    //
    // 玩家报过：用流体固化器做不锈钢转子，页面说「熔融不锈钢有库存 16000」，
    // 而仓库里一点熔融不锈钢都没有 —— 那 16000 是**稀硫酸**的。
    // 成因：流体的身份曾经用 damage 猜（熔融不锈钢的显示物品 @619 → 猜成稀硫酸），
    // 或者拿缓存回读的栈现读显示名（同样猜错），再把那个名字/注册名拿去查库存。
    //
    // 不变式：仓库的流体表里没有这个名字，就不许报出库存。
    const table = await api('/api/stock?limit=200');
    const tableNames = new Set((table.fluids || []).map((f) => String(f.name)));
    const candidates = ['熔融不锈钢', '熔融钛', '熔融钨', '熔融铱', '熔融锇'];
    let checkedFluids = 0;
    for (const name of candidates) {
        const search = await api('/api/search?q=' + encodeURIComponent(name) + '&limit=200');
        const hit = (search.items || []).find((it) => it.name === name);
        if (!hit) continue;
        const probe = await api('/api/stock?id=' + hit.id);
        const p = probe.probe || {};
        if (tableNames.has(String(p.name))) continue;   // 仓库里真有这种流体：这条不适用
        checkedFluids++;
        check(
            name + ' 不在仓库里时库存必须是 0（不能借别的流体的数字）',
            p.stock === 0,
            'stock=' + p.stock + '（现读名=' + p.freshName + '，damage 猜出来=' + p.fluidRegistryGuess + '）'
        );
    }
    check('这套「不许借库存」的检查确实跑了', checkedFluids > 0, '检查了 ' + checkedFluids + ' 种');

    console.log('\n' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})().catch((err) => {
    console.error('跑不下去了：' + err.message);
    process.exit(2);
});
