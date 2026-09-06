@rem Gradle startup script for Windows
@echo off
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
