@ECHO OFF
SET APP_HOME=%~dp0

IF NOT "%JAVA_HOME%"=="" (
  SET JAVA_CMD=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_CMD=java.exe
)

"%JAVA_CMD%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
