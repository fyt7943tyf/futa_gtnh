# futa_gtnh — GTNH 模组开发环境

> Minecraft 1.7.10 / **GTNH（GregTech: New Horizons）** 附属模组。
> 使用 GTNH 官方工具链：**RetroFuturaGradle (RFG) + GTNHGradle + Gradle 9.3.1 + JDK 25**。

## 快速上手

| 步骤 | 操作 | 说明 |
| --- | --- | --- |
| 1（仅首次） | 双击 `setup.bat` | 下载并反编译 Minecraft 1.7.10、建立 GTNH 工作区（较慢，只需一次） |
| 2 | 编辑 `src/main/java/com/futa_gtnh/` | 写你的模组逻辑 |
| 3 | 双击 `build.bat` | 编译打包，产物在 `build\libs\` |
| 4 | 双击 `runClient.bat` | 启动带模组的开发版客户端 |

命令行等价操作：

```
gradlew.bat build                      # 编译打包
gradlew.bat runClient                  # 开发版客户端
gradlew.bat runServer                  # 开发版服务端
gradlew.bat --refresh-dependencies build   # 依赖出问题时强制刷新
gradlew.bat clean                      # 清理 build 产物
```

> ⚠️ **不要再运行 `gradlew8.bat`**（那是旧环境留下的，已废弃）。
> 本工具链需要 **JDK 17 或更高**，本机用的是 `C:\Program Files\Microsoft\jdk-25.0.2.10-hotspot`。

## 环境组成

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | Microsoft OpenJDK 25.0.2 | 需 JDK 17+ |
| Gradle | 9.3.1（wrapper 自动下载） | 由 `gradle/wrapper/` 锁定 |
| Forge | 10.13.4.1614 | `gradle.properties` 的 `forgeVersion` |
| MCP 映射 | stable / 12 | `channel` + `mappingsVersion` |
| 构建插件 | GTNHGradle 2.0.20 + RFG 2.0.2 | 由 `settings.gradle.kts` 引入 |

## 目录结构

```
futa_gtnh/
├── build.gradle.kts              # 构建设置（版本号从 version.txt 读，一般不用改）
├── settings.gradle.kts           # 引入 GTNH 构建插件（含 GTNH Maven 地址）
├── gradle.properties             # ★ 主要配置：modId / modName / Mixin / Java 语法等
├── dependencies.gradle           # ★ 添加模组依赖（GregTech / NEI 等）在这里
├── repositories.gradle           # 额外的依赖仓库
├── version.txt                   # ★ 版本号（改这里，jar 名会跟着变）
├── src/main/java/com/futa_gtnh/
│   ├── FutaGtnhMod.java          # 模组主类（@Mod 注解）
│   ├── CommonProxy.java          # 客户端/服务端共用逻辑
│   ├── ClientProxy.java          # 仅客户端逻辑
│   └── Config.java               # 配置文件读写
├── src/main/resources/
│   ├── mcmod.info                # 模组元信息（用 ${modId} 等占位符，构建时替换）
│   └── mixins.futa_gtnh.json     # Mixin 配置清单（文件名 = mixins.<modId>.json）
├── gtnhShared/                   # Spotless 代码格式配置（勿删）
├── legacy-forgegradle/           # 旧版 ForgeGradle 1.2 环境的备份（可删）
└── run/                          # runClient/runServer 的工作目录（自动生成）
```

## 修改模组信息

| 想改什么 | 改哪里 |
| --- | --- |
| **版本号** | `version.txt` |
| modid / 名称 / 包名 | `gradle.properties` 的 `modId` / `modName` / `modGroup` |
| 作者 / 描述 | `src/main/resources/mcmod.info` |
| 依赖 | `dependencies.gradle` |
| 启用/关闭 Mixin | `gradle.properties` 的 `usesMixins` |

> **注意**：modid 一旦发布就不要改（存档、配置文件、其他模组的依赖声明都认它）。
> 改 `modGroup`（包名）时，`src/main/java/` 下的目录结构和 `build.gradle.kts` 里的
> `generateGradleTokenClass` 也要同步改。

## 版本号机制（容易踩坑）

GTNH 默认用 **Git tag** 当版本号。本项目不在 Git 仓库里，所以：

- `gradle.properties` 里设了 `gtnh.modules.gitVersion = false` 关掉 Git 版本推断；
- 关掉后必须在构建脚本里提供版本号，`build.gradle.kts` 从 `version.txt` 读取，
  同时设置 `project.version`（决定 jar 文件名）和 `project.ext.modVersion`（RFG 内部要用）；
- 只有一处需要维护：**改 `version.txt`**。

如果以后把项目放进 Git 仓库并打了 tag，可以删掉那行 `gtnh.modules.gitVersion = false` 恢复自动版本号。

## Mixin

`gradle.properties` 里 `usesMixins = true`，Mixin 基础设施已开好。组成：

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/mixins.futa_gtnh.json` | **Mixin 配置清单**，必须存在，否则启动直接崩 |
| `src/main/java/com/futa_gtnh/mixins/` | Mixin 类所在包（`mixinsPackage` 指向它，包必须存在） |
| jar 的 `MANIFEST.MF` | GTNHGradle 自动写入 `MixinConfigs: mixins.futa_gtnh.json` |

**当前状态**：配置清单里 `"mixins": []` 是空的，包里只有一个 `package-info.java`
占位，**没有任何 Mixin 在运行**。客户端/服务端都能正常启动。

> ⚠️ **重要**：`mixins.futa_gtnh.json` 必须和 manifest 里 `MixinConfigs` 声明的文件名一致。
> 文件名由 `gradle.properties` 的 `modId` 自动推导（`mixins.<modId>.json`）。
> 如果这个文件缺失或 JSON 不合法，游戏会在启动时直接抛
> `MixinInitialisationError: Error initialising mixin config mixins.futa_gtnh.json`。
> 之前踩过这个坑：只把 `usesMixins` 打开、却没提供配置文件。

### 要开始写 Mixin

1. 在 `src/main/java/com/futa_gtnh/mixins/` 下新建 Mixin 类；
2. 把类名（不含包名）加进 `mixins.futa_gtnh.json` 的 `"mixins"` 数组，
   仅客户端的加进 `"client"`，仅服务端的加进 `"server"`；
3. 参考 [GTNH 官方 Mixin 范例](https://github.com/GTNewHorizons/ExampleMod1.7.10#mixins)。

GTNH 推荐用 **IMixins**（GTNHLib 提供）来注册，比原生写法更简洁统一；
需要完全控制加载时机时才用 Early/Late Mixin。

> 改完记得 `gradlew.bat build`。如果发现新资源没被打进 jar，用
> `gradlew.bat clean build`（增量构建偶尔会漏掉新增的 resources 文件）。

### 如果暂时不想用 Mixin

把 `gradle.properties` 的 `usesMixins` 改成 `false` 即可 ——
manifest 里就不会再写 `MixinConfigs`，也不需要 `mixins.futa_gtnh.json`。
（但依赖了 Mixin 的第三方模组仍需要 Mixin 运行时，所以 `forceEnableMixins` 保持 `true` 更省事。）

## 已配置的依赖

`dependencies.gradle` 里已经加好 GTNH 三件套（都是 `compileOnly`，不进产物 jar）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| GT5-Unofficial | 5.09.54.175 | GregTech 本体，GT 的机器/配方 API |
| NotEnoughItems | 2.8.144-GTNH | NEI 配方查看器 |
| GTNHLib | 0.11.51 | GTNH 公共库 |

> 这些用的是 GTNH Maven 上的 **`-dev` classifier** 产物，本身已经是反混淆过的，
> **不需要**再套 `rfg.deobf(...)`。
> 注意 GregTech 很大（约 68MB）且带 30 多个传递依赖，首次配置依赖时要下载不少东西。

想加本地 jar：放进 `libs/`，然后写 `compileOnly(files("libs/xxx.jar"))`。

## Java 语法

`gradle.properties` 里 `enableModernJavaSyntax = jabel`，可以用现代的 Java **语法**
（`var`、switch 表达式、文本块等），但编译产物仍是 Java 8 字节码。
注意 Jabel **只开语法、不开 API** —— Java 9+ 新增的类/方法（如 `List.of()`）依然不能用。

## Windows Defender 误报（已处理，换机器时需要重做）

本机 **Windows Defender 曾把 `GTNHExtLib-1.0.4.jar` 误报为病毒并锁死该文件**，
导致 `javac` 读不到它、编译整体失败（报错形如
`无法成功完成操作，因为文件包含病毒或潜在的垃圾软件`）。

该文件是 GTNH 官方库，位置：

```
C:\Users\fyt79\.gradle\caches\modules-2\files-2.1\com.github.GTNewHorizons\GTNHExtLib\1.0.4\
```

核对过哈希：下载后的 **SHA1 = `71BA0EB42623E9BA1D60C7CDFE87C8562245A4C7`**，
与 GTNH Nexus 上的官方产物**逐字节一致**，没有被动过手脚 —— 纯粹是杀软误报。

**已通过把 Gradle 缓存目录加入 Defender 排除项解决**：

```powershell
Add-MpPreference -ExclusionPath "C:\Users\fyt79\.gradle\caches"   # 需管理员
```

图形界面路径：`Windows 安全中心` → `病毒和威胁防护` → `病毒和威胁防护设置` → `管理设置` →
`排除项` → `添加或删除排除项` → 添加**文件夹** `C:\Users\fyt79\.gradle\caches`。

依赖已恢复完整（`dependencies.gradle` 里不再有 `exclude ... GTNHExtLib`）。

> **换电脑 / 重装系统后**如果又出现这个报错，按上面加一次排除项即可。
> 若一时拿不到管理员权限，临时办法是在 `dependencies.gradle` 的 GTNHLib 依赖后面加：
> ```groovy
> compileOnly("com.github.GTNewHorizons:GTNHLib:0.11.51:dev") {
>     exclude group: "com.github.GTNewHorizons", module: "GTNHExtLib"
> }
> ```
> 但这样就用不了依赖 `GTNHExtLib` 的 GTNH 内容，只适合应急。

## 常见问题

1. **`This mod must be version controlled by Git AND the repository must provide at least one Git tag`**
   → 已经处理过（`gtnh.modules.gitVersion = false` + `version.txt`）。若报错请检查这两处还在不在。
2. **`Cannot get property 'modVersion' on extra properties extension`**
   → 同上，`build.gradle.kts` 里 `ext.set("modVersion", ...)` 丢了。
3. **依赖下载失败 / 域名解析错误**
   → 多为网络抖动，重跑一次；或 `gradlew.bat --refresh-dependencies build`。
   本项目在国内网络下已验证可下载（GTNH Nexus、Prism Launcher Maven、Mojang 均可达）。
   另外首次构建时遇到过 `files.prismlauncher.org` 解析失败，属偶发，重试即通过。
4. **`无法成功完成操作，因为文件包含病毒或潜在的垃圾软件`**
   → 见上面「Windows Defender 误报」一节，按推荐方式加排除项。
5. **构建脚本提示有新版本**
   → `gradlew.bat updateBuildScript`。注意这会把 `build.gradle.kts` 换回官方版本，
   而本项目在里面加了读取 `version.txt` 的逻辑，更新后需要重新加回去。
6. **想看详细报错**
   → `gradlew.bat build --stacktrace`，或 `--info` / `--debug`。
7. **想彻底重置**
   → 删掉项目下的 `build\`、`.gradle\`，再跑 `setup.bat`。

## 参考

- [GTNH 官方 ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10)（构建系统来源，有 FAQ 和 mixin 范例）
- [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)（1.7.10 的现代 Gradle 插件）
- [GTNH 开发文档](https://gtnh.miraheze.org/wiki/Development)
- FML 生命周期：`FMLPreInitializationEvent` → `FMLInitializationEvent` → `FMLPostInitializationEvent`
