# futa_gtnh 只读仓库 API v1

适用版本：futa_gtnh `1.21.0`。本 API 提供唯一的全服物品/流体仓库快照，不依赖 AE2、AE2-WEB、玩家在线状态、终端方块或队伍系统。

## 调用入口

入口类为 `com.futa_gtnh.api.SharedStorageReadApi`：

```java
int version = SharedStorageReadApi.getApiVersion(); // 当前为 1，可从任意线程调用
SharedStorageReadStatus status = SharedStorageReadApi.getStatus();
SharedStorageSnapshot snapshot = SharedStorageReadApi.snapshot();
```

`getStatus()` 和 `snapshot()` 必须在 Minecraft 服务端主线程调用。启动完成后，API 通过启动事件记录的线程检查调用者；其他线程调用会抛 `IllegalStateException`。HTTP 线程需要先通过接入方的主线程任务队列调度。尚未首次启动时，API 只返回 `NOT_READY` 和空行，不读取临时仓库；停服后同样返回未就绪状态，原服务端线程约束仍然有效。

API 版本查询不访问仓库，也不要求服务器已启动。可选接入方应先检测 `futa_gtnh` 是否加载，再隔离加载含本 API 类型引用的兼容类。旧 futa 版本没有该 API 时应报告不兼容，不要让整个 Web 服务启动失败。

## 状态与快照

`SharedStorageReadStatus` 提供：

| 方法 | 语义 |
| --- | --- |
| `getState()` | 枚举 `READY` / `NOT_READY` |
| `isReady()` | 是否已加载实际仓库，包括已加载的空仓库 |
| `getGeneration()` | UUID 字符串；启动、换存档、成功替换仓库/重载和停止时更新，不持久化 |
| `getRevision()` | int 内容版本；仅 READY 时有效，不应比较大小，允许回绕 |

`getStatus()` 不复制库存，适合读取就绪状态。只有 `snapshot()` 返回的状态与它自己的库存行属于同一次锁内读取；不要把先前 `getStatus()` 的 revision 与后取得的快照当成同一版本。

`SharedStorageSnapshot` 提供 `getStatus()`、`getItems()`、`getFluids()`。物品、流体和 revision 在持有同一个 `SharedStorage` 锁期间取得。列表不可修改；每行保存原型和独立 long 数量，构造时、调用 `getPrototype()` 时均复制 NBT。

```java
SharedStorageSnapshot snapshot = SharedStorageReadApi.snapshot();
if (!snapshot.getStatus().isReady()) {
    // 返回来源未就绪；不能把它当成“仓库为空”。
    return;
}
for (SharedStorageSnapshot.ItemEntry row : snapshot.getItems()) {
    ItemStack prototype = row.getPrototype(); // stackSize == 1
    long amount = row.getAmount();
    // 用 prototype 的注册名、metadata、NBT 生成资源描述；用 amount 生成数量。
}
for (SharedStorageSnapshot.FluidEntry row : snapshot.getFluids()) {
    FluidStack prototype = row.getPrototype(); // amount == 1
    long amountMb = row.getAmount();           // mB，保留完整 long
}
```

`NOT_READY` 的快照有空列表，但只能依据状态判断未就绪；`READY` + 空列表才是真正空仓库。无法解析而在存档隔离区保留的条目不作为可用资源返回。

数量不经 `stackSize`/`FluidStack.amount` 中转，不夹到 int。输出 JSON 时建议另外提供 `Long.toString(row.getAmount())` 的 `amountExact`，浏览器用 BigInt 或精确字符串处理。不要从数量为 1 的原型反推仓库总量。

## 缓存与生命周期

缓存比较使用 generation + revision，以及接入方自身 HTTP 服务生命周期。generation 随 UUID 重建，解决重载后新仓库 revision 与旧仓库相同的问题。generation 是仓库实例变化标识，不是可持久保存的网络/队伍 ID；网页固定来源 ID 仍可以为 `futa_gtnh`。

每次 `snapshot()` 都按需复制 O(N) 条目，API 不另建快照缓存，也不做持续轮询。更新频率由接入方控制；首版使用手工刷新即可。

API 读取不触发保存、重载、广播或写入；改变返回原型的数量和 NBT 不会改变仓库或已保存的快照原型。既有游戏内存取、NEI/3×3 合成和持久化格式不变。旧 `snapshotItems()` / `snapshotFluids()` 也已复制为不可改写的 Entry，仓库后续增减不会修改这些已返回 Entry 的数量。

## 验证与下游编译

针对性回归入口：

```powershell
.\gradlew.bat verifySharedStorageReadApi --no-configuration-cache
```

使用真实 MC/Forge 开发类和独立内存仓库，检查 long 数量、原型/NBT 隔离、不可变列表、旧 Entry 副本、物品/流体原子快照、启动/重载/停止 generation 和错误线程拒绝。重载样本只读临时空目录，不使用实际游戏存档。

验证源码在 `tools/read-api-check/`，独立任务也挂接到 `check`，普通 `build` 会执行；`assemble` 只打包。验证进程通过隔离的 LaunchClassLoader 初始化原版注册表，不启动游戏窗口、游戏服务器或访问现有存档。测试中以 null server 进入内存模式的日志是预期的隔离样本行为，不代表真实服务端启动验证。

在当前环境，格式化器的 Equo 缓存把 `user.home` 解析到 `C:\`，导致 `C:\.m2` 访问失败。提供可选初始化脚本把缓存限定到仓库 `.gradle/spotless-p2/`，不修改插件/依赖版本：

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle"
$env:HTTP_PROXY = "http://127.0.0.1:1081"
$env:HTTPS_PROXY = "http://127.0.0.1:1081"
$env:GRADLE_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=1081 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1081"
.\gradlew.bat -I tools/gradle/spotless-cache.init.gradle build spotlessCheck --no-configuration-cache
```

该脚本仅用于缓存路径异常的环境；正常环境可以直接运行仓库既有构建命令。格式化器加载 Equo 库时脚本使用其缓存路径覆盖机制，缓存不会提交到仓库。

下游 AE2-WEB 在开发时以 `build/libs/futa_gtnh-1.21.0-dev.jar` 作为 compileOnly 依赖；正式服务端安装的是 `futa_gtnh-1.21.0.jar`。本次只完成 futa 侧桥接，AE2-WEB 的适配器、HTTP API 和前端来源选择仍需另行开发。
