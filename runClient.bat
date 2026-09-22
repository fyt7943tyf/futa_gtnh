@echo off
rem ============================================================
rem  Launch the development client with the mod loaded.
rem  Requires JDK 17+ (GTNH toolchain); JDK 25 recommended.
rem ============================================================
setlocal
cd /d "%~dp0"
call gradlew.bat runClient %*
pause
endlocal
