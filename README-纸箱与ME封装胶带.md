# 纸箱与 ME 封装胶带

两个「把设备连数据一起搬走」的工具：纸箱面向任意带方块实体的方块（GT 机器友好），ME 封装胶带面向 AE2 存储设备。

## 纸箱

玩法移植自 [GT-Not-Leisure](https://github.com/GTNotLeisure/GT-Not-Leisure)（分支 `dev-290`）的纸箱（其代码源自 Mekanism 的 Cardboard Box，MIT 署名）：

- **合成**：纸×8 + 粘液球（中空箱体，工作台）。
- **装箱**：拿空纸箱右键任意带方块实体的方块（潜行右键 = 普通放置）。目标被原地替换为纸箱方块，方块实体的完整 NBT（机器库存、设置、GT 元方机器、覆盖板）全部保留；GT 机器额外记录 MetaTileID 供 tooltip 展示。
- **搬运**：破坏纸箱掉落带数据的纸箱物品；放置后**潜行右键拆箱**，原方块在纸箱位置原样恢复（朝向等 metadata 原样保留，坐标已改写），并弹出一个空纸箱。
- **黑名单**：门类、纸箱自身、神龙反应堆核心/混沌水晶、IC2 核反应堆（后两类尽力解析，mod 没装或注册名对不上时自动落空）。配置 `cardboardBoxBlacklist` 可追加，格式 `modid:注册名`。硬度 -1（不可破坏）与保护区域同样不可打包。
- **超大 NBT 落盘**：方块实体 NBT 压缩后超过 16 KB 时，数据存入主世界 `data/futa_gtnh_cardboard_<uuid>.dat`，物品里只留 uuid；拆箱/创造拆除时删除文件（思路取自 GTNL，独立实现）。
- **替换期掉落抑制**：装箱替换方块的瞬间挂 `EntityJoinWorldEvent` 取消个别模组方块实体失效时吐出的物品实体（数据已在快照里，重复掉落等于刷物品）。

实现见 `com.futa_gtnh.cardboard` 包：`BlockCardboardBox` / `ItemBlockCardboardBox`（装箱右键走 onItemUseFirst + 专用 C2S 包，与太阳能除钙剂同一管线，GT 机器的 GUI 抢不到这次点击）、`TileEntityCardboardBox`、`CardboardBoxData`（快照与还原）、`CardboardBoxStore`（NBT 存取与落盘）、`CardboardBoxBlacklist`、`CardboardBoxOps`（装箱/拆箱流程）、`CardboardBoxDropGuard`。网络包 `PacketCardboardBoxUse`（判别号 50）。

## ME 封装胶带

玩法移植自现代版 [ExtendedAE](https://github.com/GlodBlock/ExtendedAE)（LGPL-3.0）的 me_packing_tape / package，按 1.7.10 的 AE2（GTNH fork rv3）从零实现：

- **合成**：福鲁伊克斯粉×2 + 粘液球×2 + 纸×3 + 铁锭（对角线胶带卷造型）。胶带 64 点耐久，每封一台设备耗 1 点。
- **封装**：右键白名单里的 AE 设备，设备变「封装物品」（优先入背包，装不下才落地），完整数据保留：驱动器里的元件、接口的样板/配置/升级、朝向、优先级、颜色。
- **默认白名单（存储设备组）**：ME 驱动器、ME 接口方块、ME 接口部件、ME 箱子、IO 传输总线。内建条目走 AE2 的 definitions API 解析；配置 `meTapeWhitelist` 可追加，格式 `block:modid:注册名` 或 `part:modid:物品名:子id`（AE2 所有部件共用一个物品，必须带子id精确匹配）。
- **放回**：右键放回 —— 方块设备放在点击面外侧；部件设备对齐 AE2 原版放部件的三段式（点到总线本体 / 点击面外侧已是总线 / 在外侧位置新建线缆总线再贴部件），所以「右键箱子侧面贴回接口」和原版手感一致。

实现见 `com.futa_gtnh.aetape` 包（本包内所有 AE2 触碰集中在 `MeTapeOps`）+ 无 AE 依赖的物品类 `item/ItemMePackingTape`、`item/ItemMePackage`（封装产物，无配方不进创造栏）。方块设备走完整方块实体 NBT（`AEBaseTile.writeToNBT` 是 final 的，元件/朝向/状态一网打尽）；部件设备走 AE2 的 def/extra 惯例（与 `CableBusContainer` 的 NBT 格式一致），拆卸/放回流程对齐 `PartPlacement.wrenchLogic` / `placeItemPart`，含 BreakEvent/PlaceEvent 保护模组兼容。网络包 `PacketMeTapeUse`（51）、`PacketMePackageUse`（52）。

## 配置

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `enableCardboardBox` | true | 注册纸箱（方块 + 物品 + 配方） |
| `cardboardBoxBlacklist` | 空 | 追加黑名单，`modid:注册名` |
| `enableMePackingTape` | true | 注册 ME 封装胶带（需 AE2） |
| `meTapeWhitelist` | 空 | 追加白名单，`block:modid:注册名` / `part:modid:物品名:子id` |

## 授权信息

纸箱部分按 GPL-3.0 署名惯例随 jar 分发修改后的源码（`META-INF/licenses/gtnl/source`，与拆解机微光迁移同一套做法），原始设计署名 Mekanism（MIT）与 GT-Not-Leisure（GPL-3.0）。ME 封装胶带为本仓原创实现，玩法参考 ExtendedAE（LGPL-3.0）。

## 验证

`gradlew build` 与 `spotlessCheck` 通过（含全部既有回归任务）；未启动客户端/服务端，游戏内行为待实测。贴图由 `tools/texture/gen_packing_textures.py` 生成（原创 16×16）。
