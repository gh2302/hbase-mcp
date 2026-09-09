@echo off
REM Launches the HBase MCP server on stdio.
REM HBase 2.6 supports Java 17 at most, so prefer JAVA_HOME_17 if it is set.
setlocal

set "JAR=%~dp0..\target\hbase-mcp.jar"

if defined JAVA_HOME_17 (
  set "JAVA_EXE=%JAVA_HOME_17%\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if not exist "%JAR%" (
  echo hbase-mcp: %JAR% not found. Run "mvn package" first. 1>&2
  exit /b 1
)

REM Never echo to stdout here: it is the JSON-RPC channel.
"%JAVA_EXE%" -jar "%JAR%" %*
