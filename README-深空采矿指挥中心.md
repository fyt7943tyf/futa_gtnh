# 深空采矿指挥中心

UV 级 7×7×N（2..9 层）多方块：塞入 X 级 Galacticraft 火箭后，在 GUI 里按星球配置**虚拟的** 1-4 级采矿场与流体钻机。虚拟机不加载任何区块——矿机用确定性算法离线复现真实星球的 GT 矿脉，钻机复现当地油田的上界产量。产出与功耗按 GT 原版机器的口径模拟，**不可超频**。

需要 Galacticraft（软依赖：没装时整个功能组不注册）。服务器权威模拟，客户端只负责 GUI 与渲染。

## 使用流程

1. **搭建结构**（7×7 底座 + 可叠加中间层 + 顶盖，2 到 9 层，NEI 结构预览可用）：
   - 底座：采掘桁架地板，**控制器在底座前中**，边缘可贴输入总线/输入仓/输出总线/输出仓/维护/能源仓；
   - 中间层：桁架边框 + 中央全息投影核心柱；
   - 顶盖：桁架边框 + 全息核心环 + 中央信号天线阵列。
2. **控制槽放入 Galacticraft 火箭**（任意 `ItemTier{N}Rocket`，等级决定可到达的星球）。火箭是「通行证」，不会被消耗。
3. 打开 GUI，**星球页**选一颗「已勘查」的星球（该维度至少被加载过一次，种子才会被记录；等级不够的星球显示为红色锁定）。
4. **配置页**用 +/- 配置各等级采矿场/流体钻机台数（受机位容量钳制），钻机可选目标油田流体。标题行悬停可预览该星球矿脉分布（名称: 权重）。
5. **运行页**看各虚拟机状态；「立即输出」手动倒缓冲，「矿脉重置」把该星球矿机采完的区域原地换新矿（era+1）。
6. 输入：输入总线/输入仓；输出：输出总线/输出仓。机器成型后正面贴图发光、顶盖天线向天射出上行链路光柱（仅运行时）。

## 数值口径（对齐 GT 5.09.54.175，勿手工重推）

### 虚拟采矿场（t = 1..4）

| 项 | 公式 | 数值 |
|---|---|---|
| 功耗 | `3·4^(t+1)` EU/t | 48 / 192 / 768 / 3072 |
| 周期 | `baseTime>>(t+1)` tick | 240 / 100 / 40 / 15 |
| 开采区域 | (2R)² 区块，R = 3/4/6/9 | 36 / 64 / 144 / 324 |
| 每周期产出 | 1 矿方块 → 打粉机配方 → 粉碎矿 ×(1~4 随机) | — |
| 钻井液 | 2000mB / 矿（`ItemList.sDrillingFluid`） | — |
| 下潜 | 管道 1 根/周期，深度 = 矿脉 veinMinY | — |

区域采完后 era+1 原地重置（`autoResetVein=false` 时改为软暂停）。区块按行优先顺序推进，队列不持久化，重载后按确定性算法重建并跳过已完成部分。

### 虚拟流体钻机（t = 1..4）

| 项 | 公式 | 数值 |
|---|---|---|
| 功耗 | `7·4^(t+1)` EU/t | 112 / 448 / 1792 / 7168 |
| 下潜 | 64 根管道后周期恒 8t | — |
| 抽取区域 | R×R 区块，R = 1/2/4/8 | — |
| 产量 | 区域内油田上界产量之和 ×`oilYieldFactor`（固定不衰减） | 默认 ×0.8 |

油田上界产量 = `floor(amount × 0.5 / 5000)`（amount 为 GT 五次幂分布 ±25% 抖动后的存量）。

### 共同口径

- **总功率 = 全部启用虚拟机 EU/t 之和**，不可超频；批次 20t（=1 秒）推进一次。
- **润滑油**：每台每秒 1000mB（`Materials.Lubricant`），批次结算。
- **机位容量** = `8 + (层数-2)×8`（9 层 64 台），GUI 配置时钳制。
- 产物：矿石 → 打粉机配方 → 粉碎矿 ×(1~4 随机倍率)；油田按 GUI 选中的流体过滤。

### 矿脉/油田种子链（确定性复现的核心）

- 矿脉：`oreveinSeed=(seed<<16)^((dim&0xff)<<56|(sx&0xfffffff)<<28|(sz&0xfffffff))` → `nextInt(100)<veinChance` → `FNV1a64` 两次 → `WorldgenQuery.veins().inDimension(dimName).findRandom` → `FNV1a64(attemptSeed, primary.getId())` → 5 次 `nextInt`（minY/westX/eastX/northZ/southZ）→ 9 层逐列放置。
- 种子区块：`floorMod(x,3)==1 && floorMod(z,3)==1`；影响半径 2（`GTWorldgenerator.MAX_VEIN_SIZE`）。
- 油田：8×8 区块共享油藏 `XSTR(seed+dim*2+(cx>>3)+8267*(cz>>3))`。
- 星球维度键 = GC 天体 name（`DimensionDef.DimNames`）；矿脉按 `layer.canGenerateIn(name)` 过滤，油田按数字 dimId 查 `GTMod.proxy.mUndergroundOil`。

## 调试

`/futaspacer planets` 列出星球表；`/futaspacer probe <dimName> <chunkX> <chunkZ> [era]` 输出该星球该区块的模拟矿脉与油田。**与真实星球（探矿仪 / Visual Prospecting / 实地开挖）对照，同 seed 应当一致**——这是验证确定性复现正确性的手段，也是 era 重置后检查分布是否符合权重的手段。

## 配置项（`futa_gtnh.cfg`）

| 键 | 默认 | 含义 |
|---|---|---|
| `enableSpaceMiningCenter` | true | 功能总开关（关闭时方块/MTE/事件都不注册） |
| `spaceMiningCenterMetaTileId` | 32703 | 控制器 MTE id（冲突时启动抛异常，提示改配置） |
| `spaceCenterMinHeight` / `spaceCenterMaxHeight` | 2 / 9 | 结构层数范围 |
| `spaceCenterBaseCapacity` | 8 | 基础机位（2 层时的容量） |
| `spaceCenterCapacityPerLayer` | 8 | 每加一层的机位增量 |
| `spaceCenterLubricantPerMachine` | 1000 | 每台每秒润滑油（mB） |
| `spaceCenterOilYieldFactor` | 0.8 | 钻机产量系数（固定，不随时间衰减） |
| `spaceCenterAutoResetVein` | true | 区域采完是否自动 era+1 原地重置 |

## 已知偏差（离线模拟 vs 实地开采）

1. **矿脉种类取「首个尝试」**：真实世界里矿脉种类由首个处理该种子的区块锁定；虚拟矿机从 (0,0) 向外扩张，与常规世界生成顺序一致，绝大多数情况相同，但理论上存在错位的可能。
2. **假设放置目标永远是石头**：真实星球的洞穴/空气会让部分列放不下矿，因此虚拟产量**略高于**实地同区块的可采量。
3. **era>0 的重置矿脉**：把 oreveinSeed 先过 FNV 哈希再混入 era，生成「分布规律与真实星球一致」的新矿脉，但内容与真实地形无对应关系（这是设计而非复现）。

## 技术说明

- **服务端权威**：全部模拟、资源扣取、产物缓冲都在服务端 `checkProcessing` 里推进（20t 批次），客户端只通过 StringSyncValue 编码串拿展示数据（`\u001F`/`\u001E` 分隔，本地化全在客户端做）。
- **星球种子捕获**：`PlanetSeeds` 挂 `WorldEvent.Load` 抓各维度 seed，存主世界 `WorldSavedData`；`PlanetRegistry` 惰性构建星球表（GC 天体 × GT 矿脉 × GT 油田，无矿无油的剔除）。
- **动画与性能**：结构方块（全息核心的星环几何体 + 扫描线、天线的呼吸灯）走 ISBRH 静态几何 + 原版贴图帧动画（`space_center_holo.png` / `space_center_antenna.png` 为竖条帧图 + `.mcmeta`），GPU 侧零逐帧开销；运行光柱走 `RenderWorldLastEvent`（每 20 tick 扫一次锚点缓存，仅 active 的机器画），粒子为稀疏的 magicCrit/portal。
- **Galacticraft 隔离**：`GcCompat` 是唯一的 GC 类型引用点（`ItemTier(\d+)Rocket` 类名识别火箭等级）；GC 未安装时 `SpaceCenterRegistration.register()` 直接跳过，服务端/客户端都不受影响。
