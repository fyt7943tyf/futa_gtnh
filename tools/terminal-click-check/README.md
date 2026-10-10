# 终端点击回归验证

`gradlew.bat verifyTerminalClicks` 执行实际 `GuiSharedTerminal` / 原版 `GuiContainer` 的按下、拖动和释放路径，验证快速 Shift 连点每次仅取一组、库存总量守恒、Shift 右键、丢失释放事件后的普通点击、光标双击收集及背包 Shift 双击存入。

测试以独立 JVM 运行。`org.lwjgl.Sys` 和 `org.lwjgl.input.Keyboard` 仅固定时钟和键盘状态；出站点击以测试传输层调用真实 `InventoryExchange` / `SharedStorage`。不启动渲染或网络。LWJGL 2 解包到 `build/terminal-click-check/lwjgl` 以解除测试类加载的包封装限制。

这些源码位于主源码集之外，测试替身与解包的第三方类均不会进入发布 jar。`build` 的 `check` 阶段自动运行本验证。

`gradlew.bat verifyTerminalNeiSearch` 使用实际 NEI 共用解析器、名称/矿辞/提示文本/ID 提供器、英文索引及 GT 流体显示物品，验证普通搜索、ALWAYS/PREFIX/NEVER 配置、自定义前缀、扩展/正则语法、插件提供器、拖入名称转义、流体身份与未安装 NEI 的本地回退。配置和词库仅在独立测试 JVM 中设置，不修改用户整合包配置；本验证随 `build` 自动运行。

`gradlew.bat verifyTerminalSearch` 使用真实 NEI `GuiContainerManager` 的键盘/点击分发以及 `PanelWidget` 的拖拽结束路径。验证聚焦时先输入字符而不触发快捷键（包括已到长度上限）、失焦后恢复快捷键、NEI 消费框外点击也能失焦、共享槽 Shift 点击提前返回时失焦、右键清空仅同步一次、拖入物品填充去除颜色代码的名称并结束虚拟拖拽且保留真实背包物品，以及输入法字符和模态对话框隔离。字体测量与出站点击使用测试替身，不启动渲染或网络；本验证也随 `build` 自动运行。
