# MUI 界面审计与改造优化清单

> 对全部 ModularUI 2 界面的一次性审计结论与修复跟踪表。
> 基准：GregTech 自家覆盖板 GUI 约定（`CoverBaseGui` / `CoverControlsWorkGui` / `CoverFluidfilterGui`）
> 与 ModularUI 2 框架源码。审计范围：`futa_gtnh` 全库引用 `com.cleanroommc.modularui` 的 7 个文件。

---

## 审计结论（2026-10 摸底）

| 界面 | 文件 | sync 值注册 | 本地化端 | 布局体系 | 字号 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| AE 无线接收端覆盖板 | `aewireless/CoverAeSignalReceiverGui.java` | **5 个未注册（崩溃）** | **状态行在服务端** | Flow/Grid 混用 | 0.8/0.9/1.0 三档 | 问题最多 |
| ME 超级对接站 | `medock/MTEAeSuperDock.java` | 全部已注册 | **信息行在服务端** | 绝对 pos | 0.7/0.8/0.9/1.0 四档 | 两处问题 |
| LV 拆解机 | `disassembler/MTELVDisassembler.java` | 全部已注册 | 客户端（正确） | 绝对 pos | 0.7/1.0 | 仅页码对齐小问题 |
| 原版按钮镜像层 | `client/ui/FutaUiLayer.java` | 不涉及 | 客户端 | 对齐原版 | 统一 | 干净 |
| 其余（主题/齿轮按钮/覆盖板逻辑类） | `FutaUiTheme` / `ClientFeatureButtonOverlay` / `CoverAeSignalReceiver` | 不涉及 | — | — | — | 干净 |

两类框架级机制（修复的依据）：

- **未注册的 sync 值必崩**：`SyncHandler` 只有经 `PanelSyncManager.syncValue(name, handler)` 注册才会拿到
  manager 引用；未注册的值第一次交互就抛
  `IllegalStateException: Sync handler is not yet initialised!`（玩家点击即崩客户端）。
- **`StringSyncValue` 的 getter 在服务端求值**：专用服务端只加载 en_US，服务端拼好的本地化文本
  同步给中文客户端就是英文（中英混排）。本地化必须发生在客户端。

---

## 修复清单

状态：☐ 待办 · ☑ 已完成 · ✗ 明确不做

### P0 — 崩溃级

| # | 问题 | 位置 | 修法 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 5 个 sync 值未注册，点按钮/改数字即崩 | `CoverAeSignalReceiverGui`：`threshold`(L260)、`condition`(L274)、`action`(L302)、`interval`(L328)、`linkLost`(L342) | `PanelSyncManager` 传入各 `createXxxRow()`，补 `syncValue("threshold"/"condition"/"action"/"interval"/"link_lost", …)` | ☑ |

### P1 — 服务器上必现的功能缺陷

| # | 问题 | 位置 | 修法 | 状态 |
| --- | --- | --- | --- | --- |
| 2 | 状态行服务端本地化，多人服务器中文客户端显示英文 | `CoverAeSignalReceiverGui` `status`/`statusText()` | 改同步原始数据（`link_state` 序号 + `last_amount` 数值），客户端 `IKey.dynamic` 内本地化拼句 | ☑ |
| 3 | 信息行服务端本地化（含 `getDisplayName`/`getLocalizedName`） | `MTEAeSuperDock` `info`/`infoLine()` | 服务端只编码原始数据（模式/数量/容量/物品 id+meta+自定义名/流体名），客户端解码并本地化 | ☑ |

### P2 — 接收端覆盖板 GUI 样式统一（截图问题主体）

| # | 问题 | 位置 | 修法 | 状态 |
| --- | --- | --- | --- | --- |
| 4 | Flow/Grid 混用、各行左边距不齐 | `CoverAeSignalReceiverGui` 9 行 | 统一 `row()` 帮助方法（`marginLeft(WIDGET_MARGIN)` + `marginBottom(4)`）；Grid 补 `minElementMarginTop(0).minElementMarginLeft(0)` 对齐 GT 模板 | ☑ |
| 5 | 图标语义不一致：同一"否定"语义两种叉（CROSS/FORBIDDEN）、"保持现状"用叉、类型开关用勾叉 | 同上 | 统一映射：启用/停用=CHECKMARK/FORBIDDEN；≥/≤=REDSTONE_ON/REDSTONE_OFF（GT 机器控制器惯例）；保持=LOCK（中性）；物品/流体开关=LIQUIDMODE/LIQUIDMODE_OFF（GT 混凝土回填机惯例） | ☑ |
| 6 | 字号三档混用（0.8/0.9/1.0） | 同上 L240/L386 | 统一 1.0，说明文本改措辞（lang 同步缩短） | ☑ |
| 7 | 间距两档并存（marginLeft 8/4、marginRight 4/6、行底 4/2） | 同上 | 全部收敛一档（4） | ☑ |
| 8 | 行内 `IKey.dynamic` 文本垂直错位（MUI2 对尺寸未算好的子控件跳过居中）；类型行标签与 tooltip 重复 | 同上 L116-118/L219-223 | Flow 行内动态文本加 `.verticalCenter()`；类型行删重复的 tooltipDynamic | ☑ |

### P3 — 轻量点修

| # | 问题 | 位置 | 修法 | 状态 |
| --- | --- | --- | --- | --- |
| 9 | 单界面字号四档（0.7/0.8/0.9/1.0） | `MTEAeSuperDock` L639/L646/L651 | 收敛两档：标题/优先级/信息 1.0、提示 0.8 | ☑ |
| 10 | 页码动态文本夹在固定按钮间，右侧间隙随内容宽窄跳动 | `MTELVDisassembler.pageButtons()` L507-516 | 页码文本固定宽度居中 | ☑ |
| 11 | 标题与副标题零行距；输入框双重描边（drawRect + SLOT 贴图） | `GuiAeSignalTransmitter` L110-111/L103 | 副标题下移；删 drawRect | ☑ |

### 明确不做（仅记录）

| # | 问题 | 理由 |
| --- | --- | --- |
| 12 | 拆解机/对接站从绝对 pos 迁移 Flow/Grid | 功能正常的风格孤岛，专项重构回归风险大于收益；待下次功能改动时顺手处理 |

---

## 验证清单（实机）

1. 接收端覆盖板 GUI：**逐个点击全部可交互控件**（发信端翻页、类型开关、幽灵物品槽/流体槽、
   阈值/间隔输入、≥/≤、启用/停用、保持/启用/停用）——零崩溃。
2. 中文客户端连专用服务器：接收端覆盖板状态行、对接站信息行均为中文。
3. 对照旧截图：文字与按钮垂直居中、各行左缘对齐、同类按钮图标一致、字号统一。
4. 对接站分别放入箱子和储罐物品，信息行内容正确（含带自定义名的物品）。
