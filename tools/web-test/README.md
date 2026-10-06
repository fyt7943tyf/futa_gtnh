# 网页接口自测脚本

合成向导（[README-合成向导.md](../../README-合成向导.md)）的接口是「游戏里跑着服务、
手机去看」的形态，拿手机一条条点很慢，改完后端也不容易看出哪里坏了。
这里三个脚本用 Node 直接打接口、或者直接驱动浏览器去点，把「字段契约」
「规划结果的结构不变量」和「点下去会发生什么」都检查一遍。

## 用法

先开游戏（客户端），确认日志里有「网页配方：服务已启动」；然后：

```bash
node tools/web-test/smoke.js     # 接口契约：38 项
node tools/web-test/fuzz.js      # 规划压力：沿目录抽样 40 个物品
node tools/web-test/ui.js        # 页面交互：无头 Edge 真的去点
node tools/web-test/groups.js    # 书签组兼容：旧清单迁移、切组、旧键保留
node tools/web-test/basket-times.js  # 「做几套」：兼容、按比例回落、切组后仍在
node tools/web-test/settings.js  # 本地数据页（清空 / 导出 / 恢复）
node tools/web-test/target-stock.js  # 「目标不扣库存、中间产物照扣」（纯 HTTP）
```

`ui.js` / `groups.js` / `basket-times.js` / `settings.js` 都**自己起一个无头 Edge**
（各自的临时 `--user-data-dir`），不碰你正在用的浏览器 —— 它们会 `localStorage.clear()`，
连错浏览器就是把别人的清单清了。

**点按钮要用真鼠标**：`element.click()` 是直接派发事件，元素被别的东西盖住也照样"成功"。
破坏性按钮（删除本组之类）在 `groups.js` 里走 CDP 的 `Input.dispatchMouseEvent`，
并且先检查按钮中心点上"最上层的是谁"、再断言**全程没有系统对话框**
（`window.confirm` 被浏览器吞掉时，表现就是"点了没反应"）。

改前端时不用重开游戏：`node tools/web-test/serve-live.js` 在 8766 起一个代理，
页面文件取 `build/resources/main/assets/futa_gtnh/web`（先跑一次 `gradlew processResources`），
`/api/*` 转给正在跑的游戏。然后 `--base http://127.0.0.1:8766` 跑上面这些脚本。

环境变量：

| 变量 | 作用 |
|---|---|
| `BASE` | 服务地址，默认 `http://127.0.0.1:8765` |
| `SAMPLE` | `fuzz.js` 抽多少个物品，默认 40 |

三个脚本都只用 Node 18+ 自带的 `fetch`（`ui.js` 另外用到 Node 22+ 的 `WebSocket`），
没有第三方依赖。

## 它们各自盯什么

**`smoke.js`** —— 一次把每个接口都走一遍：状态 / 静态页面 / 搜索（中文、拼音、`@模组`）/
物品详情 / 图标 / 规划 / `choices`（指定配方）/ `raw`（当作自己准备）/ 库存扣减 / 错误处理。
除了字段有没有、类型对不对，还检查：配方里每个槽位都坐得进它自己声明的网格、
每条配方的 `resultId` 确实指向被查询的那个物品。

**`fuzz.js`** —— 沿物品目录的多个区段抽样（只取前 200 个的话全是模组列表最前面那几家的东西，
覆盖不到 GT 的深层配方树），逐个规划，专门盯这几类「一眼看不出、但会毁掉整份指导」的错误：

- 步骤顺序排错（某一步要的材料，它的配方却排在后面）；
- 自产自销（某一步的材料就是它自己的产物）；
- 空材料步骤（配方在物品形态上「凭空产出」）；
- 数量算成 0 或负数、还缺算成负数；
- 目标既出现在「要准备的材料」里、又出现在「要做的步骤」里。

脚本只读不写，不会改动游戏里的任何状态。

**`ui.js`** —— 无头 Edge + CDP，真的去点：在物品页选中组装机配方 → 打开计划页 →
确认每一步左侧都有物品图标、用了手动指定配方的那一步带「已选配方」标识 →
把鼠标移到图标上确认 hover 真的命中 → 点图标确认配方弹层打开 →
勾掉一步确认「现在可以做」里等它的那一步会冒出来、进度能存下来、清零能归零 →
清空选择后确认标识消失。

静态截图证明不了「点了会怎样」，所以这部分必须是行为断言。
它启动的是独立的无头实例（自己的 `--user-data-dir`），不碰你正在用的浏览器；
改的是它自己那份 `localStorage`，也不会影响手机上的选择。

规划结果的依赖关系（`needs`/`level`）还额外验三条不变量：步骤号从 1 连续、
依赖只指向更靠前的步骤、批次号随依赖递增。这三条一旦破了，
「现在可以做」算出来的东西就是错的，而那种错在界面上看着很正常。

## 页面本身怎么看

上面两个脚本验的是接口，看不出 CSS/JS 的问题（比如某个元素盖住了整页）。
改完页面用无头浏览器截一张最快：

```bash
msedge --headless=new --disable-gpu --window-size=400,1520 \
  --screenshot=shot.png "http://127.0.0.1:8765/#/item/3142"
```

**`--window-size` 在无头模式下不一定真的改掉布局视口**：实测给它 390 宽，
页面仍按 800 宽排版，截出来右边被裁掉，看着像「横向溢出」，其实是假象。
要验手机布局，把页面套进一个 `width:390px` 的 iframe 再截图才是准的：

```html
<iframe src="http://127.0.0.1:8765/#/item/3142"
        style="width:390px;height:1500px;border:0"></iframe>
```

页面里几个位置可以直接用 hash 路由打开，省得一路点进去：
`#/search`、`#/item/<物品id>`、`#/plan/<物品id>?count=64`。

## 离线诊断工具（不用重开游戏）

「网页上看着不对」时，先分清是**索引里的数据不对**还是**规划算法不对**。
下面这两个工具直接读索引缓存（`config/futa_gtnh/web_recipes.dat`），
不用开游戏、不用连接口，最适合查「这个 id 到底是谁」「这件东西的配方到底有没有进索引」：

```bash
java tools/web-test/DumpIndexKeys.java <缓存> 36885,43173        # id ↔ 身份键（流体形如 fluid:显示名）
java tools/web-test/DumpRecipes.java  <缓存> --handlers          # 每个处理器各贡献多少条配方
java tools/web-test/DumpRecipes.java  <缓存> --produces 36885     # 谁产出这个 id（材料一并列出）
java tools/web-test/DumpRecipes.java  <缓存> --item 36885         # 什么配方用到它（产出/消耗都算）
java tools/web-test/DumpRecipes.java  <缓存> --handler alloysmelter  # 按处理器标签翻（ASCII，避免中文乱码）
```

两个都用 JDK 的单文件模式直接跑（`java 文件.java`），不需要编译。

**`fluid-identity.js`**（`node tools/web-test/fluid-identity.js`）盯的是流体身份：
同一种流体在 GTNH 里有两套显示物品（GT 的 `GregTech_FluidDisplay` 和 NEI 的
`neiFluidDisplay`），它们在玩家眼里是同一个东西。脚本断言每个流体名在目录里找得到、
同名的几条**指向同一种流体**（库存数字必须一致），并且能规划的那一条在计划里可用 ——
「可用」包括**已被库存覆盖**（那时计划就是 0 步：不需要再做）。
另可用 `--alias <重复编号> <正式编号>` 核对老编号会被指回正式那条。

> 搜名字要翻页找：搜索是「名字 + 拼音」的子串匹配，搜「水」会命中一千多条
> （紫水晶、水桶、粗水之魔晶矿石…），精确同名的那条可能排在很后面。

排查「明明能做却报缺」时，规划接口加 `&debug=1`：警告里会逐条写出被掐断的物品
**各自选中了哪条配方、材料是谁** —— 光看材料表只知道它被掐断了，看不出是被哪条配方带进环里的。
