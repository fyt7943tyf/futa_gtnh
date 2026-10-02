# 共享终端输出回归检查

在项目根目录运行：

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle'
.\gradlew.bat --offline --no-configuration-cache -I tools/gradle/spotless-cache.init.gradle -I tools/terminal-io-check/verify.init.gradle verifyTerminalIo
```

检查使用真实 MC / Forge 类型和内存仓库，不启动游戏、不加载存档、不建立网络连接。
覆盖六面独立白名单、空名单禁止输出、主动推送、被动抽取、输入不筛选、精确 NBT、矿辞匹配、配置迁移和虚拟物品槽位的数量守恒。

客户端与服务端应同时更新。游戏内仍需核对输出筛选页的方向切换，以及实际 GT 传送带、物品管道和流体管道的行为。
