@echo off
rem ============================================================
rem  Build mod jar. Output: build\libs\
rem  Requires JDK 17+ (GTNH toolchain); JDK 25 recommended.
rem ============================================================
setlocal
cd /d "%~dp0"
call gradlew.bat build %*
echo.
echo ==== Output: build\libs\ ====
dir /b build\libs 2>nul
pause
endlocal
