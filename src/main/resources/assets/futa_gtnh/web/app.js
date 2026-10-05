/* ==========================================================================
 * 合成向导 - 前端逻辑（原生 JS，无框架、无外部依赖）
 *
 * 后端接口契约（同源，UTF-8 JSON）：
 *   GET /api/status
 *   GET /api/search?q=&limit=&offset=
 *   GET /api/item?id=
 *   GET /api/plan?id=&count=&stock=&choices=&raw=
 *   GET /api/icon/<id>.png
 *
 * 兼容性：ES2017 以内（不使用可选链 / 顶层 await），fetch + async/await。
 * ========================================================================== */
(function () {
    'use strict';

    /* ------------------------------------------------------------ 常量与状态 */

    var STATUS_POLL_MS = 1500;   // 索引建立中时轮询 /api/status 的间隔
    var SEARCH_DEBOUNCE_MS = 250;
    var TOAST_MS = 3800;
    var OPEN_STEPS = 3;          // 步骤列表默认展开前几步

    var LS_CHOICES = 'futa_gtnh.choices';
    var LS_RAW = 'futa_gtnh.raw';
    var LS_DONE = 'futa_gtnh.done';
    var LS_CATALYST = 'futa_gtnh.catalyst';
    var LS_CONSUMABLE = 'futa_gtnh.consumable';
    var LS_IGNORE_STOCK = 'futa_gtnh.ignorestock';
    var LS_BASKET = 'futa_gtnh.basket';

    /** 一组多少个（MC 的栈上限）。数量换算成「几组零几个」时用。 */
    var STACK_SIZE = 64;

    var VIEW = document.getElementById('view');

    var state = {
        viewToken: 0,        // 页面渲染令牌：异步返回时令牌不一致就丢弃旧结果
        status: null,        // 最近一次 /api/status 的结果
        pollTimer: null,
        toastTimer: null,
        debounceTimer: null,
        lastBuildPct: null,  // 上一次提示过的索引进度（0-100），避免刷屏
        lastOfflineToast: 0,
        pending: null,       // 索引未建好时暂存的操作，建好后自动继续
        search: { q: '', total: 0, items: [], seq: 0 },  // 搜索页状态
        item: { id: 0, count: 64 },                      // 物品页：数量
        plan: { id: 0, count: 64, plan: null, open: {}, rawMode: false, active: false },
        basketNames: {},                                 // 计划清单里的物品名（拿到接口数据后补上）
        modalOpener: null,
        modalOrigin: ''      // 弹层是从哪个页面打开的，决定关闭后要不要重新规划
    };

    /* ------------------------------------------------------------ 小工具函数 */

    function byId(id) {
        return document.getElementById(id);
    }

    function clear(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) {
            node.className = cls;
        }
        if (text !== undefined && text !== null) {
            node.textContent = String(text);
        }
        return node;
    }

    function btn(cls, text) {
        var b = el('button', cls, text);
        b.type = 'button';
        return b;
    }

    function num(v, fallback) {
        var n = Number(v);
        return isFinite(n) ? n : fallback;
    }

    function clampInt(v, min, max, fallback) {
        var n = Math.round(Number(v));
        if (!isFinite(n)) {
            return fallback;
        }
        if (n < min) {
            return min;
        }
        if (n > max) {
            return max;
        }
        return n;
    }

    /**
     * 数量怎么显示。
     *
     * <p>
     * 超过一组（64）的，把「几组零几个」也附上：4570 这个数字本身说明不了什么，
     * 但「71×64+26」一眼就知道要腾出多少格背包、要不要带潜影盒。
     * 原数字保留 —— 判断「我够不够」还是得看它。
     */
    function countText(n) {
        if (n === null || n === undefined) {
            return '-';
        }
        var value = Number(n);
        if (!isFinite(value) || value <= STACK_SIZE) {
            return String(n);
        }
        var stacks = Math.floor(value / STACK_SIZE);
        var rest = value - stacks * STACK_SIZE;
        return value + '（' + stacks + '×' + STACK_SIZE + (rest > 0 ? '+' + rest : '') + '）';
    }

    /**
     * 槽位角标里的数量：格子只有三十几像素，五位数塞不下。
     *
     * <p>
     * 一万以下照原样写（看得准）；再大就换成「万」，因为这种量级玩家关心的是
     * 「大概多少」，精确值在提示和信息条里都能看到。
     */
    function compactCount(n) {
        var value = Number(n);
        if (!isFinite(value)) return String(n);
        if (value < 10000) return String(value);
        var wan = value / 10000;
        return (wan >= 100 ? Math.round(wan) : Math.round(wan * 10) / 10) + '万';
    }

    /**
     * GT 机器的电压等级表（EU/t）。
     *
     * <p>
     * 玩家排产时想知道的不是「24 EU/t」这个数字，而是「这要几级机器、够不够电」。
     */
    var VOLTAGE_TIERS = [
        [8, 'ULV'], [32, 'LV'], [128, 'MV'], [512, 'HV'], [2048, 'EV'], [8192, 'IV'], [32768, 'LuV'],
        [131072, 'ZPM'], [524288, 'UV'], [2097152, 'UHV'], [8388608, 'UEV'], [33554432, 'UIV'],
        [134217728, 'UMV'], [536870912, 'UXV']
    ];

    function tierOf(euPerTick) {
        var value = Math.abs(num(euPerTick, 0));
        var name = '';
        for (var i = 0; i < VOLTAGE_TIERS.length; i++) {
            if (value <= VOLTAGE_TIERS[i][0]) return VOLTAGE_TIERS[i][1];
            name = VOLTAGE_TIERS[i][1];
        }
        return name;
    }

    /**
     * 「耗电 / 耗时」那一行文字，GT 配方才有。
     *
     * <p>
     * 配方表里光有材料排不出先后：玩家还得知道这一步要几级机器、吃多少电、跑多久
     * （NEI 里也是这么显示的）。非 GT 配方（工作台、熔炉之类）没有这些数，返回空串。
     */
    function powerText(recipe, times) {
        if (!recipe) return '';
        var eu = num(recipe.euPerTick, 0);
        var ticks = num(recipe.durationTicks, 0);
        if (eu === 0 && ticks === 0) return '';

        var parts = [];
        if (eu !== 0) {
            // 负数 = 发电配方（发电机产出 EU），别写成「耗电 -32」
            parts.push((eu < 0 ? '发电 ' : '耗电 ') + Math.abs(eu) + ' EU/t');
            parts.push(tierOf(eu));
        } else {
            parts.push('不耗电');
        }
        if (ticks > 0) {
            parts.push((ticks / 20) + ' 秒');
            var total = Math.abs(eu) * ticks;
            if (total > 0) {
                parts.push('共 ' + Math.round(total) + ' EU');
            }
        }
        var repeat = Math.max(1, num(times, 1));
        if (repeat > 1 && ticks > 0) {
            parts.push('做 ' + repeat + ' 次共 ' + ((ticks * repeat) / 20) + ' 秒');
        }
        return parts.join(' · ');
    }

    /**
     * 「还可以用」的候选列表文字（矿物词典那种「任意一种都行」）。
     *
     * <p>
     * 只在候选不止一种时才有意义。玩家手里的替代品是算数的（库存按整组统计），
     * 所以要让他看见到底哪几种能用。
     */
    function alternativesText(entry) {
        var alts = entry && Array.isArray(entry.alts) ? entry.alts : [];
        if (alts.length <= 1) return '';
        var names = [];
        for (var i = 0; i < alts.length && i < 6; i++) {
            names.push(alts[i].name ? String(alts[i].name) : ('#' + alts[i].id));
        }
        var more = alts.length > names.length ? ' 等 ' + alts.length + ' 种' : '';
        return '也可以用：' + names.join(' / ') + more;
    }

    /**
     * 电压/耗时的短写法，给步骤标题用（不展开也看得见）。
     *
     * <p>
     * 展开后的完整版在 {@link powerText} 里（还带总 EU 和总时长）。
     * 这里只留「几级机器、吃多少电、跑多久」——排产时最先要判断的就是这三样。
     */
    function powerShort(recipe) {
        if (!recipe) return '';
        var eu = num(recipe.euPerTick, 0);
        var ticks = num(recipe.durationTicks, 0);
        if (eu === 0 && ticks === 0) return '';
        var parts = [];
        if (eu !== 0) {
            parts.push(Math.abs(eu) + ' EU/t');
            parts.push(tierOf(eu));
        }
        if (ticks > 0) {
            parts.push((ticks / 20) + ' 秒');
        }
        return parts.join(' · ');
    }

    function stockText(stock) {
        if (stock === null || stock === undefined) {
            return '库存未知';
        }
        if (stock <= 0) {
            return '库存 0';
        }
        return '库存 ' + stock;
    }

    /* localStorage 可能被禁用（隐私模式 / 内嵌 WebView），全部包一层保护 */

    function lsGet(key) {
        try {
            return window.localStorage.getItem(key) || '';
        } catch (e) {
            return '';
        }
    }

    function lsSet(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (e) {
            /* 忽略：不影响主流程 */
        }
    }

    /* ------------------------------------------------------------ 配方选择 / 原始材料 */

    // choices 存储格式：{"42":"gregtech.nei.GTNEIDefaultHandler~1234"}
    function readChoices() {
        var raw = lsGet(LS_CHOICES);
        var out = {};
        if (!raw) {
            return out;
        }
        try {
            var parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                var keys = Object.keys(parsed);
                for (var i = 0; i < keys.length; i++) {
                    var v = parsed[keys[i]];
                    if (typeof v === 'string' && v) {
                        out[keys[i]] = v;
                    }
                }
            }
        } catch (e) {
            /* 坏数据就当没有 */
        }
        return out;
    }

    function getChoice(itemId) {
        var all = readChoices();
        var rid = all[String(itemId)];
        return typeof rid === 'string' ? rid : '';
    }

    function setChoice(itemId, rid) {
        var all = readChoices();
        if (rid) {
            all[String(itemId)] = String(rid);
        } else {
            delete all[String(itemId)];
        }
        lsSet(LS_CHOICES, JSON.stringify(all));
    }

    /**
     * 给某个物品指定配方，并摘掉「当作原始材料」。
     *
     * <p>
     * 这两个状态是<b>打架</b>的：「当作原始材料」的语义是「别展开它的配方」，
     * 「指定配方」的语义是「就按这条做」。同时存在时前者赢，玩家看到的现象是
     * 「我明明选了配方，怎么还让我自己去准备」——被玩家抓到过一次。
     * 既然选配方这个动作本身就是「我要做它」，那就顺手把 raw 摘掉。
     *
     * @return 之前是不是挂着「当作原始材料」（界面据此多说一句）
     */
    function chooseRecipe(itemId, rid) {
        setChoice(itemId, rid);
        var wasRaw = isRaw(itemId);
        if (wasRaw) {
            toggleRaw(itemId, false);
        }
        return wasRaw;
    }

    // choices 查询参数格式：42:gregtech.foo~12,77:minecraft.crafting~3
    function choicesParam() {
        var all = readChoices();
        var parts = [];
        var keys = Object.keys(all);
        for (var i = 0; i < keys.length; i++) {
            parts.push(keys[i] + ':' + all[keys[i]]);
        }
        return parts.join(',');
    }

    // raw 存储格式：[9,15,16]
    function readRaw() {        var raw = lsGet(LS_RAW);
        var out = [];
        if (!raw) {
            return out;
        }
        try {
            var parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) {
                for (var i = 0; i < parsed.length; i++) {
                    var n = Number(parsed[i]);
                    if (isFinite(n) && out.indexOf(n) < 0) {
                        out.push(n);
                    }
                }
            }
        } catch (e) {
            /* 坏数据就当没有 */
        }
        return out;
    }

    function isRaw(itemId) {
        return readRaw().indexOf(Number(itemId)) >= 0;
    }

    function toggleRaw(itemId, on) {
        var list = readRaw();
        var id = Number(itemId);
        var at = list.indexOf(id);
        if (on && at < 0) {
            list.push(id);
        } else if (!on && at >= 0) {
            list.splice(at, 1);
        }
        lsSet(LS_RAW, JSON.stringify(list));
    }

    // raw 查询参数格式：9,15,16
    function rawParam() {
        return readRaw().join(',');
    }

    /* ------------------------------------------------------------ 计划清单（多目标） */

    /**
     * 「这次要一起做的东西」。
     *
     * <p>
     * 做机器往往是成套的（A 64 个 + B 3 个 + C 1 个），一件一件算的话，
     * 公共的中间产物会被算好几遍，而且看不出哪些活是共用的。
     * 清单存在浏览器本地，规划时整份交给后端一次算完。
     */
    function readBasket() {
        var raw = lsGet(LS_BASKET);
        var out = [];
        if (!raw) return out;
        try {
            var parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) {
                for (var i = 0; i < parsed.length; i++) {
                    var entry = parsed[i];
                    var id = Number(entry && entry.id);
                    var count = Number(entry && entry.count);
                    if (!isFinite(id) || id < 0) continue;
                    if (!isFinite(count) || count < 1) count = 1;
                    out.push({ id: id, count: Math.min(1000000, Math.round(count)) });
                }
            }
        } catch (e) {
            /* 坏数据当没有 */
        }
        return out;
    }

    function writeBasket(list) {
        lsSet(LS_BASKET, JSON.stringify(list || []));
    }

    function addToBasket(itemId, count) {
        var list = readBasket();
        var id = Number(itemId);
        var at = -1;
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === id) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            list[at].count = Math.min(1000000, list[at].count + Math.max(1, Number(count) || 1));
        } else {
            list.push({ id: id, count: Math.max(1, Number(count) || 1) });
        }
        writeBasket(list);
    }

    function removeFromBasket(itemId) {
        var list = readBasket();
        var out = [];
        for (var i = 0; i < list.length; i++) {
            if (list[i].id !== Number(itemId)) out.push(list[i]);
        }
        writeBasket(out);
    }

    function basketParam() {
        var list = readBasket();
        var parts = [];
        for (var i = 0; i < list.length; i++) {
            parts.push(list[i].id + ':' + list[i].count);
        }
        return parts.join(',');
    }

    /* ------------------------------------------------------------ 库存开关 */

    /**
     * 「算不算我手头已有的东西」。
     *
     * <p>
     * 默认算：背包里够了的材料就不用做，计划短、贴近当下。
     * 但玩家常常要问的是另一个问题 ——「我什么都没有的话，一共要准备多少」，
     * 那是提前备料、给别人列清单时的算法。所以留一个开关，别替他决定。
     */
    function ignoreStock() {
        return lsGet(LS_IGNORE_STOCK) === '1';
    }

    function setIgnoreStock(on) {
        lsSet(LS_IGNORE_STOCK, on ? '1' : '0');
    }

    function stockParam() {
        return ignoreStock() ? '0' : '1';
    }

    /* ------------------------------------------------------------ 非消耗品 */

    /**
     * 「这个物品可反复使用，不用按次数备」—— 可编程电路、模具、常驻工具、透镜这些。
     *
     * <p>
     * 默认由后端自动判定（GT 的电路/工具/名字带 Mold、Lens 的），这里只存玩家的<b>推翻</b>：
     * {@code catalyst} 是「这个也算」，{@code consumable} 是「我就要按消耗算」。
     * 两个方向都要有 —— 自动判定一定会有认错的时候，认错了得能改回来。
     */
    function readFlagList(key) {
        var raw = lsGet(key);
        var out = [];
        if (!raw) {
            return out;
        }
        try {
            var parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) {
                for (var i = 0; i < parsed.length; i++) {
                    var n = Number(parsed[i]);
                    if (isFinite(n) && out.indexOf(n) < 0) {
                        out.push(n);
                    }
                }
            }
        } catch (e) {
            /* 坏数据当没有 */
        }
        return out;
    }

    function flagHas(list, itemId) {
        return readFlagList(list)
            .indexOf(Number(itemId)) >= 0;
    }

    function setFlag(list, itemId, on) {
        var all = readFlagList(list);
        var id = Number(itemId);
        var at = all.indexOf(id);
        if (on && at < 0) {
            all.push(id);
        } else if (!on && at >= 0) {
            all.splice(at, 1);
        }
        lsSet(list, JSON.stringify(all));
    }

    function isForcedCatalyst(itemId) {
        return flagHas(LS_CATALYST, itemId);
    }

    function isForcedConsumable(itemId) {
        return flagHas(LS_CONSUMABLE, itemId);
    }

    function catalystParam() {
        return readFlagList(LS_CATALYST)
            .join(',');
    }

    function consumableParam() {
        return readFlagList(LS_CONSUMABLE)
            .join(',');
    }

    /* ------------------------------------------------------------ 施工进度 */

    /**
     * 「哪几步已经做完了」。
     *
     * <p>
     * 按「目标物品 + 数量」分开存：同一份计划重新打开时进度还在，
     * 换成别的目标或改数量就是另一份活，不该混在一起。
     *
     * <p>
     * 记的是<b>配方编号</b>（rid）而不是步骤序号：改了某个物品的配方之后，
     * 步骤会重新排号，按序号记的话勾会串位。
     */
    function doneKey(itemId, count) {
        return String(itemId) + '@' + String(count);
    }

    function readDone() {
        var raw = lsGet(LS_DONE);
        var out = {};
        if (!raw) {
            return out;
        }
        try {
            var parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                return parsed;
            }
        } catch (e) {
            /* 坏数据当没有 */
        }
        return out;
    }

    function doneSet(itemId, count) {
        var all = readDone();
        var list = all[doneKey(itemId, count)];
        return Array.isArray(list) ? list.map(String) : [];
    }

    function isDone(itemId, count, rid) {
        return doneSet(itemId, count)
            .indexOf(String(rid)) >= 0;
    }

    function setDone(itemId, count, rid, on) {
        var all = readDone();
        var key = doneKey(itemId, count);
        var list = Array.isArray(all[key]) ? all[key].map(String) : [];
        var value = String(rid);
        var at = list.indexOf(value);
        if (on && at < 0) {
            list.push(value);
        } else if (!on && at >= 0) {
            list.splice(at, 1);
        }
        all[key] = list;
        lsSet(LS_DONE, JSON.stringify(all));
    }

    function clearDone(itemId, count) {
        var all = readDone();
        delete all[doneKey(itemId, count)];
        lsSet(LS_DONE, JSON.stringify(all));
    }

    /**
     * 按当前进度算「现在能开工哪几步」。
     *
     * <p>
     * 规则很朴素，也是玩家在机器前的真实处境：这一步的所有前置步骤都做完 + 自己还没做完
     * = 现在就能做。它们之间没有依赖，所以可以同时开工（GT 里就是几台机器一起跑）。
     */
    function readySteps(plan) {
        var steps = plan && Array.isArray(plan.steps) ? plan.steps : [];
        var done = {};
        var list = doneSet(plan && plan.target ? plan.target.id : plan.id, plan && plan.target ? plan.target.count : 0);
        for (var d = 0; d < list.length; d++) {
            done[list[d]] = true;
        }
        var out = [];
        for (var i = 0; i < steps.length; i++) {
            var step = steps[i];
            var rid = String(step.rid);
            if (done[rid]) continue;
            var needs = Array.isArray(step.needs) ? step.needs : [];
            var blocked = false;
            for (var k = 0; k < needs.length; k++) {
                var need = steps[needs[k] - 1];
                if (need && !done[String(need.rid)]) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) out.push(step);
        }
        return out;
    }

    /* ------------------------------------------------------------ 物品信息条 */

    /**
     * 点一下格子，就地告诉玩家「这是什么」。
     *
     * <p>
     * 手机上没有 hover，而格子里的图标只有 36 像素 —— 光看图经常认不出是哪种板、哪个电路。
     * 直接点开配方弹层代价太大（整屏切换、还丢了配方卡片的上下文），所以分两步：
     * 第一次点只弹一条信息（名称、数量、库存、模组），要深究再点信息条上的「看配方」。
     *
     * <p>
     * 信息条挂在页面底部（拇指够得到），不带遮罩、点别处就消失 —— 不打断当前的阅读。
     */
    function showItemInfo(item, count, options) {
        var bar = byId('item-info');
        if (!bar) return;

        // 提示条和信息条都贴在底部，同时出现会叠在一起（截图里正好压住了物品名）。
        // 两者都是「看一眼就走」的东西，留更相关的那个：信息条。
        var toastBox = byId('toast');
        if (toastBox) {
            toastBox.hidden = true;
        }

        var opts = options || {};
        clear(bar);
        bar.className = 'item-info';

        var row = el('div', 'item-info-main');
        row.appendChild(makeIcon(item, 'icon-40'));

        var info = el('div', 'item-info-text');
        var nameLine = el('div', 'item-info-name');
        nameLine.appendChild(document.createTextNode(item && item.name ? String(item.name) : '未知物品'));
        if (num(count, 0) > 0) {
            // 和格子的提示一致：数量超过一组就附上「几组零几个」
            nameLine.appendChild(el('span', 'item-info-count', ' ×' + countText(count)));
        }
        info.appendChild(nameLine);

        var stat = el('div', 'material-stat');
        if (item && item.mod) {
            stat.appendChild(el('span', 'dim', String(item.mod)));
        }
        if (item && item.stock !== undefined && item.stock !== null) {
            stat.appendChild(el('span', item.stock > 0 ? 'have' : 'dim',
                '库存 ' + countText(item.stock)));
        }
        if (opts.note) {
            stat.appendChild(el('span', 'dim', String(opts.note)));
        }
        info.appendChild(stat);
        row.appendChild(info);

        var close = btn('btn btn-ghost btn-small', '关闭');
        close.setAttribute('aria-label', '收起物品信息');
        close.addEventListener('click', function () {
            hideItemInfo();
        });
        row.appendChild(close);
        bar.appendChild(row);

        // 想深究再进配方弹层 —— 识别一个东西不该付出整屏切换的代价
        if (item && item.id !== undefined && item.id !== null) {
            var act = el('div', 'item-info-actions');
            var recipes = btn('btn btn-small btn-primary', '看它的配方');
            recipes.addEventListener('click', function () {
                hideItemInfo();
                openRecipeModal({ id: item.id, name: item.name });
            });
            act.appendChild(recipes);
            if (opts.extraAction) {
                act.appendChild(opts.extraAction);
            }
            bar.appendChild(act);
        }

        bar.hidden = false;
    }

    function hideItemInfo() {
        var bar = byId('item-info');
        if (bar) {
            bar.hidden = true;
            clear(bar);
        }
    }

    /* ------------------------------------------------------------ 立刻出现的悬停提示 */

    /**
     * 接管所有 {@code title}，换成自己画的浮层。
     *
     * <p>
     * 浏览器对 title 有大约一秒的延迟（手机上干脆不显示），而格子只有三十几像素、
     * 里面的信息又关键 —— 等一秒才出提示，手感上就是「hover 很慢」。
     * 这里是全局委托：鼠标一进就把 title 挪到 data-tip（顺便让原生提示不再有机会冒出来），
     * 浮层立刻出现、位置跟着指针走。
     */
    function installInstantTips() {
        var box = byId('tip');
        if (!box) return;

        var hide = function () {
            box.hidden = true;
            box.textContent = '';
        };

        var place = function (x, y) {
            var pad = 14;
            var width = box.offsetWidth;
            var height = box.offsetHeight;
            var left = x + pad;
            var top = y + pad;
            if (left + width > window.innerWidth - 4) left = Math.max(4, x - width - pad);
            if (top + height > window.innerHeight - 4) top = Math.max(4, y - height - pad);
            box.style.left = left + 'px';
            box.style.top = top + 'px';
        };

        document.addEventListener('mouseover', function (e) {
            // ★ 选择器必须同时认 title 和 data-tip。
            //
            // 第一次悬停会把 title 挪成 data-tip（为了掐掉原生的一秒延迟提示），
            // 只认 [title] 的话，第二次悬停就找不到它了 —— 表现正是「显示一次，
            // 第二次就不显示了」。这是被玩家报上来的 bug。
            var node = e.target && e.target.closest ? e.target.closest('[title], [data-tip]') : null;
            if (!node) {
                hide();
                return;
            }
            // 有 title 就取一次并摘掉：留着的话浏览器迟一秒又会弹一个出来
            var text = node.getAttribute('title');
            if (text) {
                node.setAttribute('data-tip', text);
                node.removeAttribute('title');
            }
            var shown = node.getAttribute('data-tip') || '';
            if (!shown) {
                hide();
                return;
            }
            box.textContent = shown;
            box.hidden = false;
            place(e.clientX, e.clientY);
        });

        document.addEventListener('mousemove', function (e) {
            if (!box.hidden) place(e.clientX, e.clientY);
        });
        document.addEventListener('mouseout', hide);
        // 页面一滚，浮层就悬在半空了，收起来更干净
        document.addEventListener('scroll', hide, true);
    }

    /* ------------------------------------------------------------ 提示条（不用 alert） */

    function toast(message, kind) {
        var box = byId('toast');
        if (!box || !message) {
            return;
        }
        // 和信息条互斥：都贴底，同屏会叠（见 showItemInfo）
        hideItemInfo();
        clear(box);
        box.appendChild(document.createTextNode(String(message) + '　'));
        var close = btn('btn btn-ghost btn-small', '知道了');
        close.addEventListener('click', function () {
            box.hidden = true;
        });
        box.appendChild(close);
        box.className = 'toast' + (kind === 'error' ? ' is-error' : (kind === 'ok' ? ' is-ok' : ''));
        box.hidden = false;
        if (state.toastTimer) {
            window.clearTimeout(state.toastTimer);
        }
        state.toastTimer = window.setTimeout(function () {
            box.hidden = true;
        }, TOAST_MS);
    }

    /* ------------------------------------------------------------ 接口层 */

    // 后端在索引未建好时返回 {"ok":false,"error":"索引尚未建立","building":true,"progress":x}
    function IndexBuildingError(message, progress) {
        this.name = 'IndexBuildingError';
        this.message = message || '索引尚未建立';
        this.progress = typeof progress === 'number' ? progress : null;
    }
    IndexBuildingError.prototype = Object.create(Error.prototype);

    function fetchJson(url) {
        return window.fetch(url, {
            method: 'GET',
            headers: { Accept: 'application/json' },
            credentials: 'same-origin'
        }).then(function (res) {
            // 服务端设了访问口令时，未授权的请求会返回 401 + 一张 HTML 提示页
            if (res.status === 401 || res.status === 403) {
                throw new Error('需要访问口令：请用游戏里给出的完整地址重新打开（形如 http://电脑IP:端口/?k=口令）。');
            }
            var type = res.headers && res.headers.get ? String(res.headers.get('content-type') || '') : '';
            if (type && type.indexOf('json') < 0) {
                throw new Error('服务返回的不是配方数据（HTTP ' + res.status + '），可能服务还没准备好。');
            }
            return res.text().then(function (text) {
                var data = null;
                if (text) {
                    try {
                        data = JSON.parse(text);
                    } catch (e) {
                        throw new Error('服务器返回了无法解析的数据（HTTP ' + res.status + '）');
                    }
                }
                if (data && data.building === true && data.ok !== true) {
                    throw new IndexBuildingError(data.error, num(data.progress, null));
                }
                if (!data || typeof data !== 'object') {
                    throw new Error('服务器没有返回数据（HTTP ' + res.status + '）');
                }
                if (data.ok !== true) {
                    throw new Error(data.error ? String(data.error) : ('请求失败（HTTP ' + res.status + '）'));
                }
                return data;
            });
        }, function () {
            throw new Error('连接不上游戏内服务，请确认游戏仍在运行且与手机在同一网络。');
        });
    }

    function apiStatus() {
        return fetchJson('/api/status');
    }

    function apiSearch(q, limit, offset) {
        var url = '/api/search?q=' + encodeURIComponent(q ? q : '') +
            '&limit=' + encodeURIComponent(String(limit === undefined ? 40 : limit)) +
            '&offset=' + encodeURIComponent(String(offset === undefined ? 0 : offset));
        return fetchJson(url);
    }

    function apiItem(id) {
        return fetchJson('/api/item?id=' + encodeURIComponent(String(id)));
    }

    function apiPlan(id, count, stock, choices, raw) {
        var url = '/api/plan?id=' + encodeURIComponent(String(id)) +
            '&count=' + encodeURIComponent(String(count)) +
            '&stock=' + encodeURIComponent(String(stock));
        // 一起做的清单 = 当前这一件 + 清单里的其它东西（「合成 A 64 个 + B 3 个」）。
        // 后端只认 targets，所以当前目标必须一起塞进去，否则它会被清单顶掉
        var targets = [];
        if (id) {
            targets.push(encodeURIComponent(String(id)) + ':' + encodeURIComponent(String(count)));
        }
        var list = readBasket();
        for (var i = 0; i < list.length; i++) {
            if (id && list[i].id === Number(id)) continue;
            targets.push(list[i].id + ':' + list[i].count);
        }
        if (targets.length > 1) {
            url += '&targets=' + targets.join(',');
        }
        if (choices) {
            url += '&choices=' + encodeURIComponent(choices);
        }
        if (raw) {
            url += '&raw=' + encodeURIComponent(raw);
        }
        // 非消耗品的两个方向：补标的 / 推翻自动判定的
        var asCatalyst = catalystParam();
        if (asCatalyst) {
            url += '&catalyst=' + encodeURIComponent(asCatalyst);
        }
        var asConsumable = consumableParam();
        if (asConsumable) {
            url += '&consumable=' + encodeURIComponent(asConsumable);
        }
        return fetchJson(url);
    }

    /* ------------------------------------------------------------ 图标（含 404 兜底） */

    function hashString(s) {
        var h = 0;
        var str = String(s);
        for (var i = 0; i < str.length; i++) {
            h = (h * 31 + str.charCodeAt(i)) % 1000000007;
        }
        return h;
    }

    // 同一个 id 永远得到同一个颜色
    function placeholderColor(id) {
        var hue = hashString('id:' + id) % 360;
        return 'hsl(' + hue + ', 42%, 38%)';
    }

    function firstChar(name, id) {
        var s = name ? String(name).trim() : '';
        if (!s) {
            return String(id);
        }
        var c = s.charAt(0);
        if (/[\uD800-\uDBFF]/.test(c) && s.length > 1) {
            return s.substring(0, 2);
        }
        return c;
    }

    function pickIcon(item) {
        if (!item) {
            return '';
        }
        var icon = item.icon;
        if (typeof icon === 'string' && icon) {
            return icon;
        }
        if (item.id !== undefined && item.id !== null) {
            return '/api/icon/' + String(item.id) + '.png';
        }
        return '';
    }

    function makePlaceholder(item) {
        var name = item ? item.name : '';
        var id = item && item.id !== undefined ? item.id : name;
        var ph = el('span', 'icon-ph', firstChar(name, id));
        ph.style.background = placeholderColor(id === undefined ? '?' : id);
        ph.title = name ? String(name) : '';
        return ph;
    }

    /**
     * 生成物品图标。icon 可能 404 / 加载失败，此时换成「首字 + 稳定颜色」的文字块。
     * extraClass 用来控制尺寸（icon-lg / icon-40 / icon-22）。
     */
    function makeIcon(item, extraClass) {
        var wrap = el('span', 'icon' + (extraClass ? ' ' + extraClass : ''));
        var url = pickIcon(item);
        if (!url) {
            wrap.appendChild(makePlaceholder(item));
            return wrap;
        }
        var img = document.createElement('img');
        img.alt = '';
        img.loading = 'lazy';
        img.decoding = 'async';
        // 图标失败只兜底一次，避免无限重试
        img.addEventListener('error', function () {
            if (img.parentNode) {
                img.parentNode.replaceChild(makePlaceholder(item), img);
            }
        }, { once: true });
        img.src = url;
        wrap.appendChild(img);
        return wrap;
    }

    /* ------------------------------------------------------------ 物品图标按钮 */

    /**
     * 物品列表左侧那个图标：能点（打开这个物品的配方选择）、有提示、
     * 并且一眼看出「这个物品我手动指定过配方」。
     *
     * <p>
     * 为什么不是直接给 makeIcon 加个 title 就完事：玩家看合成步骤时最常想问的两件事是
     * 「这东西我能不能换个配方做」和「这一步用的是不是我选的那个配方」——
     * 图标是列表里最大的点击目标，把它做成入口最顺手。
     *
     * <p>
     * 手机上没有真正的 hover：桌面端靠 title 和 :hover 高亮，触屏端点一下就进弹层
     *（弹层里有名称、库存和全部配方）。所以两者都要有，不能只做一个。
     */
    function makeItemIconButton(item, sizeClass, sub) {
        var id = item && item.id !== undefined && item.id !== null ? item.id : null;
        var name = item && item.name ? String(item.name) : '未知物品';
        var chosen = id === null ? '' : getChoice(id);

        var wrap = el('span', 'icon-btn' + (chosen ? ' is-chosen' : ''));
        wrap.setAttribute('role', 'button');
        wrap.tabIndex = 0;
        wrap.appendChild(makeIcon(item, sizeClass));

        var tip = name + (sub ? '（' + sub + '）' : '');
        if (chosen) {
            tip += '\n已手动指定配方，点一下可以换';
        }
        if (id !== null) {
            tip += '\n点一下选择这个物品的配方';
        }
        wrap.title = tip;
        wrap.setAttribute('aria-label', name + (chosen ? '（已手动指定配方）' : '') + '，点击选择配方');

        if (id !== null) {
            var open = function (e) {
                // 步骤卡片整个头部也是个按钮，这里必须掐断，否则会连带折叠/展开
                e.preventDefault();
                e.stopPropagation();
                openRecipeModal({ id: id, name: name });
            };
            wrap.addEventListener('click', open);
            wrap.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13 || e.keyCode === 32) {
                    open(e);
                }
            });
        }
        return wrap;
    }

    /** 「已选配方」小标签：图标上的勾之外再给一处文字，扫一眼就明白。 */
    function makeChosenTag() {
        return el('span', 'tag-chosen', '已选配方');
    }

    /**
     * 就地刷新网格槽位上的「已选配方」勾。
     *
     * <p>
     * 不整页重绘是有原因的：物品页上「我要合成 N 个」是用户输入的，
     * 重绘会把输入清掉。所以选了配方之后就只动这几个勾。
     */
    function refreshChosenMarks() {
        var cells = document.querySelectorAll('.slot[data-item]');
        for (var i = 0; i < cells.length; i++) {
            var cell = cells[i];
            var has = getChoice(cell.getAttribute('data-item')) !== '';
            var mark = cell.querySelector('.slot-chosen');
            if (has && !mark) {
                cell.appendChild(el('span', 'slot-chosen', '✓'));
            } else if (!has && mark) {
                mark.parentNode.removeChild(mark);
            }
        }
    }

    /**
     * 把配方按「同一种做法」分组。
     *
     * <p>
     * 分组键用处理器标签（{@code handler}）而不是机器名：机器名可能重复
     * （好几台机器都叫「有序合成」），而标签是 NEI 里真正区分配方表的东西。
     * 同一组内部保持后端给的顺序（NEI 里的顺序）。
     *
     * @return {@code [{title, tag, recipes: [...]}]}，按首次出现排序
     */
    function groupRecipes(recipes) {
        var order = [];
        var byTag = {};
        for (var i = 0; i < recipes.length; i++) {
            var r = recipes[i];
            if (!r) continue;
            var tag = String(r.handler || r.machine || '?');
            var group = byTag[tag];
            if (!group) {
                group = {
                    tag: tag,
                    title: r.machine ? String(r.machine) : '未知做法',
                    // 这一类配方在 NEI 里的代表图标（烧制 = 熔炉）。后端给的是物品编号，
                    // 拿不到就是 -1，界面退回纯文字
                    tabIcon: num(r.tabIcon, -1),
                    recipes: []
                };
                byTag[tag] = group;
                order.push(group);
            }
            group.recipes.push(r);
        }
        return order;
    }

    /**
     * 一组同类型配方：一张卡片 + 左右箭头翻页。
     *
     * <p>
     * 为什么要合并：同一个物品在同一个处理器下经常有几十条配方（不同电压、不同材料），
     * 全铺开的话玩家要在一个长列表里找，而且很难意识到「这些是同一类做法、只是参数不同」。
     * 合并之后一屏能看完所有「做法」，再在组内翻具体那一条。
     */
    function makeRecipeGroup(group, options) {
        var opts = options || {};
        var list = group.recipes;
        var index = 0;

        var box = el('div', 'recipe-group');
        var head = el('div', 'group-head');
        // 类别图标：烧制是熔炉、组装机是组装机方块 —— 一列同类型配方扫下来，
        // 认图比认字快。NEI 没给这一类配图标时就不放（不拿占位块凑数）
        if (group.tabIcon >= 0) {
            var icon = el('span', 'group-icon');
            icon.appendChild(makeIcon({ id: group.tabIcon, name: group.title }, 'icon-22'));
            head.appendChild(icon);
        }
        var title = el('span', 'group-title', group.title);
        head.appendChild(title);
        if (list.length > 1) {
            head.appendChild(el('span', 'group-total', list.length + ' 条'));
        }
        head.appendChild(el('span', 'spacer'));

        var nav = el('div', 'recipe-nav');
        var prev = btn('recipe-nav-btn', '‹');
        prev.setAttribute('aria-label', '上一条' + group.title + '配方');
        var counter = el('span', 'recipe-nav-count', '');
        var next = btn('recipe-nav-btn', '›');
        next.setAttribute('aria-label', '下一条' + group.title + '配方');
        nav.appendChild(prev);
        nav.appendChild(counter);
        nav.appendChild(next);
        head.appendChild(nav);
        box.appendChild(head);

        var host = el('div', 'stack');
        box.appendChild(host);

        var paint = function () {
            clear(host);
            var rid = list[index] && list[index].rid !== undefined ? String(list[index].rid) : '';
            var card = makeRecipeCard(list[index], {
                itemId: opts.itemId,
                selectable: true,
                // 机器名已经在组标题上写了一遍，卡片里不再重复
                hideMachine: true,
                onSelect: function (rid) {
                    if (opts.selectHandler) opts.selectHandler(rid)();
                },
                onClear: function () {
                    if (opts.onClear) opts.onClear();
                }
            });
            host.appendChild(card);
            if (opts.onCard) opts.onCard(card, rid);
            counter.textContent = (index + 1) + ' / ' + list.length;
            prev.disabled = list.length <= 1;
            next.disabled = list.length <= 1;
        };

        var step = function (delta) {
            if (list.length <= 1) return;
            index = (index + delta + list.length) % list.length;
            paint();
        };
        prev.addEventListener('click', function () {
            step(-1);
        });
        next.addEventListener('click', function () {
            step(1);
        });
        paint();
        return box;
    }

    /* ------------------------------------------------------------ 配方渲染 */

    function gridDims(recipe) {
        var w = 3;
        var h = 3;
        if (recipe && recipe.grid) {
            var gw = Math.round(num(recipe.grid.w, 3));
            var gh = Math.round(num(recipe.grid.h, 3));
            if (gw > 0 && gw <= 16) {
                w = gw;
            }
            if (gh > 0 && gh <= 16) {
                h = gh;
            }
        }
        return { w: w, h: h };
    }

    /**
     * 槽位：x/y 是格子坐标（0 开始），越界（脏数据）直接忽略。
     * kind: 'input' 显示需求数量，'output' 显示产出数量，其余只显示数量。
     *
     * <p>
     * 提示（title）一律挂在<b>整格</b>上，不挂在右下角那个数量角标上：
     * 角标只有十几个像素、还带 pointer-events:none，指针停在图标上时什么都没有 ——
     * 玩家看到的现象就是「这一格里只有某个槽有提示，其它都没有」。
     */
    function makeSlot(slot, kind, multiplier) {
        var cell = el('div', 'slot' + (kind === 'output' ? ' is-output' : ''));
        var item = slot && slot.primary ? slot.primary : slot;
        var id = item && item.id !== undefined && item.id !== null ? item.id : null;
        cell.appendChild(makeIcon(item));

        var label = item && item.name ? String(item.name) : '未知物品';
        var per = num(slot ? slot.count : 0, 0);
        var times = Math.max(1, num(multiplier, 1));
        // 这一步要做 times 次，所以这一格真正要备/要收的是 per × times。
        // 只写 NEI 那种「每次用量」会让玩家少备几十上百倍。
        var count = per * times;
        var altCount = slot && slot.alts ? slot.alts.length : 0;

        var tip = label;
        if (count > 0) {
            // 数量一律过 countText：超过一组的附上「几组零几个」。
            // 提示和信息条是最该有它的地方 —— 那正是玩家决定「要不要去腾背包」的时刻。
            // （乘号统一用 × U+00D7；这里一度写成 ⨯ U+2A2F，同一个数字看着是两个字符）
            tip += ' ×' + countText(count);
            if (times > 1) {
                tip += '（每个配方 ×' + per + '，本次做 ' + times + ' 次）';
            }
        }
        if (altCount > 1) {
            tip += '（或其它 ' + (altCount - 1) + ' 种替代品）';
        }
        tip += kind === 'output' ? '\n本次共产出' : '\n本次共需要';
        if (id !== null) {
            tip += '\n点一下看这是什么';
        }
        cell.title = tip;

        if (count > 1) {
            var countCls = 'slot-count';
            if (kind === 'output') {
                countCls += ' is-ok';
            } else if (kind === 'input' && item && num(item.stock, 0) <= 0) {
                countCls += ' is-dim';
            }
            cell.appendChild(el('span', countCls, compactCount(count)));
        }

        // 这个材料手动指定过配方：左上角一个小勾（右下角是数量，两个角各干各的）
        if (id !== null && getChoice(id) !== '') {
            cell.appendChild(el('span', 'slot-chosen', '✓'));
        }

        var chance = num(slot ? slot.chance : 100, 100);
        if (chance < 100) {
            cell.appendChild(el('span', 'slot-chance', chance + '%'));
        }

        // 手机上先「点一下看这是什么」；要换配方在信息条上再点一步。
        // （桌面端仍然可以直接悬停看 title，两条路都留着）
        if (id !== null) {
            cell.setAttribute('data-item', String(id));
            cell.classList.add('is-clickable');
            cell.setAttribute('role', 'button');
            cell.tabIndex = 0;
            var shownCount = count;
            var shownPer = per;
            var shownTimes = times;
            var open = function (e) {
                e.preventDefault();
                e.stopPropagation();
                showItemInfo(item, shownCount, {
                    note: (kind === 'output' ? '本次共产出' : '本次共需要')
                        + (shownTimes > 1 ? '，每个配方 ×' + shownPer : '')
                });
            };
            cell.addEventListener('click', open);
            cell.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13 || e.keyCode === 32) {
                    open(e);
                }
            });
        }
        return cell;
    }

    /**
     * 把槽位按 x/y 摆进 grid.w × grid.h 的网格，空格子留空。
     *
     * <p>
     * 材料和产出画在<b>同一张网格</b>里，因为后端给的 x/y 本来就是同一套坐标
     * （NEI 配方界面的坐标）—— 工作台是「左边 3×3、右边产物」，GT 机器是
     * 「左侧进料、右侧出料」，合起来画正是玩家在 NEI 里看到的样子。
     * 分成两张网格并排画的话，GT 常见的 5 列布局在手机上会把产物挤出屏幕外
     * （实测 390px 宽时产物整列都在可视区之外，页面上又没有任何提示）。
     *
     * @param entries {@code [{slot, kind}]}，kind 见 {@link makeSlot}
     * @param multiplier 做几次。传 &gt;1 时槽位数字按<b>本次总量</b>显示 ——
     *        「这一步做 256 次」时，玩家真正要备的是每个槽 ×256，
     *        只写 NEI 那种每次用量会让他少备 256 倍。
     */
    function makeGrid(recipe, entries, multiplier) {
        var dims = gridDims(recipe);
        var grid = el('div', 'grid');
        grid.style.gridTemplateColumns = 'repeat(' + dims.w + ', var(--slot-size))';
        grid.style.gridTemplateRows = 'repeat(' + dims.h + ', var(--slot-size))';
        grid.style.gridAutoFlow = 'row';

        var cells = [];
        var i;
        for (i = 0; i < dims.w * dims.h; i++) {
            cells.push(null);
        }
        var list = Array.isArray(entries) ? entries : [];
        for (i = 0; i < list.length; i++) {
            var entry = list[i];
            if (!entry || !entry.slot) {
                continue;
            }
            var x = Math.round(num(entry.slot.x, -1));
            var y = Math.round(num(entry.slot.y, -1));
            if (x < 0 || y < 0 || x >= dims.w || y >= dims.h) {
                continue;   // 坐标越界：忽略脏数据
            }
            cells[y * dims.w + x] = entry;
        }
        for (i = 0; i < cells.length; i++) {
            if (cells[i]) {
                grid.appendChild(makeSlot(cells[i].slot, cells[i].kind, multiplier));
            } else {
                grid.appendChild(el('div', 'slot is-empty'));
            }
        }
        return grid;
    }

    /** 把一组槽位包成 makeGrid 要的 {@code {slot, kind}} 列表。 */
    function slotEntries(slots, kind) {
        var out = [];
        var list = Array.isArray(slots) ? slots : [];
        for (var i = 0; i < list.length; i++) {
            if (list[i]) {
                out.push({ slot: list[i], kind: kind });
            }
        }
        return out;
    }

    function isSlotEmpty(slots) {
        return !Array.isArray(slots) || slots.length === 0;
    }

    /**
     * 「产出：某某 ×2」一行文字。
     *
     * <p>
     * 网格里产出槽的数字是绿的，但手机上格子小、又和材料混在一张网格里，
     * 一眼扫过去容易看漏自己要的东西到底做出来几个 —— 这里再用文字说一遍。
     *
     * @param multiplier 做几次；&gt;1 时写的是本次总量（「做 64 次共产出 64 个」）
     */
    function makeOutputLine(recipe, multiplier) {
        var outputs = recipe && Array.isArray(recipe.outputs) ? recipe.outputs : [];
        var times = Math.max(1, num(multiplier, 1));
        var line = el('div', 'out-line');
        line.appendChild(el('span', 'slot-label', times > 1 ? '本次共产出' : '产出'));
        if (outputs.length === 0) {
            line.appendChild(el('span', 'dim', '（这条配方没有列出物品产出）'));
            return line;
        }
        for (var i = 0; i < outputs.length; i++) {
            var slot = outputs[i];
            var item = slot && slot.primary ? slot.primary : slot;
            var per = num(slot ? slot.count : 0, 0);
            var n = per * times;
            var chip = el('span', 'out-item');
            chip.appendChild(makeIcon(item, 'icon-22'));
            var text = (item && item.name ? String(item.name) : '未知物品') + (n > 1 ? ' ×' + countText(n) : '');
            if (times > 1 && per > 0) {
                text += '（每个配方 ×' + per + '）';
            }
            var chance = num(slot ? slot.chance : 100, 100);
            if (chance < 100) {
                text += '（' + chance + '%）';
            }
            chip.appendChild(el('span', null, text));
            line.appendChild(chip);
        }
        return line;
    }

    /**
     * 只更新配方卡片的选中状态（不重建整页，避免把用户输入的数量清掉）。
     * 已选状态下按钮变成「取消选择」，让用户能从这个入口改回自动挑选。
     */
    function paintRecipeCard(card, selected) {
        card.classList.toggle('is-selected', selected);
        var head = card.querySelector('.recipe-head');
        if (!head) {
            return;
        }
        var badge = head.querySelector('.badge');
        var pick = head.querySelector('.btn');
        if (selected && !badge) {
            badge = el('span', 'badge badge-ok', '已选');
            head.insertBefore(badge, pick);
        } else if (!selected && badge) {
            badge.remove();
        }
        if (pick) {
            pick.textContent = selected ? '取消选择' : '选它';
            pick.className = 'btn btn-small' + (selected ? ' is-selected' : '');
            pick.setAttribute('aria-pressed', selected ? 'true' : 'false');
        }
    }

    /**
     * 配方卡片：机器名 + 材料网格 + 产出。
     * opts.selectable=true 时带「选它 / 取消选择」按钮，
     * opts.onSelect(rid) / opts.onClear(rid) 回调由调用方决定怎么处理。
     */
    function makeRecipeCard(recipe, opts) {
        var options = opts || {};
        var rid = recipe && recipe.rid ? String(recipe.rid) : '';
        var selected = !!rid && getChoice(options.itemId) === rid;

        var card = el('div', 'card recipe' + (selected ? ' is-selected' : ''));
        // 配方号挂成属性：界面自动化要能确认「翻页后选中的到底是哪一条」
        if (rid) card.setAttribute('data-rid', rid);

        var head = el('div', 'recipe-head');
        // hideMachine：合并成组之后机器名已经在组标题上了，卡里不再重复
        if (!options.hideMachine) {
            head.appendChild(el('span', 'recipe-machine', recipe && recipe.machine ? recipe.machine : '未知机器'));
        }
        head.appendChild(el('span', 'spacer'));
        if (selected) {
            head.appendChild(el('span', 'badge badge-ok', '已选'));
        }
        if (options.selectable && rid) {
            var pick = btn('btn btn-small' + (selected ? ' is-selected' : ''), selected ? '取消选择' : '选它');
            pick.setAttribute('aria-pressed', selected ? 'true' : 'false');
            pick.setAttribute('aria-label', (selected ? '取消当前配方选择：' : '选择配方：') +
                (recipe && recipe.machine ? recipe.machine : '未知机器'));
            pick.addEventListener('click', function () {
                var nowSelected = getChoice(options.itemId) === rid;
                if (nowSelected && typeof options.onClear === 'function') {
                    options.onClear(rid);
                } else if (!nowSelected && typeof options.onSelect === 'function') {
                    options.onSelect(rid);
                }
            });
            head.appendChild(pick);
        }
        card.appendChild(head);

        var body = el('div', 'recipe-body');

        // 耗电 / 耗时（GT 配方才有）：材料表说不清「要几级机器、够不够电」
        var power = powerText(recipe, options.times);
        if (power) {
            body.appendChild(el('div', 'recipe-power', power));
        }

        var entries = slotEntries(recipe ? recipe.inputs : null, 'input');
        entries = entries.concat(slotEntries(recipe ? recipe.outputs : null, 'output'));
        var gridWrap = el('div', 'recipe-side');
        gridWrap.appendChild(makeGrid(recipe, entries));
        body.appendChild(gridWrap);
        body.appendChild(makeOutputLine(recipe));

        card.appendChild(body);

        if (!isSlotEmpty(recipe ? recipe.extras : null)) {
            var extras = el('div', 'extras');
            extras.appendChild(el('span', 'extras-label', '额外需要'));
            var list = recipe.extras;
            for (var i = 0; i < list.length; i++) {
                var slot = list[i];
                var item = slot && slot.primary ? slot.primary : slot;
                var wrap = el('span', 'extra-item');
                wrap.appendChild(makeIcon(item, 'icon-22'));
                var n = num(slot ? slot.count : 0, 0);
                wrap.appendChild(el('span', null, (item && item.name ? item.name : '未知物品') + (n > 1 ? ' ×' + countText(n) : '')));
                if (num(slot ? slot.chance : 100, 100) < 100) {
                    wrap.appendChild(el('span', 'dim', num(slot.chance, 100) + '%'));
                }
                extras.appendChild(wrap);
            }
            card.appendChild(extras);
        }

        return card;
    }

    function makeEmptyBox(title, message, actionText, onAction) {
        var box = el('div', 'box');
        box.appendChild(el('strong', null, title));
        if (message) {
            box.appendChild(el('div', null, message));
        }
        if (actionText && typeof onAction === 'function') {
            var b = btn('btn', actionText);
            b.addEventListener('click', onAction);
            box.appendChild(b);
        }
        return box;
    }

    /* ------------------------------------------------------------ 顶栏状态 */

    function setStatus(kind, text) {
        var dot = document.querySelector('#status .status-dot');
        if (dot) {
            dot.className = 'status-dot is-' + kind;
        }
        var textNode = byId('status-text');
        if (textNode) {
            textNode.textContent = text;
        }
    }

    function setProgress(value) {
        var wrap = byId('status-progress');
        var bar = byId('status-bar');
        if (!wrap || !bar) {
            return;
        }
        if (typeof value !== 'number' || !isFinite(value)) {
            wrap.hidden = true;
            return;
        }
        var pct = Math.round(Math.max(0, Math.min(1, value)) * 100);
        wrap.hidden = false;
        bar.style.width = pct + '%';
        wrap.setAttribute('aria-valuenow', String(pct));
    }

    function renderStatus(status) {
        state.status = status;
        if (status.building) {
            var pct = Math.round(Math.max(0, Math.min(1, num(status.progress, 0))) * 100);
            setStatus('building', '建立索引 ' + pct + '%');
            setProgress(num(status.progress, 0));
        } else if (status.error) {
            setStatus('error', '出错：' + String(status.error).slice(0, 24));
            setProgress(null);
        } else if (status.indexed === false) {
            setStatus('error', '索引未建立');
            setProgress(null);
        } else {
            setStatus('ok', (status.game || 'GTNH') + ' ' + (status.version || '') + ' 就绪');
            setProgress(null);
        }
    }

    function scheduleStatus(delay) {
        if (state.pollTimer) {
            window.clearTimeout(state.pollTimer);
        }
        state.pollTimer = window.setTimeout(pollStatus, typeof delay === 'number' ? delay : STATUS_POLL_MS);
    }

    function pollStatus() {
        state.pollTimer = null;
        apiStatus().then(function (st) {
            var partialChanged = state.lastPartial !== undefined && state.lastPartial !== (st.partial === true);
            state.lastPartial = st.partial === true;
            renderStatus(st);
            // 状态从「只有原版」变成完整（或反过来）时重画一次，让横幅即时消失/出现；
            // 但用户正开着配方弹层时不打断他
            if (partialChanged && byId('overlay').hidden) {
                render();
            }
            if (st.building) {
                // 进度变化时才提示，避免每 1.5 秒刷屏
                var pct = Math.round(Math.max(0, Math.min(1, num(st.progress, 0))) * 100);
                if (state.lastBuildPct !== pct) {
                    state.lastBuildPct = pct;
                    toast('正在建立配方索引（' + pct + '%' + (st.detail ? '，' + st.detail : '') + '），完成后会自动继续。');
                }
                scheduleStatus(STATUS_POLL_MS);
            } else {
                state.lastBuildPct = null;
                runPending();
            }
        }).catch(function (err) {
            setStatus('error', '连接失败');
            setProgress(null);
            // 服务可能还没起来：降频重试，不打扰用户
            scheduleStatus(Math.max(3000, STATUS_POLL_MS * 2));
            if (state.pending || !state.lastOfflineToast || (Date.now() - state.lastOfflineToast) > 10000) {
                state.lastOfflineToast = Date.now();
                toast(err && err.message ? err.message : '连接不上游戏内服务。', 'error');
            }
        });
    }

    /* ------------------------------------------------------------ 索引未建立时排队 */

    function armPending(action, message) {
        if (!state.pending && state.status && state.status.building && typeof message === 'string') {
            toast(message + ' 索引建好后会自动继续。');
        }
        state.pending = action;
        setStatus('building', '建立索引中…');
        setProgress(state.status ? num(state.status.progress, 0) : 0);
        scheduleStatus(300);
    }

    function runPending() {
        var action = state.pending;
        state.pending = null;
        if (typeof action === 'function') {
            action();
        }
    }

    /* ------------------------------------------------------------ 路由 */

    function currentRoute() {
        var raw = window.location.hash ? window.location.hash.replace(/^#/, '') : '';
        if (!raw || raw === '/') {
            return { name: 'search', id: 0, query: {} };
        }
        var qIndex = raw.indexOf('?');
        var path = qIndex >= 0 ? raw.substring(0, qIndex) : raw;
        var queryStr = qIndex >= 0 ? raw.substring(qIndex + 1) : '';
        var query = {};
        if (queryStr) {
            var pairs = queryStr.split('&');
            for (var i = 0; i < pairs.length; i++) {
                if (!pairs[i]) {
                    continue;
                }
                var eq = pairs[i].indexOf('=');
                var key = eq >= 0 ? pairs[i].substring(0, eq) : pairs[i];
                var value = eq >= 0 ? pairs[i].substring(eq + 1) : '';
                try {
                    query[decodeURIComponent(key)] = decodeURIComponent(value.replace(/\+/g, ' '));
                } catch (e) {
                    query[key] = value;
                }
            }
        }
        var parts = path.split('/').filter(function (s) {
            return s.length > 0;
        });
        var name = parts[0] || 'search';
        var id = parts.length > 1 ? clampInt(parts[1], 0, Number.MAX_SAFE_INTEGER, 0) : 0;
        return { name: name, id: id, query: query };
    }

    function go(hash) {
        if (window.location.hash === hash) {
            render();
        } else {
            window.location.hash = hash;
        }
    }

    function render() {
        state.viewToken += 1;
        var route = currentRoute();
        hideFooter();
        closeModal();
        clear(VIEW);

        // 「这个客户端还没进过世界」：NEI 只在进世界之后才注册各模组的配方表，
        // 这时候页面上搜什么机器都是「没有配方」，不说清楚玩家会以为是自己搜错了
        if (state.status && state.status.partial === true) {
            VIEW.appendChild(makePartialBanner());
        }

        if (route.name === 'item' && route.id > 0) {
            document.title = '物品 · 合成向导';
            renderItemPage(route.id);
        } else if (route.name === 'plan' && route.id > 0) {
            document.title = '合成步骤 · 合成向导';
            renderPlanPage(route.id, route.query);
        } else if (route.name === 'search') {
            document.title = '合成向导';
            renderSearchPage(route.query.q ? String(route.query.q) : '');
        } else {
            document.title = '合成向导';
            go('#/search');
        }
    }

    /* ------------------------------------------------------------ 底部操作栏 */

    /**
     * 「配方表不完整」横幅。
     *
     * <p>
     * 只在后端报 {@code partial} 时出现（客户端停在标题界面，NEI 还没注册各模组的
     * 配方处理器）。GTNH 玩家搜一台机器搜不到时，第一反应是「是不是我搜错了」——
     * 与其让他对着一个功能完好的页面怀疑自己，不如直说是哪一边还没就绪。
     */
    function makePartialBanner() {
        var box = el('div', 'banner banner-warn');
        box.appendChild(el('strong', null, '配方表暂不完整'));
        box.appendChild(
            el(
                'div',
                null,
                '提供这个页面的客户端还没进过世界。NEI 只在进入世界之后才注册各模组的配方表，'
                    + '所以现在只有原版合成/烧制之类，GT 机器的配方查不到。'
                    + '在那个客户端里进一次存档（进去就可以退出来），配方会自动补齐。'));
        return box;
    }

    function hideFooter() {
        var footer = byId('footer');
        var inner = footer.querySelector('.footer-inner');
        if (inner) {
            clear(inner);
        }
        footer.hidden = true;
    }

    function showFooter(primaryText, primaryHandler, secondary) {
        var footer = byId('footer');
        var inner = footer.querySelector('.footer-inner');
        clear(inner);
        if (primaryText && typeof primaryHandler === 'function') {
            var b = btn('btn btn-primary btn-block', primaryText);
            b.addEventListener('click', primaryHandler);
            inner.appendChild(b);
        }
        if (secondary) {
            var s = btn('btn btn-ghost', secondary.text);
            s.addEventListener('click', secondary.handler);
            inner.appendChild(s);
        }
        footer.hidden = false;
    }

    /* ------------------------------------------------------------ 搜索页 */

    function makeStatusBanner() {
        var st = state.status;
        if (!st || !st.building) {
            return null;
        }
        var pct = Math.round(Math.max(0, Math.min(1, num(st.progress, 0))) * 100);
        var box = el('div', 'box');
        box.appendChild(el('strong', null, '正在建立配方索引：' + pct + '%'));
        if (st.detail) {
            box.appendChild(el('div', null, String(st.detail)));
        }
        box.appendChild(el('div', 'dim', '首次打开需要几十秒到几分钟，完成后这里会自动刷新。'));
        return box;
    }

    function matchesHighlight(name, q) {
        var needle = String(q || '').trim();
        if (!needle || needle.charAt(0) === '@') {
            return false;
        }
        return String(name || '').toLowerCase().indexOf(needle.toLowerCase()) >= 0;
    }

    function buildResultRow(item, q) {
        var row = btn('result');
        row.setAttribute('aria-label', (item.name || '未知物品') + '，' + stockText(item.stock) +
            (item.craftable ? '，可合成' : ''));
        row.appendChild(makeIcon(item, 'icon-40'));

        var main = el('span', 'result-main');
        var name = el('span', 'result-name');
        if (matchesHighlight(item.name, q)) {
            var text = String(item.name);
            var at = text.toLowerCase().indexOf(String(q).trim().toLowerCase());
            var len = String(q).trim().length;
            name.appendChild(document.createTextNode(text.substring(0, at)));
            name.appendChild(el('b', null, text.substring(at, at + len)));
            name.appendChild(document.createTextNode(text.substring(at + len)));
        } else {
            name.textContent = item.name ? String(item.name) : '未知物品';
        }
        main.appendChild(name);
        if (item.mod) {
            main.appendChild(el('span', 'result-sub', String(item.mod)));
        }
        row.appendChild(main);

        var badges = el('span', 'result-badges');
        var stock = num(item.stock, 0);
        badges.appendChild(el('span', 'badge' + (stock > 0 ? ' badge-ok' : ''), stockText(stock)));
        if (item.craftable) {
            badges.appendChild(el('span', 'badge badge-warn', '可合成'));
        }
        row.appendChild(badges);

        row.addEventListener('click', function () {
            go('#/item/' + item.id);
        });
        return row;
    }

    function runSearch(q, seq) {
        if (!byId('search-results')) {
            return;
        }
        apiSearch(q, 40, 0).then(function (data) {
            if (seq !== state.search.seq || !byId('search-results')) {
                return;
            }
            state.search.items = Array.isArray(data.items) ? data.items : [];
            state.search.total = num(data.total, state.search.items.length);
            renderSearchResults(q);
        }).catch(function (err) {
            if (seq !== state.search.seq) {
                return;
            }
            var box = byId('search-results');
            if (!box) {
                return;
            }
            clear(box);
            if (err && err.name === 'IndexBuildingError') {
                var banner = makeStatusBanner();
                if (banner) {
                    box.appendChild(banner);
                }
                armPending(function () {
                    runSearch(q, state.search.seq);
                });
                return;
            }
            box.appendChild(makeEmptyBox('搜索失败', err && err.message ? err.message : '请稍后再试。', '重试', function () {
                state.search.seq += 1;
                runSearch(q, state.search.seq);
            }));
        });
    }

    function renderSearchResults(q) {
        var box = byId('search-results');
        if (!box) {
            return;
        }
        clear(box);
        var items = state.search.items;

        var meta = el('div', 'row muted');
        if (items.length === 0) {
            meta.textContent = q ? '没有找到匹配的物品' : '暂时没有可显示的数据';
        } else {
            meta.textContent = '共 ' + state.search.total + ' 项，显示前 ' + items.length + ' 项';
        }
        box.appendChild(meta);

        if (items.length === 0) {
            box.appendChild(makeEmptyBox(
                q ? '没有匹配「' + q + '」的物品' : '索引里没有物品',
                q ? '换个关键词试试，支持中文名、拼音、@模组名。' : '等索引建立完成后再刷新看看。'
            ));
            return;
        }

        var list = el('div', 'list');
        for (var i = 0; i < items.length; i++) {
            list.appendChild(buildResultRow(items[i], q));
        }
        box.appendChild(list);
    }

    function renderSearchPage(q) {
        state.plan.active = false;
        var panel = el('div', 'panel');

        var banner = makeStatusBanner();
        if (banner) {
            panel.appendChild(banner);
        }

        var bar = el('div', 'search-bar');
        var label = el('label', 'field-label', '搜索物品');
        label.setAttribute('for', 'search-input');
        bar.appendChild(label);
        var input = document.createElement('input');
        input.type = 'search';
        input.id = 'search-input';
        input.className = 'input';
        input.autocomplete = 'off';
        input.setAttribute('enterkeyhint', 'search');
        input.placeholder = '中文名 / 拼音 / @模组名';
        input.value = q || '';
        bar.appendChild(input);
        panel.appendChild(bar);

        var results = el('div', 'section');
        results.id = 'search-results';
        panel.appendChild(results);

        VIEW.appendChild(panel);

        input.addEventListener('input', function () {
            state.search.seq += 1;
            var seq = state.search.seq;
            q = input.value;
            state.search.q = q;
            if (state.debounceTimer) {
                window.clearTimeout(state.debounceTimer);
            }
            state.debounceTimer = window.setTimeout(function () {
                runSearch(q, seq);
            }, SEARCH_DEBOUNCE_MS);
        });

        // 首次进入立即查一次（不等待防抖）
        state.search.seq += 1;
        var seq = state.search.seq;
        state.search.q = q;
        if (state.debounceTimer) {
            window.clearTimeout(state.debounceTimer);
        }
        runSearch(q, seq);

        try {
            input.focus();
        } catch (e) {
            /* 某些 WebView 不允许自动聚焦 */
        }
    }

    /* ------------------------------------------------------------ 物品页 */

    function itemFooterConfig(id) {
        return {
            text: '生成合成步骤',
            handler: function () {
                var input = byId('count-input');
                var n = input ? clampInt(input.value, 1, 1000000, state.item.count) : state.item.count;
                state.item.count = n;
                state.plan.count = n;
                go('#/plan/' + id + '?count=' + n);
            }
        };
    }

    /**
     * 计划清单那一块（「这次要一起做的东西」）。
     *
     * <p>
     * 做机器是成套的：A 64 个 + B 3 个 + C 1 个。清单里的东西会被一起规划，
     * 公共的中间产物只算一次 —— 所以先把清单摆出来，玩家才看得见自己加了什么。
     */
    function makeBasketSection(currentId, currentCount) {
        var list = readBasket();
        var section = el('div', 'section');
        var title = el('div', 'section-title');
        title.appendChild(document.createTextNode('一起做'));
        title.appendChild(el('span', 'count', '(' + (list.length + (currentId !== null ? 1 : 0)) + ')'));
        section.appendChild(title);

        section.appendChild(el('div', 'muted',
            '这里的东西会放进同一份计划一起算：共用的中间产物只做一批，'
                + '步骤表里也会合并。'));

        var rows = el('div', 'basket-list');
        var addRow = function (itemId, count, isCurrent) {
            var row = el('div', 'basket-row' + (isCurrent ? ' is-current' : ''));
            var ref = { id: itemId, name: '' };
            row.appendChild(makeIcon(ref, 'icon-22'));

            var info = el('div', 'basket-info');
            var line = el('div', 'basket-name', state.basketNames[itemId] || ('#' + itemId));
            info.appendChild(line);
            row.appendChild(info);

            var qty = document.createElement('input');
            qty.type = 'number';
            qty.className = 'input input-count is-small';
            qty.min = '1';
            qty.max = '1000000';
            qty.value = String(count);
            qty.setAttribute('aria-label', '要做多少个');
            qty.addEventListener('change', function () {
                var value = clampInt(qty.value, 1, 1000000, count);
                qty.value = String(value);
                if (isCurrent) {
                    // 当前这一件的数量就是这份计划的数量
                    state.plan.count = value;
                    state.item.count = value;
                } else {
                    var now = readBasket();
                    for (var i = 0; i < now.length; i++) {
                        if (now[i].id === itemId) now[i].count = value;
                    }
                    writeBasket(now);
                }
                refreshPlan();
            });
            row.appendChild(qty);

            if (isCurrent) {
                row.appendChild(el('span', 'dim', '当前'));
            } else {
                var drop = btn('btn btn-ghost btn-small', '移出');
                drop.setAttribute('aria-label', '把这个物品移出计划清单');
                drop.addEventListener('click', function () {
                    removeFromBasket(itemId);
                    refreshPlan();
                    toast('已移出计划清单。');
                });
                row.appendChild(drop);
            }
            rows.appendChild(row);
        };

        if (currentId !== null) addRow(currentId, currentCount, true);
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === currentId) continue;
            addRow(list[i].id, list[i].count, false);
        }
        if (list.length === 0 && currentId === null) {
            rows.appendChild(el('div', 'card muted', '清单是空的。在物品页点「一起做」把东西加进来。'));
        }
        section.appendChild(rows);
        return section;
    }

    /**
     * 把清单里缺的名字补上。
     *
     * <p>
     * 清单只存了编号和数量（编号每个会话会重排，名字只能现查）——
     * 用搜索接口按编号反查太绕，直接用规划返回的 targets 里的名字。
     */
    function rememberBasketNames(plan) {
        var targets = plan && Array.isArray(plan.targets) ? plan.targets : [];
        for (var i = 0; i < targets.length; i++) {
            if (targets[i] && targets[i].id !== undefined) {
                state.basketNames[targets[i].id] = String(targets[i].name || '');
            }
        }
    }

    function renderItemPage(id) {
        state.plan.active = false;
        state.item.id = id;
        state.item.count = clampInt(state.item.count, 1, 1000000, 64);

        var panel = el('div', 'panel');
        var host = el('div', 'section');
        host.id = 'item-host';
        host.appendChild(el('div', 'box', '正在读取物品…'));
        panel.appendChild(host);

        var usedInHost = el('div', 'section');
        usedInHost.id = 'usedin-host';
        panel.appendChild(usedInHost);

        VIEW.appendChild(panel);

        var token = state.viewToken;
        var done = function () {
            return token !== state.viewToken || !byId('item-host');
        };

        var load = function () {
            apiItem(id).then(function (data) {
                if (done()) {
                    return;
                }
                renderItemDetail(data);
            }).catch(function (err) {
                if (done()) {
                    return;
                }
                var h = byId('item-host');
                if (!h) {
                    return;
                }
                clear(h);
                if (err && err.name === 'IndexBuildingError') {
                    var banner = makeStatusBanner();
                    if (banner) {
                        h.appendChild(banner);
                    }
                    armPending(load);
                    return;
                }
                h.appendChild(makeEmptyBox('读取物品失败',
                    err && err.message ? err.message : '请稍后再试。', '重试', load));
            });
        };

        load();
        showFooter(itemFooterConfig(id).text, itemFooterConfig(id).handler);
    }

    function renderItemDetail(data) {
        var host = byId('item-host');
        if (!host) {
            return;
        }
        clear(host);
        var item = data.item || {};
        var id = item.id !== undefined ? item.id : state.item.id;
        var count = state.item.count;
        var recipes = Array.isArray(data.recipes) ? data.recipes : [];

        /* 头部：大图标 + 名字 + 库存 */
        var head = el('div', 'card item-head');
        head.appendChild(makeIcon(item, 'icon-lg'));
        var info = el('div', 'material-info');
        info.appendChild(el('div', 'item-name', item.name ? String(item.name) : '未知物品'));
        var sub = el('div', 'item-sub');
        sub.textContent = stockText(item.stock) + (item.mod ? ' · ' + item.mod : '');
        info.appendChild(sub);
        var badges = el('div', 'chips');
        badges.appendChild(el('span', 'badge' + (item.craftable ? ' badge-ok' : ''),
            item.craftable ? '可合成' : '无配方'));
        if (num(item.stock, 0) <= 0) {
            badges.appendChild(el('span', 'badge badge-danger', '库存为 0'));
        }
        info.appendChild(badges);
        head.appendChild(info);
        host.appendChild(head);

        /* 数量输入 */
        var qty = el('div', 'card');
        var rowLabel = el('label', 'field-label', '我要合成');
        rowLabel.setAttribute('for', 'count-input');
        qty.appendChild(rowLabel);

        var row = el('div', 'row-input');
        var input = document.createElement('input');
        input.type = 'number';
        input.id = 'count-input';
        input.className = 'input input-count';
        input.min = '1';
        input.max = '1000000';
        input.step = '1';
        input.inputMode = 'numeric';
        input.value = String(count);
        input.setAttribute('aria-label', '要合成的数量');
        row.appendChild(input);
        row.appendChild(el('span', null, '个'));

        var presets = [1, 8, 16, 64];
        var presetButtons = [];
        for (var p = 0; p < presets.length; p++) {
            (function (value) {
                var b = btn('btn btn-small' + (count === value ? ' is-selected' : ''), String(value));
                b.setAttribute('aria-pressed', count === value ? 'true' : 'false');
                b.addEventListener('click', function () {
                    input.value = String(value);
                    state.item.count = value;
                    for (var i = 0; i < presetButtons.length; i++) {
                        var on = presetButtons[i].value === value;
                        presetButtons[i].node.classList.toggle('is-selected', on);
                        presetButtons[i].node.setAttribute('aria-pressed', on ? 'true' : 'false');
                    }
                });
                presetButtons.push({ value: value, node: b });
                row.appendChild(b);
            })(presets[p]);
        }
        qty.appendChild(row);

        input.addEventListener('input', function () {
            var value = clampInt(input.value, 1, 1000000, 1);
            state.item.count = value;
            for (var i = 0; i < presetButtons.length; i++) {
                var on = presetButtons[i].value === value;
                presetButtons[i].node.classList.toggle('is-selected', on);
                presetButtons[i].node.setAttribute('aria-pressed', on ? 'true' : 'false');
            }
        });
        input.addEventListener('change', function () {
            var value = clampInt(input.value, 1, 1000000, 1);
            input.value = String(value);
            state.item.count = value;
        });
        input.addEventListener('focus', function () {
            input.select();
        });
        host.appendChild(qty);

        /* 加进「一起做」清单：做机器是成套的，A 64 个 + B 3 个要一起算 */
        var itemName = item && item.name ? String(item.name) : ('#' + id);
        var basketBox = el('div', 'card basket-add');
        var basketBtn = btn('btn btn-small', '一起做：加进计划清单');
        basketBtn.setAttribute('aria-label', '把当前数量和这件物品加进计划清单，和别的物品一起规划');
        basketBtn.addEventListener('click', function () {
            var box = byId('count-input');
            var n = box ? clampInt(box.value, 1, 1000000, state.item.count) : state.item.count;
            addToBasket(id, n);
            state.basketNames[id] = itemName;
            toast('已把 ' + itemName + ' ×' + n + ' 加进「一起做」。再去加别的，或直接生成步骤。', 'ok');
        });
        basketBox.appendChild(basketBtn);
        var basketSize = readBasket().length;
        if (basketSize > 0) {
            basketBox.appendChild(el('div', 'muted',
                '清单里已有 ' + basketSize + ' 件东西，生成步骤时会一起算。'));
        }
        host.appendChild(basketBox);

        /* 配方列表 */
        var section = el('div', 'section');
        var title = el('div', 'section-title');
        title.appendChild(document.createTextNode('配方'));
        // 回收类物品的候选配方能有上万条，后端只回前 80 条并给出真实总数，
        // 这里照实写「(80 / 共 11082)」，别让玩家以为就这么几条
        var total = num(data.recipeTotal, recipes.length);
        title.appendChild(el('span', 'count',
            total > recipes.length ? '(' + recipes.length + ' / 共 ' + total + ')' : '(' + recipes.length + ')'));
        section.appendChild(title);

        if (recipes.length === 0) {
            section.appendChild(makeEmptyBox('没有可用配方',
                '这是一份原始材料，直接去收集吧。', '生成合成步骤', function () {
                    var input2 = byId('count-input');
                    var n = input2 ? clampInt(input2.value, 1, 1000000, count) : count;
                    state.item.count = n;
                    go('#/plan/' + id + '?count=' + n);
                }));
        } else {
            section.appendChild(el('div', 'muted', '未选择配方时，规划会自动挑选一条；点「选它」可以固定你要用的配方。'));
            if (total > recipes.length) {
                section.appendChild(
                    el('div', 'muted',
                        '这个物品有 ' + total + ' 条配方，这里只列出前 ' + recipes.length + ' 条（回收类物品都这样）。'));
            }

            // 就地更新卡片状态，不重建页面，免得把用户输入的数量清掉
            var cards = [];
            var applyChoice = function (rid) {
                var wasRaw = rid ? chooseRecipe(id, rid) : (setChoice(id, ''), false);
                for (var i = 0; i < cards.length; i++) {
                    paintRecipeCard(cards[i].node, !!rid && cards[i].rid === rid);
                }
                // 网格里的「已选配方」勾也要跟着变：整页重绘会把用户填的数量清掉，
                // 所以这里手动把勾补上/去掉
                refreshChosenMarks();
                return wasRaw;
            };

            var selectHandler = function (rid) {
                return function () {
                    var wasRaw = applyChoice(rid);
                    toast(
                        '已选择配方，规划时将优先使用。'
                            + (wasRaw ? '同时取消了「当作原始材料」——两者是冲突的。' : ''),
                        'ok');
                };
            };

            // 同类型（同一个处理器 / 同一台机器）的配方合成一张卡片，卡内用左右箭头翻。
            // 一个物品经常有几十条同类型的配方（回收类上千条），一条一张卡的话
            // 手机上要滑很久，而且看不出「这些其实是同一类做法」。
            var groups = groupRecipes(recipes);
            for (var g = 0; g < groups.length; g++) {
                section.appendChild(makeRecipeGroup(groups[g], {
                    itemId: id,
                    selectHandler: selectHandler,
                    onClear: function () {
                        applyChoice('');
                        toast('已取消该物品的配方选择，规划时改为自动挑选。');
                    },
                    onCard: function (node, rid) {
                        cards.push({ node: node, rid: rid });
                    }
                }));
            }
        }
        host.appendChild(section);

        /* 用在哪些配方里 */
        var usedIn = Array.isArray(data.usedIn) ? data.usedIn : [];
        var usedHost = byId('usedin-host');
        if (usedHost) {
            clear(usedHost);
            if (usedIn.length > 0) {
                var usedTitle = el('div', 'section-title');
                usedTitle.appendChild(document.createTextNode('用于'));
                usedTitle.appendChild(el('span', 'count', '(' + usedIn.length + ')'));
                usedHost.appendChild(usedTitle);
                var chips = el('div', 'chips');
                for (var u = 0; u < usedIn.length; u++) {
                    (function (entry) {
                        var chip = btn('chip');
                        chip.appendChild(makeIcon(entry, 'icon-22'));
                        chip.appendChild(el('span', null, entry && entry.name ? String(entry.name) : '未知物品'));
                        chip.setAttribute('aria-label', '查看 ' + (entry && entry.name ? entry.name : '物品'));
                        chip.addEventListener('click', function () {
                            go('#/item/' + entry.id);
                        });
                        chips.appendChild(chip);
                    })(usedIn[u]);
                }
                usedHost.appendChild(chips);
            } else {
                usedHost.appendChild(el('div', 'muted', '暂无「用于」数据。'));
            }
        }

        var footerConfig = itemFooterConfig(id);
        showFooter(footerConfig.text, footerConfig.handler);
    }

    /* ------------------------------------------------------------ 计划页 */

    function renderPlanPage(id, query) {
        var count = clampInt(query && query.count !== undefined ? query.count : 64, 1, 1000000, 64);
        var planState = state.plan;
        planState.id = id;
        planState.count = count;
        planState.plan = null;
        planState.open = {};
        planState.rawMode = false;
        planState.active = true;

        var panel = el('div', 'panel');
        var host = el('div', 'section');
        host.id = 'plan-host';
        host.appendChild(el('div', 'box', '正在计算合成步骤…'));
        panel.appendChild(host);
        VIEW.appendChild(panel);

        var token = state.viewToken;
        var done = function () {
            return token !== state.viewToken || !byId('plan-host');
        };

        var load = function () {
            apiPlan(id, count, stockParam(), choicesParam(), rawParam()).then(function (data) {
                if (done()) {
                    return;
                }
                renderPlanBody(data);
            }).catch(function (err) {
                if (done()) {
                    return;
                }
                var h = byId('plan-host');
                if (!h) {
                    return;
                }
                clear(h);
                if (err && err.name === 'IndexBuildingError') {
                    var banner = makeStatusBanner();
                    if (banner) {
                        h.appendChild(banner);
                    }
                    armPending(load);
                    return;
                }
                h.appendChild(makeEmptyBox('计算合成步骤失败',
                    err && err.message ? err.message : '请稍后再试。', '重试', load));
            });
        };

        load();
        showFooter('重新规划', load, {
            text: '返回物品',
            handler: function () {
                go('#/item/' + id);
            }
        });
    }

    function refreshPlan() {
        var plan = state.plan;
        // 不在计划页时不刷新（比如从物品页的弹层里改了选择）
        if (!plan.active || !byId('plan-host')) {
            return;
        }
        apiPlan(plan.id, plan.count, stockParam(), choicesParam(), rawParam()).then(function (data) {
            if (!plan.active || !byId('plan-host')) {
                return;
            }
            renderPlanBody(data);
        }).catch(function (err) {
            if (err && err.name === 'IndexBuildingError') {
                armPending(refreshPlan);
                return;
            }
            toast(err && err.message ? err.message : '重新规划失败。', 'error');
        });
    }

    function renderPlanBody(data) {
        var host = byId('plan-host');
        if (!host) {
            return;
        }
        clear(host);
        state.plan.plan = data;

        var target = data.target || {};
        var summary = data.summary || {};
        var warnings = Array.isArray(data.warnings) ? data.warnings : [];
        var materials = Array.isArray(data.materials) ? data.materials : [];
        var steps = Array.isArray(data.steps) ? data.steps : [];

        var targetId = target.id !== undefined ? target.id : state.plan.id;
        var targetCount = target.count !== undefined ? target.count : state.plan.count;
        var doneCount = doneSet(targetId, targetCount).length;

        /* 顶部：目标物品 + 重新规划 */
        var head = el('div', 'card');
        head.appendChild(el('div', 'item-sub', '目标物品'));
        var targetRow = el('div', 'target');
        targetRow.appendChild(makeIcon(target, 'icon-40'));
        var targetInfo = el('div', 'material-info');
        targetInfo.appendChild(el('div', 'item-name', target.name ? String(target.name) : ('#' + state.plan.id)));
        var line = el('div', 'material-stat');
        line.appendChild(el('span', 'need',
            '合成 ' + countText(target.count !== undefined ? target.count : state.plan.count) + ' 个'));
        targetInfo.appendChild(line);
        targetRow.appendChild(targetInfo);
        targetRow.appendChild(el('span', 'spacer'));
        // 库存开关：默认按「我手头有什么」算，切换后按「从零开始」算。
        // 提前备料、给别人列清单时要的正是后者。
        var stockToggle = btn('btn btn-small btn-ghost' + (ignoreStock() ? ' is-on' : ''),
            ignoreStock() ? '从零算' : '按库存算');
        stockToggle.setAttribute('aria-pressed', ignoreStock() ? 'true' : 'false');
        stockToggle.title = ignoreStock()
            ? '当前：不管手头有多少，把总需求全列出来（点一下改回按库存算）'
            : '当前：手头够了的材料就不用做（点一下改成从零算）';
        stockToggle.addEventListener('click', function () {
            setIgnoreStock(!ignoreStock());
            toast(ignoreStock() ? '已改成从零算，总需求会全部列出来。' : '已改回按手头库存算。');
            refreshPlan();
        });
        targetRow.appendChild(stockToggle);
        if (doneCount > 0) {
            var resetDone = btn('btn btn-small btn-ghost', '清零进度');
            resetDone.setAttribute('aria-label', '清空这份计划的施工进度');
            resetDone.addEventListener('click', function () {
                clearDone(targetId, targetCount);
                renderPlanBody(state.plan.plan);
                toast('施工进度已清零。');
            });
            targetRow.appendChild(resetDone);
        }
        var replan = btn('btn btn-small', '重新规划');
        replan.addEventListener('click', refreshPlan);
        targetRow.appendChild(replan);
        head.appendChild(targetRow);
        host.appendChild(head);

        // 「一起做」：这份计划里包含的每一件东西（含清单里加进来的）。
        // 只有一个目标时不显示，免得占地方
        rememberBasketNames(data);
        var allTargets = Array.isArray(data.targets) ? data.targets : [];
        if (allTargets.length > 1 || readBasket().length > 0) {
            host.appendChild(makeBasketSection(targetId, targetCount));
        }

        /* 概览 */
        var sum = el('div', 'card summary');
        sum.appendChild(el('span', 'badge', '步骤 ' + countText(num(summary.steps, steps.length))));
        sum.appendChild(el('span', 'badge', '原材料 ' + countText(num(summary.materials, materials.length))));
        sum.appendChild(el('span', 'badge', '深度 ' + countText(summary.depth)));
        if (summary.truncated) {
            sum.appendChild(el('span', 'badge badge-warn', '结果可能不完整'));
        }
        if (steps.length > 0) {
            sum.appendChild(el('span', 'badge' + (doneCount >= steps.length ? ' badge-ok' : ''),
                '已完成 ' + Math.min(doneCount, steps.length) + '/' + steps.length));
        }
        host.appendChild(sum);

        for (var w = 0; w < warnings.length; w++) {
            host.appendChild(el('div', 'card step-note', '提示：' + String(warnings[w])));
        }

        /* 现在可以做：按进度放行的那一批，互相之间没有依赖，可以同时开工 */
        if (steps.length > 0) {
            host.appendChild(makeReadySection(targetId, targetCount));
        }

        /* 还没指定配方：自顶向下过一遍，看到就点一下换掉 */
        host.appendChild(makeUnchosenSection(targetId, targetCount));

        /* 需要准备 */
        var matSection = el('div', 'section');
        var matTitle = el('div', 'section-title');
        matTitle.appendChild(document.createTextNode('需要准备'));
        matTitle.appendChild(el('span', 'count', '(' + materials.length + ')'));
        matSection.appendChild(matTitle);

        if (materials.length === 0) {
            matSection.appendChild(el('div', 'card muted',
                '不需要额外准备原材料：现有库存已经够了，或者全部材料都被当作原始材料跳过了。'));
        } else {
            for (var m = 0; m < materials.length; m++) {
                matSection.appendChild(makeMaterialRow(materials[m]));
            }
        }
        host.appendChild(matSection);

        /* 合成顺序 */
        var stepSection = el('div', 'section');
        var stepTitle = el('div', 'section-title');
        stepTitle.appendChild(document.createTextNode('合成顺序'));
        stepTitle.appendChild(el('span', 'count', '(' + steps.length + ')'));
        stepSection.appendChild(stepTitle);

        if (steps.length === 0) {
            stepSection.appendChild(el('div', 'card muted',
                state.plan.rawMode ? '所有材料都被标记为原始材料，没有需要合成的步骤。' : '没有需要合成的步骤。'));
        } else {
            var list = el('div', 'steps');
            for (var s = 0; s < steps.length; s++) {
                var step = steps[s];
                var n = num(step && step.n, s + 1);
                // 默认展开前 3 步
                var open = Object.prototype.hasOwnProperty.call(state.plan.open, String(n))
                    ? state.plan.open[String(n)]
                    : (n <= OPEN_STEPS);
                list.appendChild(makeStepCard(step, open));
            }
            stepSection.appendChild(list);
        }
        host.appendChild(stepSection);
    }

    /**
     * 「现在可以做」：按当前进度放行的那一批步骤。
     *
     * <p>
     * 为什么单独拎出来：GT 里同时开着好几台机器是常态，玩家真正想知道的是
     * 「我这会儿能同时推哪几样」，而不是一张长长的顺序表。这一批之间没有依赖，
     * 可以并行；勾掉其中一步，等着它的下一步就自动冒出来。
     */
    function makeReadySection(targetId, targetCount) {
        var plan = state.plan.plan;
        var steps = plan && Array.isArray(plan.steps) ? plan.steps : [];
        var ready = readySteps(plan);
        var section = el('div', 'section');
        var title = el('div', 'section-title');
        title.appendChild(document.createTextNode('现在可以做'));
        title.appendChild(el('span', 'count', '(' + ready.length + ')'));
        section.appendChild(title);

        if (steps.length > 0 && doneSet(targetId, targetCount).length >= steps.length) {
            section.appendChild(el('div', 'card muted', '所有步骤都已完成 🎉'));
            return section;
        }
        if (ready.length === 0) {
            section.appendChild(el('div', 'card muted',
                '暂时没有可开工的步骤：剩下的都要等前面的步骤先做完。'));
            return section;
        }

        section.appendChild(el('div', 'muted',
            '这几步互不依赖，可以同时开工（几台机器一起跑）。做完一步勾掉，下一批会自动出现。'));

        var listBox = el('div', 'ready-list');
        var groups = {};
        for (var i = 0; i < ready.length; i++) {
            var step = ready[i];
            var item = step.output || {};
            var card = el('div', 'ready-item');
            // 步骤号挂成属性：同名物品在一份计划里可能出现好几次，
            // 靠名字区分不开（自动化断言也靠它精确定位）
            card.setAttribute('data-step', String(step.n));

            var check = document.createElement('input');
            check.type = 'checkbox';
            check.className = 'done-box';
            check.checked = false;
            check.setAttribute('aria-label', '把第 ' + step.n + ' 步标记为已完成');
            check.addEventListener('change', function (s) {
                return function () {
                    toggleDone(s, true);
                };
            }(step));
            card.appendChild(check);

            // 图标本身就是「换这个物品的配方」的入口（和步骤卡片上那个一致）
            card.appendChild(makeItemIconButton(item, 'icon-22', '第 ' + step.n + ' 步'));

            var info = el('div', 'ready-info');
            var line1 = el('div', 'ready-name');
            line1.appendChild(el('span', null, item.name ? String(item.name) : ('#' + step.itemId)));
            line1.appendChild(el('span', 'dim', ' ×' + countText(step.total)));
            info.appendChild(line1);
            var line2 = el('div', 'material-stat');
            line2.appendChild(el('span', 'need', step.machine ? String(step.machine) : '未知机器'));
            line2.appendChild(document.createTextNode(' · 做 ' + countText(step.crafts) + ' 次'));
            if (Array.isArray(step.needs) && step.needs.length > 0) {
                line2.appendChild(document.createTextNode(' · 第 ' + step.needs.join('、') + ' 步之后'));
            }
            info.appendChild(line2);

            // 这里也给完整合成表：这几步正是「现在要开工」的，照着摆的时候不该再点进别处。
            // 用比步骤卡片小一号的槽位，免得一屏放不下好几步。
            var readyRecipe = step.recipe;
            if (readyRecipe && (Array.isArray(readyRecipe.inputs) ? readyRecipe.inputs.length : 0) > 0) {
                var gridBox = el('div', 'step-grid is-compact');
                gridBox.appendChild(makeGrid(readyRecipe, slotEntries(readyRecipe.inputs, 'input')
                    .concat(slotEntries(readyRecipe.outputs, 'output'))
                    .concat(slotEntries(readyRecipe.extras, 'extra')), step.crafts));
                info.appendChild(gridBox);
            }
            card.appendChild(info);
            listBox.appendChild(card);
            groups[String(step.n)] = true;
        }
        section.appendChild(listBox);
        return section;
    }

    /**
     * 「还没指定配方」：自顶向下把自动挑的配方列出来。
     *
     * <p>
     * 规划会自己挑配方，但挑得对不对只有玩家知道（同样的东西有好几条路线）。
     * 与其让他自己去长长的步骤表里找，不如按顺序列出来：看到哪条不顺眼点一下换掉。
     */
    function makeUnchosenSection(targetId, targetCount) {
        var plan = state.plan.plan;
        var steps = plan && Array.isArray(plan.steps) ? plan.steps : [];
        var pending = [];
        for (var i = 0; i < steps.length; i++) {
            var item = steps[i].output || {};
            if (item.id === undefined) continue;
            if (getChoice(item.id) === '') pending.push(steps[i]);
        }

        var section = el('div', 'section');
        var title = el('div', 'section-title');
        title.appendChild(document.createTextNode('还没指定配方'));
        title.appendChild(el('span', 'count', '(' + pending.length + ' / ' + steps.length + ')'));
        section.appendChild(title);

        if (steps.length === 0) {
            section.appendChild(el('div', 'card muted', '没有需要合成的步骤。'));
            return section;
        }
        if (pending.length === 0) {
            section.appendChild(el('div', 'card muted', '每一步都已经指定好配方了。'));
            return section;
        }

        section.appendChild(el('div', 'muted',
            '这些步骤用的是自动挑的配方。按顺序过一遍，觉得不合适就点一下换掉；换完计划会立刻重算。'));

        var box = el('div', 'chosen-list');
        for (var k = 0; k < pending.length; k++) {
            var step = pending[k];
            var item = step.output || {};
            // 已经做完的那几步压暗但仍留在表里：列表顺序才不会一边勾一边跳
            var rowDone = isDone(targetId, targetCount, step.rid);
            var row = el('button', 'unchosen-item' + (rowDone ? ' is-done' : ''));
            row.type = 'button';
            row.appendChild(el('span', 'step-no', String(step.n)));
            row.appendChild(makeIcon(item, 'icon-22'));
            row.appendChild(el('span', 'unchosen-name', item.name ? String(item.name) : ('#' + step.itemId)));
            row.appendChild(el('span', 'dim', step.machine ? String(step.machine) : '未知机器'));
            row.appendChild(el('span', 'spacer'));
            row.appendChild(el('span', 'unchosen-go', '选配方'));
            row.addEventListener('click', function (it) {
                return function () {
                    openRecipeModal({ id: it.id, name: it.name });
                };
            }(item));
            box.appendChild(row);
        }
        section.appendChild(box);
        return section;
    }

    /** 勾掉/取消一步，然后就地重排「现在可以做」。 */
    function toggleDone(step, on) {
        var plan = state.plan.plan;
        var target = (plan && plan.target) || {};
        var targetId = target.id !== undefined ? target.id : state.plan.id;
        var targetCount = target.count !== undefined ? target.count : state.plan.count;
        setDone(targetId, targetCount, step.rid, on);
        // 只重画页面主体，不重新请求：进度全在本地，没必要为一勾跑一趟后端
        renderPlanBody(plan);
        if (on) {
            toast('第 ' + step.n + ' 步已完成，可以做的步骤已更新。', 'ok');
        }
    }

    function toggleStep(n) {
        var key = String(n);
        var open = Object.prototype.hasOwnProperty.call(state.plan.open, key)
            ? state.plan.open[key]
            : (n <= OPEN_STEPS);
        state.plan.open[key] = !open;
        renderPlanBody(state.plan.plan);
    }

    function materialStatText(need, have, missing) {
        var wrap = el('div', 'material-stat');
        wrap.appendChild(el('span', 'need', '需要 ' + countText(need)));
        wrap.appendChild(document.createTextNode(' / '));
        wrap.appendChild(el('span', 'have', '已有 ' + countText(have)));
        wrap.appendChild(document.createTextNode(' / '));
        if (num(missing, 0) > 0) {
            wrap.appendChild(el('span', 'lack', '还缺 ' + countText(missing)));
        } else {
            wrap.appendChild(el('span', 'enough', '已足够'));
        }
        return wrap;
    }

    /**
     * 「这个物品为什么成了要自己准备的材料」。
     *
     * <p>
     * 后端给的是原因编码，这里翻成人话 —— 同样是「需要准备」，应对方式完全不同：
     * 没配方只能去挖；被自己勾成原始材料就该去把勾去掉；循环依赖则换条配方可能就绕开了。
     * 只写「要你自己准备」的话，玩家看到的就是「我明明选了配方，怎么还让我自己准备」。
     */
    var MATERIAL_WHY = {
        none: '没有配方，只能自己去挖 / 去找',
        raw: '你把它勾成了「当作原始材料」',
        cycle: '配方互相绕回自己，规划掐断了这一环（换条配方往往能绕开）',
        loop: '这条配方绕回了它自己，只能自己准备',
        depth: '展开层数到顶了，再往下算步骤会过长',
        fluid: '它的配方没有物品材料（只有流体/能量），物品形态上等于凭空产出，照着做会得出假步骤',
        catalyst: '非消耗品（可反复使用），只按一份算',
        other: '没能展开成步骤，按原材料处理'
    };

    function materialWhyText(reason) {
        return MATERIAL_WHY[String(reason || 'other')] || MATERIAL_WHY.other;
    }

    function makeMaterialRow(material) {
        var row = el('div', 'material');
        var id = material && material.id !== undefined ? material.id : 0;
        var main = el('div', 'material-main');
        main.appendChild(makeItemIconButton(material, 'icon-40'));

        var info = el('div', 'material-info');
        var nameBtn = btn('material-name', material && material.name ? String(material.name) : '未知物品');
        nameBtn.setAttribute('aria-label', '查看 ' + (material && material.name ? material.name : '该物品') + ' 的配方');
        nameBtn.addEventListener('click', function () {
            openRecipeModal(material);
        });
        info.appendChild(nameBtn);
        var rawMarked = isRaw(id);
        var hasChoice = getChoice(id) !== '';
        if (hasChoice) {
            info.appendChild(makeChosenTag());
        }
        // 两个状态打架：指定了配方，却被勾成「当作原始材料」。
        // 规划这时不会展开它 —— 不说清楚的话，玩家看到的就是
        //「我明明选了配方，怎么还让我自己去准备」。给一句明说 + 一键改回来。
        if (rawMarked && hasChoice) {
            info.appendChild(el('div', 'material-note',
                '已指定配方，但这里被勾成「当作原始材料」，所以不会展开它，要你自己准备。'));
            var back = btn('btn btn-small', '改回按配方合成');
            back.addEventListener('click', function () {
                toggleRaw(id, false);
                refreshPlan();
                toast('已取消「当作原始材料」，改回按你指定的配方合成。', 'ok');
            });
            info.appendChild(back);
        }
        info.appendChild(materialStatText(material ? material.need : 0, material ? material.have : 0,
            material ? material.missing : 0));
        // 每一行都写清楚「为什么它在这里」。没配方的那种是正常情况，不用强调；
        // 其余几种都是「本来可以做」的，得说明白，否则玩家只会觉得规划算错了
        var why = String((material && material.reason) || 'other');
        if (why !== 'none') {
            info.appendChild(el('div', 'material-why', '为什么按原材料算：' + materialWhyText(why)));
        }
        // 非消耗品是可以推翻的：自动判定总有认错的时候（比如某个模具其实会消耗掉）
        if (why === 'catalyst') {
            var asConsumed = btn('btn btn-small', '这个其实会消耗，按用量算');
            asConsumed.addEventListener('click', function () {
                setFlag(LS_CONSUMABLE, id, true);
                setFlag(LS_CATALYST, id, false);
                refreshPlan();
                toast('已改成按消耗算：以后会按合成次数累计用量。');
            });
            info.appendChild(asConsumed);
        } else if (id > 0) {
            // 反向：自动判定没认出来，但玩家知道它可反复用
            var asCatalyst = btn('btn btn-small btn-ghost', '非消耗品，只备一份');
            asCatalyst.addEventListener('click', function () {
                setFlag(LS_CATALYST, id, true);
                setFlag(LS_CONSUMABLE, id, false);
                refreshPlan();
                toast('已标成非消耗品：只按一份算，也不会展开成合成步骤。');
            });
            info.appendChild(asCatalyst);
        }
        main.appendChild(info);
        row.appendChild(main);

        var toggle = el('label', 'toggle material-toggle');
        var box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = rawMarked;
        box.setAttribute('aria-label', '把 ' + (material && material.name ? material.name : '该物品') + ' 当作原始材料');
        box.addEventListener('change', function () {
            toggleRaw(id, box.checked);
            state.plan.rawMode = box.checked;
            refreshPlan();
            toast(
                (box.checked ? '已把该物品当作原始材料，不再展开它的配方。' : '已恢复展开该物品的配方。')
                    + (box.checked && hasChoice ? '注意：你给它指定的配方会被搁置。' : ''));
        });
        toggle.appendChild(box);
        toggle.appendChild(el('span', null, '当作原始材料（自己去挖 / 去找）'));
        row.appendChild(toggle);

        return row;
    }

    function makeStepCard(step, open) {
        var plan = state.plan.plan || {};
        var target = plan.target || {};
        var targetId = target.id !== undefined ? target.id : state.plan.id;
        var targetCount = target.count !== undefined ? target.count : state.plan.count;
        var finished = isDone(targetId, targetCount, step && step.rid);

        var card = el('div', 'step' + (open ? ' is-open' : '') + (finished ? ' is-done' : ''));
        var n = num(step && step.n, 0);
        var out = step && step.output ? step.output : {};
        var crafts = num(step ? step.crafts : 1, 1);
        var total = num(out.total, 0);
        // 步骤号和配方号挂成属性：同名物品在一份计划里会出现多次，
        // 界面自动化得靠它们精确定位（不写死物品名，那样会随存档漂移）
        card.setAttribute('data-step', String(n));
        if (step && step.rid !== undefined) card.setAttribute('data-rid', String(step.rid));
        if (out.id !== undefined) card.setAttribute('data-item', String(out.id));

        // 勾选放在最左边：这是「正在照着做」的清单，勾是最高频的动作，
        // 比折叠箭头更该在拇指够得到的位置
        var check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'done-box';
        check.checked = finished;
        check.setAttribute('aria-label', '把第 ' + n + ' 步标记为已完成');
        check.addEventListener('click', function (e) {
            e.stopPropagation(); // 别让卡片的折叠也跟着触发
        });
        check.addEventListener('change', function () {
            toggleDone(step, check.checked);
        });

        var head = btn('step-head');
        head.setAttribute('aria-expanded', open ? 'true' : 'false');
        head.appendChild(check);
        // 左侧放产物图标：它同时也是「换这个物品的配方」的入口。
        // 这一步用的是不是手动指定的那条，靠 rid 和 choices 对上号来判断
        head.appendChild(makeItemIconButton(out, 'icon-22', '这一步的产物'));
        head.appendChild(el('span', 'step-no', String(n)));
        var main = el('span', 'step-main');
        var machineLine = el('span', 'step-machine-line');
        machineLine.appendChild(el('span', 'step-machine', step && step.machine ? String(step.machine) : '未知机器'));
        // 机器后面跟上电压/耗时：折叠着也能看出「这台机器要几级、跑多久」
        var headRecipe = step && step.recipe ? step.recipe : null;
        if (headRecipe) {
            var shortPower = powerShort(headRecipe);
            if (shortPower) {
                machineLine.appendChild(el('span', 'step-power', shortPower));
            }
        }
        if (out.id !== undefined && getChoice(out.id) !== '' && String(getChoice(out.id)) === String(step.rid)) {
            machineLine.appendChild(makeChosenTag());
        }
        main.appendChild(machineLine);
        var line = el('span', 'step-line');
        line.appendChild(document.createTextNode('做 ' + countText(crafts) + ' 次 → '));
        line.appendChild(el('span', 'step-out', (out.name ? String(out.name) : '产物') + ' ×' + countText(total)));
        main.appendChild(line);
        head.appendChild(main);
        head.appendChild(el('span', 'caret', '›'));
        head.addEventListener('click', function () {
            toggleStep(n);
        });
        card.appendChild(head);

        if (!open) {
            return card;
        }

        var body = el('div', 'step-body');

        /* 照着摆的那张合成表。
           后端把这条配方的完整槽位一起带过来了（step.recipe），所以这里能画出
           和物品页一样的网格 —— 材料清单只说明「要多少」，摆法还得看网格。 */
        var recipe = step && step.recipe ? step.recipe : null;
        if (recipe && (Array.isArray(recipe.inputs) ? recipe.inputs.length : 0) > 0) {
            // 按「做 crafts 次」的总量显示：这一步要做几十上百次，
            // 只给 NEI 那种每次用量的话，玩家会照着备少几十倍
            var power = powerText(recipe, crafts);
            if (power) {
                body.appendChild(el('div', 'recipe-power', power));
            }
            var gridBox = el('div', 'step-grid');
            gridBox.appendChild(makeGrid(recipe, slotEntries(recipe.inputs, 'input')
                .concat(slotEntries(recipe.outputs, 'output'))
                .concat(slotEntries(recipe.extras, 'extra')), crafts));
            body.appendChild(gridBox);
            body.appendChild(makeOutputLine(recipe, crafts));
        }

        /* 这一步需要的材料（点击可换配方 / 标记原始材料）。
           材料清单和上面的网格是互补的：网格给摆法，清单给「每种要多少、还缺多少」。 */
        var inputs = Array.isArray(step && step.inputs) ? step.inputs : [];
        if (inputs.length > 0) {
            var lines = el('div', 'mat-lines');
            for (var i = 0; i < inputs.length; i++) {
                lines.appendChild(makeMatLine(inputs[i], '材料'));
            }
            body.appendChild(lines);
        }

        var extras = Array.isArray(step && step.extras) ? step.extras : [];
        if (extras.length > 0) {
            var extraLines = el('div', 'mat-lines');
            for (var e = 0; e < extras.length; e++) {
                extraLines.appendChild(makeMatLine(extras[e], '额外'));
            }
            body.appendChild(extraLines);
        }

        if (step && step.note) {
            body.appendChild(el('div', 'step-note', String(step.note)));
        }

        card.appendChild(body);
        return card;
    }

    // 单个材料行：[图标] [材料] 名称按钮 每个配方 ×N，共需 M [还缺 Z] [当作原始材料]
    function makeMatLine(entry, tag) {
        var line = el('div', 'mat-line');
        line.appendChild(el('span', 'dim', tag));
        var id = entry && entry.id !== undefined ? entry.id : 0;
        line.appendChild(makeItemIconButton(entry, 'icon-22'));

        var nameBtn = btn('material-name is-link', entry && entry.name ? String(entry.name) : '未知物品');
        nameBtn.setAttribute('aria-label', '调整 ' + (entry && entry.name ? entry.name : '该材料') + ' 的配方');
        nameBtn.addEventListener('click', function () {
            openRecipeModal(entry);
        });
        line.appendChild(nameBtn);
        if (getChoice(id) !== '') {
            line.appendChild(makeChosenTag());
        }

        var per = num(entry ? entry.perCraft : 0, 0);
        var need = num(entry ? entry.need : 0, 0);
        per = num(entry ? entry.perCraft : 0, 0);
        need = num(entry ? entry.need : 0, 0);
        line.appendChild(el('span', null, '每个配方 ×' + per + '，共需 ' + countText(need)));
        if (num(entry ? entry.missing : 0, 0) > 0) {
            line.appendChild(el('span', 'lack', '还缺 ' + countText(entry.missing)));
        } else {
            line.appendChild(el('span', 'enough', '已足够'));
        }
        // 「任意一种都行」的槽位：列出候选，玩家手里的替代品是算数的
        var alts = alternativesText(entry);
        if (alts) {
            line.appendChild(el('span', 'alts-note', alts));
        }

        var toggle = el('label', 'toggle');
        var box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = isRaw(id);
        box.setAttribute('aria-label', '把 ' + (entry && entry.name ? entry.name : '该物品') + ' 当作原始材料');
        box.addEventListener('change', function () {
            toggleRaw(id, box.checked);
            refreshPlan();
            toast(box.checked ? '已把该物品当作原始材料，不再展开它的配方。' : '已恢复展开该物品的配方。');
        });
        toggle.appendChild(box);
        toggle.appendChild(el('span', null, '当作原始材料'));
        line.appendChild(toggle);

        return line;
    }

    /* ------------------------------------------------------------ 配方选择弹层 */

    function openRecipeModal(entry) {
        if (!entry || entry.id === undefined || entry.id === null) {
            return;
        }
        var id = entry.id;
        state.modalOpener = document.activeElement;
        state.modalOrigin = currentRoute().name;
        var fromPlan = state.modalOrigin === 'plan';

        var body = byId('modal-body');
        byId('modal-title').textContent = (entry.name ? String(entry.name) : '物品') + ' · 选择配方';
        clear(body);
        body.appendChild(el('div', 'box', '正在读取配方…'));
        showModal();

        apiItem(id).then(function (data) {
            clear(body);
            var item = data.item || entry;
            var recipes = Array.isArray(data.recipes) ? data.recipes : [];

            var head = el('div', 'row');
            head.appendChild(makeIcon(item, 'icon-40'));
            var info = el('div', 'material-info');
            info.appendChild(el('div', 'item-name', item.name ? String(item.name) : String(entry.name)));
            var meta = el('div', 'material-stat');
            meta.appendChild(el('span', 'need', '库存 ' + countText(item.stock)));
            meta.appendChild(document.createTextNode(' · 配方 ' + recipes.length + ' 条'));
            info.appendChild(meta);
            head.appendChild(info);
            body.appendChild(head);

            var rawToggle = el('label', 'toggle');
            var rawBox = document.createElement('input');
            rawBox.type = 'checkbox';
            rawBox.checked = isRaw(id);
            rawBox.addEventListener('change', function () {
                toggleRaw(id, rawBox.checked);
                if (fromPlan) {
                    refreshPlan();
                }
                // 勾上时如果这里已经指定过配方，得说清楚：两者冲突，raw 会赢
                var ignored = rawBox.checked && getChoice(id) !== '';
                toast(
                    (rawBox.checked ? '已把该物品当作原始材料，不再展开它的配方。' : '已恢复展开该物品的配方。')
                        + (ignored ? '注意：你给它指定的配方会被搁置。' : ''));
            });
            rawToggle.appendChild(rawBox);
            rawToggle.appendChild(el('span', null, '当作原始材料（不展开它的配方）'));
            body.appendChild(rawToggle);

            var cards = el('div', 'stack');
            if (recipes.length === 0) {
                cards.appendChild(el('div', 'card muted', '这个物品没有配方，只能作为原始材料准备。'));
            } else {
                // 和物品页一样按同类型合并：这个弹层里 23 条「Fluid Extractor Recycling」
                // 曾经是一条一张卡，手机上一屏都翻不完，还看不出它们其实是同一类做法
                var groups = groupRecipes(recipes);
                var selectHandler = function (rid) {
                    return function () {
                        var wasRaw = chooseRecipe(id, rid);
                        closeModal();
                        if (fromPlan) {
                            refreshPlan();
                        } else {
                            render();
                        }
                        toast(
                            '已更换配方'
                                + (wasRaw ? '，同时取消了「当作原始材料」——两者是冲突的' : '')
                                + (fromPlan ? '，正在重新规划。' : '。'),
                            'ok');
                    };
                };
                var clearHandler = function () {
                    setChoice(id, '');
                    closeModal();
                    if (fromPlan) {
                        refreshPlan();
                    } else {
                        render();
                    }
                    toast('已取消这个物品的配方选择，改为自动挑选。');
                };
                for (var g = 0; g < groups.length; g++) {
                    cards.appendChild(makeRecipeGroup(groups[g], {
                        itemId: id,
                        selectHandler: selectHandler,
                        onClear: clearHandler
                    }));
                }
            }
            body.appendChild(cards);

            if (getChoice(id)) {
                var cancel = btn('btn btn-ghost', '取消这个物品的配方选择');
                cancel.addEventListener('click', function () {
                    setChoice(id, '');
                    closeModal();
                    if (fromPlan) {
                        refreshPlan();
                    } else {
                        render();
                    }
                    toast('已取消配方选择，改为自动挑选。');
                });
                body.appendChild(cancel);
            }
        }).catch(function (err) {
            clear(body);
            if (err && err.name === 'IndexBuildingError') {
                body.appendChild(makeEmptyBox('索引尚未建立', err.message, '关闭', closeModal));
                armPending(function () {
                    openRecipeModal(entry);
                });
                return;
            }
            body.appendChild(makeEmptyBox('读取配方失败',
                err && err.message ? err.message : '请稍后再试。', '关闭', closeModal));
        });
    }

    function showModal() {
        byId('overlay').hidden = false;
        var modal = byId('modal');
        modal.focus();
        document.body.style.overflow = 'hidden';
    }

    function closeModal() {
        var overlay = byId('overlay');
        if (!overlay || overlay.hidden) {
            return;
        }
        overlay.hidden = true;
        document.body.style.overflow = '';
        clear(byId('modal-body'));
        var opener = state.modalOpener;
        state.modalOpener = null;
        if (opener && typeof opener.focus === 'function' && document.contains(opener)) {
            opener.focus();
        }
    }

    /* ------------------------------------------------------------ 启动 */

    function bindShell() {
        byId('modal-close').addEventListener('click', closeModal);
        byId('overlay').addEventListener('click', function (event) {
            if (event.target === byId('overlay')) {
                closeModal();
            }
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' || event.keyCode === 27) {
                closeModal();
            }
        });
    }

    function boot() {
        bindShell();
        installInstantTips();
        window.addEventListener('hashchange', render);
        // setTimeout 让浏览器先把骨架画出来，避免首屏空白
        window.setTimeout(function () {
            render();
            pollStatus();
        }, 0);
    }

    boot();
})();
