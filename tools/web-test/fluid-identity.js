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
 *   1. 每个流体名在搜索里只有一条（重复条目不该单独出现）；
 *   2. 那一条在计划里被认成流体，或者本身能展开出步骤；
 *   3. 老计划里存的「重复条目」编号，规划结果要和正式那条**一模一样**
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
        const search = await api('/api/search?q=' + encodeURIComponent(name) + '&limit=40');
        const exact = (search.items || []).filter((it) => it.name === name);
        check(
            name + ' 只有一条同名条目',
            exact.length === 1,
            '命中 ' + exact.length + ' 条：' + exact.map((i) => i.id + '/' + i.mod).join(', ')
        );
        if (exact.length !== 1) continue;

        const id = exact[0].id;
        const plan = await api('/api/plan?id=' + id + '&count=1');
        const mine = (plan.materials || []).find((m) => m.id === id);
        const usable = plan.ok === true && ((mine && mine.fluid === true) || (plan.steps || []).length > 0);
        check(
            name + ' 的编号在计划里是可用的（认成流体，或能展开出步骤）',
            usable,
            'id=' + id + ' steps=' + (plan.steps || []).length + ' fluid=' + (mine ? mine.fluid : '—')
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

    console.log('\n' + pass + ' 通过 / ' + fail + ' 失败');
    process.exit(fail === 0 ? 0 : 1);
})().catch((err) => {
    console.error('跑不下去了：' + err.message);
    process.exit(2);
});
