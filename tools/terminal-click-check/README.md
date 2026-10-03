# 终端点击与拆解机 UI 回归验证

`gradlew.bat verifyTerminalClicks` 执行实际 `GuiSharedTerminal` / 原版 `GuiContainer` 的按下、拖动和释放路径，验证快速 Shift 连点每次仅取一组、库存总量守恒、Shift 右键、丢失释放事件后的普通点击、光标双击收集及背包 Shift 双击存入。

测试以独立 JVM 运行。`org.lwjgl.Sys` 和 `org.lwjgl.input.Keyboard` 仅固定时钟和键盘状态；出站点击以测试传输层调用真实 `InventoryExchange` / `SharedStorage`。不启动渲染或网络。LWJGL 2 解包到 `build/terminal-click-check/lwjgl` 以解除测试类加载的包封装限制。

`gradlew.bat verifyDisassemblerUi` 构造实际拆解机的客户端/服务端 UI，验证两组多页输出的请求权限、页码序列化与服务端应用、越界请求以及只开放当前页。还执行 NEI 原版 `drawForeground` 路径：`GL11` 仅跳过两个 GL 状态调用，纹理管理器验证实际资源可解码为图片，字体替身记录配方说明绘制，并检查机器配方组入口。

这些源码位于主源码集之外，测试替身与解包的第三方类均不会进入发布 jar。`build` 的 `check` 阶段自动运行本验证。
