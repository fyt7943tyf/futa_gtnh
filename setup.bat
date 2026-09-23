@echo off
rem ============================================================
rem  First-time setup: download + decompile Minecraft 1.7.10
rem  and build the GTNH workspace. Takes a while, only once.
rem  Requires JDK 17+ (GTNH toolchain); JDK 25 recommended.
rem ============================================================
setlocal
cd /d "%~dp0"
call gradlew.bat setupDecompWorkspace -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10809 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10809 %*
echo.
echo ==== Done ====
pause
endlocal
