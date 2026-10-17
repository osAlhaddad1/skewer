@echo off
rem One command: start Postgres, build if needed, run the Skewer CLI.  skewer demo | skewer matrix
setlocal
cd /d "%~dp0"
docker compose up -d --wait >nul || exit /b 1
if not exist experiments\target\skewer.jar (
  echo building...
  call mvnw.cmd -q -B -DskipTests install || exit /b 1
)
java -Dstdout.encoding=UTF-8 -jar experiments\target\skewer.jar %*
