# 待发布：AE 一致性修复与 IO 搬运性能优化

> 对应反馈：①「仅有共享背包元件的 AE 网络里，AE 与共享背包数量不一致（材料还在、
> 产物没有、AE 取不出材料；从终端取出产物再从 AE 塞回去即可恢复）」；
> ②服务器性能下降（spark 报告：重 tick 中 `com.futa_gtnh` 占 79.2%，
> 热点集中在 `TerminalIoEngine.pushItems → TerminalOutputFilter.matches → ItemKey.of`）。

## 一、AE 一致性：增量推送链路为什么会断

AE 终端显示的数量来自 `NetworkMonitor.cachedList`，它**只在 `hasChanged` 置位后**才会
从元件重建。共享背包侧的写入（IO 节点收产物、远程终端取材料）不走 AE 的
inject/extract，AE 全靠 `AeCellBridge` 每 tick 末的 `postAlterationOfStoredItems` 补差量。
这条链路上有四个断点，断掉后 AE 视图冻结在旧状态——和玩家看到的现象（材料显示还在
但取不出 = 幽灵条目；产物不显示 = 漏发增量；对 AE 做一次写入立即恢复 =
`monitorDifference` 置位 `hasChanged` 触发全量重建）完全吻合：

1. **网格引用过期（最可能的主因）**。`CellEntry.grid` 与 handler 的网格在注册/构造时
   解析一次。AE 网络拆分/合并、区块卸载重载后，驱动器节点迁移到新 `IGrid`，旧引用
   指向死网格——增量全部推给了死网格，真实网络永远收不到通知。修复：handler 改为
   `currentGrid()` 现场解析（两次引用读取，无缓存），每 tick 末刷新全部条目的网格。
2. **`!anyActiveCells` 早退丢键**。注册表暂时为空（元件都在换网格 / 未注册 / 错误
   淘汰后）期间，监听器直接 return，变更键**永久丢失**且无补救路径。修复：监听器只看
   `installed` + 回声抑制，键一律排队；flush 时注册表为空则**保留队列**，元件回来后
   第一批推送补发（键集合有幂等性，上限 = 条目种类数）。网格一个都解析不出来时同样
   把已取出的键塞回队列。
3. **区块卸载的元件永不淘汰**。原淘汰信号只有 `Platform.postChanges` 的 null-host
   差量查询，而区块卸载/方块破坏不触发驱动器库存变更事件，条目带着死宿主死网格一直
   留在表里。修复：新增「宿主 TileEntity 已失效」淘汰信号（仍受 `registeredThisTick`
   保护，不误杀刚插入的元件）。
4. **推送失败静默丢弃 / machineSource 为空直接跳过**。修复：任何推送异常或拿不到动作
   来源时，对该网络退回一次 `MENetworkCellArrayUpdate` 全量重同步（绝对量收敛），
   不再丢差量。

连带修复：

- **流体元件 host 硬编码 null**：流体元件从不进注册表——只有流体元件的网络收不到
  任何推送，同网络多枚流体元件不去重（AE 显示翻倍）。现在两个通道都传真实宿主。
- **`serveContent` 不认 handler 换代**：驱动器 `updateState` 重建 handler 或新元件
  撞上被淘汰元件的 `identityHashCode` 时，旧条目（死网格）继续当家，新元件可能被判
  非 primary 而拒绝服务。现在以「当前来服务的 handler」为准刷新条目。
- **绝对量当差量发**：本 AE2 分支的 `postAlterationOfStoredItems` 是差量语义
  （`postChange(true,…)` 原样转发数量）。终端恰好会重读绝对值所以此前未爆雷，但对
  任何按差量累加的监听者是污染。现在维护 `lastPosted` 基准（元件自身写入也会同步
  基准），只发真正的变化，且差量只计算一次、所有网络共用。

## 二、IO 搬运性能

spark 热点的根因：`pushItems` 每轮对**全量快照**的**每个条目**做 `prototype()`
（NBT 深拷贝）+ `matchesOutput(ItemStack)` → `ItemKey.of`（又一次 NBT 深拷贝 +
identity hash）+ 缓存 `equals`（NBT 深比较）；空闲（目标满/无匹配条目）也一样全量
做，没有任何退避。修复分四层：

1. **键直查白名单**：`TerminalOutputFilter.matches(ItemKey)` / `matches(FluidKey)`
   与 `TerminalIoConfig.matchesOutput` 的键重载。仓库快照里的键本来就是规范化的，
   命中缓存时零分配、零 NBT 拷贝、`==` 快速相等；只有缓存未命中（新条目/筛选刚改）
   才构造一次原型栈查矿辞。`pushItems`/`pushFluid` 改为先过键白名单、通过且额度
   未用尽的条目才构造原型栈。
2. **共享快照缓存**：`SharedStorage.snapshotItemsCached()/snapshotFluidsCached()`
   按 revision 缓存一份只读列表，同 tick 的所有节点/终端复用同一次拷贝（原来每个节点
   每轮各自全量拷贝）。被动流体路径（`drain/canDrain/getTankInfo`）与
   `refreshOutputKeys` 一并换用。
3. **空闲指数退避**：一轮什么都没搬动（= 无需 IO）时，下一轮等待按
   `配置间隔 → 4 → 8 → 16 → 32 → 64 → 128 tick` 逐级翻倍，封顶 128 tick（6.4 秒）；
   一旦真的搬动了东西立刻回到配置节奏。空闲节点的全量扫描开销最多降到原来的
   1/128（5 tick 档位为 1/25）。新配置 `ioIdleBackoff`（默认 true = 退避；
   设 false 恢复恒定轮询旧行为，供对延迟敏感的场合使用）。
4. **杂项热路径**：`TileEntitySharedTerminal.visibleAmount` 不再为读堆叠上限构造
   原型栈（按键缓存上限）；`getAccessibleSlotsFromSide`/`refreshOutputKeys`/`canExtractItem`
   的输出校验走键重载。

## 行为与兼容

- 存档格式、网络协议、GUI 通道零变化；`ioIdleBackoff` 是新增服务端配置键，缺省即生效。
- 退避只影响「空闲节点」的轮询节奏；搬运语义（守恒、档位上限、筛选）完全不变。
- AE 侧改动对既有元件即插即用；流体现在正确参与同网络去重（同网络多枚流体元件
  不再重复计数）。

## 验证

- `gradlew spotlessApply build` 全绿（含既有全部回归任务）。
- `gradlew verifyAeCraftingDelivery --rerun-tasks`：93 项断言通过（合成投递数量守恒
  未受差量改造影响）。
- 未起本地客户端/服务器实测；建议上线后复测 spark：
  `/spark profiler start --timeout 600 --only-ticks-over 40`，对照基线
  （重 tick 占 17.4%、`com.futa_gtnh` 占重 tick 79.2%、`ItemKey.of` 占 42.7%）。
