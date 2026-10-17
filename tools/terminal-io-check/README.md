# 共享终端输出回归检查

在项目根目录运行：

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle'
.\gradlew.bat --offline --no-configuration-cache -I tools/gradle/spotless-cache.init.gradle verifyTerminalIo
```

检查使用真实 MC / Forge 类型和内存仓库，不启动游戏、不加载存档、不建立网络连接。
测试专用 `appeng.core.Api` 提供空的物品定义和特殊比较注册表，避免加载无关方块及可选能源联动；`AEItemStack`、NBT 转换和 `OreFilteredList` 均使用真实依赖类。测试配置仅写入 `build/terminal-io-check/`。
`build` 的 `check` 已包含此任务。覆盖六面独立白名单、空名单禁止输出、主动推送、被动抽取、输入不筛选、精确 NBT 和虚拟物品槽位的数量守恒。

矿辞检查逐项对照真实 AE2 `OreFilteredList`：精确匹配、大小写、前后及中间通配符、逻辑组合及重复操作数、正则模式、无效输入、无矿辞物品、NBT 和耐久变体、缓存刷新、表达式原文保存与恢复。旧配置检查遍历 32 种预设组合和多种重叠自定义前缀，验证迁移后匹配范围不变，反复保存不会再次迁移。

客户端与服务端应同时更新。游戏内仍需核对输出筛选页的方向切换，以及实际 GT 传送带、物品管道和流体管道的行为。
