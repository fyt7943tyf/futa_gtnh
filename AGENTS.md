# Repository Guidelines

## 项目结构与模块组织

- `src/main/java/com/futa_gtnh/` 存放 Java 模组代码，按功能划分为 `client`、`common`、`network`、`shared`、`locator`、`station` 等包；`FutaGtnhMod.java` 是模组入口类。
- `src/main/resources/` 存放 `mcmod.info`、Mixin 配置、翻译文件，以及 `assets/futa_gtnh/` 下的纹理资源。
- `docs/` 存放开发和 API 笔记；`tools/pinyin/` 存放独立的辅助工具。
- `gradle.properties`、`dependencies.gradle` 和 `build.gradle.kts` 定义 GTNH/RetroFuturaGradle 构建流程。请保留 `gtnhShared/` 中的格式化配置；发布版本只修改 `version.txt`。

## 构建、测试与开发命令

使用 Gradle Wrapper，并准备 JDK 17 或更高版本（项目文档使用 JDK 25）：

```powershell
.\setup.bat                         # 首次建立 Minecraft/GTNH 开发工作区
.\gradlew.bat build                 # 编译、检查格式并打包模组
.\gradlew.bat clean build            # 清理产物后重新构建
.\gradlew.bat spotlessCheck          # 检查 Java 和资源文件格式
.\gradlew.bat spotlessApply          # 应用项目配置的格式化规则
.\gradlew.bat runClient17            # 启动现代 Java/LWJGL3 开发客户端
.\gradlew.bat runServer17            # 启动现代开发服务端
```

依赖解析异常或缓存过期时，可追加 `--refresh-dependencies`。本项目使用 lwjgl3ify，不要运行 `runClient`，应使用 `runClient17`。

## 编码风格与命名约定

使用 UTF-8、LF 换行、四个空格缩进，并删除行尾空白。遵循仓库的 Spotless Eclipse 配置和导入顺序（`java`、`javax`、`net`、`org`、`com`）。包名使用小写，类型名使用 `UpperCamelCase`，方法和字段使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。请保持模组 ID `futa_gtnh` 和包根 `com.futa_gtnh` 不变。

## 测试规范

当前没有单独的自动化测试源码集，也没有覆盖率要求。至少运行 `gradlew.bat build` 和 `gradlew.bat spotlessCheck`；根据改动内容使用 `runClient17` 或 `runServer17` 手动验证客户端和服务端行为。涉及界面、本地化、网络或资源的改动，应在适用时同时验证客户端和服务端。

## 提交与拉取请求规范

近期提交使用简洁的中文摘要和类型前缀，例如 `修：...`、`新功能：...` 或 `README：...`；新提交也应保持具体、明确并以动作为导向。拉取请求应说明行为变化、列出执行过的验证命令，并在有对应事项时附上链接；界面或纹理改动应附截图。协议、容器或共享状态改动必须说明客户端与服务端的兼容性。不要提交 `build/`、`run/`、日志、本地 `libs/` 或生成的第三方产物。
