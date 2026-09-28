# vendor/ — 版本钉死的联动兼容 jar

这两个 jar 由对应的本地源码仓库构建后拷入，并**提交进仓库**：`dependencies.gradle` 以相对路径引用它们，这样本地与 GitHub Actions CI 的编译环境完全一致，不依赖任何机器上的绝对路径。两者都是可选联动，运行时缺席不影响模组其余功能（调用点已隔离守卫）。

| jar | 来源 | 用途 |
|---|---|---|
| `EnhancedLootBags-1.0.0-local-dev.jar` | Enhanced LootBags 源码树（`eu.usrv.enhancedlootbags`）`gradlew build` 产物 | 自选抽奖机的开袋模拟编译兼容 |
| `vendingmachine-0.4.82-local-dev.jar` | Vending Machine 源码树（`com.cubefury.vendingmachine`）`gradlew build` 产物 | 抽奖机「重置次数」代币钱包的编译兼容 |

## 升级方式

1. 在对应源码仓库里 `gradlew build`；
2. 把新的 `*-local-dev.jar` 拷进本目录覆盖旧版；
3. 同步修改 `dependencies.gradle` 里的文件名（如有版本变化）；
4. 本地 `gradlew build` 验证后再提交。

## 说明

- 这两个 jar 只进 `compileOnly` 与 dev 运行时 classpath，**不会**打进发布产物（见 `dependencies.gradle` 注释）。
- 上游 GTNH maven 的 ELB 已是 1.3.x（API 有代差）、Vending Machine 不在 GTNH maven 上，因此用 vendor jar 而不是 maven 坐标。
