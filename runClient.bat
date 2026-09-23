@echo off
rem ============================================================
rem  Launch the development client with the mod loaded.
rem  Requires JDK 17+ (GTNH toolchain); JDK 25 recommended.
rem
rem  ★ 用的是 runClient17，不是 runClient，这一行不能改回去。
rem    原因（详见 README-开发环境.md 的「lwjgl3ify 与 dev 环境」一节）：
rem      - runClient 会用 Java 8 启动。dev 环境的 classpath 里挂着 lwjgl3ify
rem        （dependencies.gradle 的 runtimeOnlyNonPublishable），它的
rem        Lwjgl3ifyRelauncherTweaker 一旦发现「本进程还没被 RFB 引导过」
rem        就会把游戏重新拉起来；而那次重启用的是 lwjgl3ify 自带的「正式版」
rem        classpath（forge + 原版 client + LWJGL3，模组只从 mods 文件夹读），
rem        Gradle 的 dev classpath 被整个丢掉 —— 结果所有 coremod 都
rem        ClassNotFoundException，最后死在 java.util.jar.Pack200 上。
rem      - runClient17 用 JetBrains Runtime 17 直接启动，并带上
rem        -Djava.system.class.loader=…RfbSystemClassLoader，RFB 在「本进程」
rem        就被引导起来（blackboard 里有 lwjgl3ify:rfb-booted），relauncher
rem        于是自动跳过，dev classpath 完整保留。
rem    同理：跑服务端要用 runServer17。
rem ============================================================
setlocal
cd /d "%~dp0"
call gradlew.bat runClient17 %*
pause
endlocal
