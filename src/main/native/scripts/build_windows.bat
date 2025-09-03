@echo off
setlocal enabledelayedexpansion

echo ======================================================
echo        PathFinder Native Library - Windows Build
echo ======================================================

:: 设置编码为UTF-8
chcp 65001 > nul

:: 检查必要的工具
echo [1/6] 检查构建工具...

:: 检查Visual Studio Build Tools
where cl.exe > nul 2>&1
if !errorlevel! neq 0 (
    echo ❌ 未找到Visual C++编译器 (cl.exe)
    echo 请安装以下之一:
    echo   - Visual Studio 2019/2022 (含C++开发工具)
    echo   - Visual Studio Build Tools
    echo   - 或运行 "Developer Command Prompt for VS"
    goto :error
)
echo ✅ Visual C++编译器已找到

:: 检查CMake
where cmake.exe > nul 2>&1
if !errorlevel! neq 0 (
    echo ❌ 未找到CMake
    echo 请从 https://cmake.org/download/ 下载并安装CMake
    goto :error
)
echo ✅ CMake已找到

:: 检查vcpkg (推荐但非必需)
where vcpkg.exe > nul 2>&1
if !errorlevel! neq 0 (
    echo ⚠️ 未找到vcpkg，将尝试使用系统已安装的库
    echo 推荐安装vcpkg以简化依赖管理: https://github.com/Microsoft/vcpkg
    set USE_VCPKG=false
) else (
    echo ✅ vcpkg已找到
    set USE_VCPKG=true
)

:: 检查Java环境
echo [2/6] 检查Java环境...
if "%JAVA_HOME%"=="" (
    echo ❌ JAVA_HOME环境变量未设置
    echo 请设置JAVA_HOME指向JDK安装目录
    goto :error
)

if not exist "%JAVA_HOME%\include\jni.h" (
    echo ❌ 在JAVA_HOME中未找到JNI头文件
    echo 请确保JAVA_HOME指向完整的JDK安装目录
    goto :error
)
echo ✅ Java环境已设置: %JAVA_HOME%

:: 创建构建目录
echo [3/6] 准备构建目录...
set BUILD_DIR=%~dp0..\build
set OUTPUT_DIR=%~dp0..\..\..\..\target\native\windows

if exist "%BUILD_DIR%" (
    echo 清理旧的构建目录...
    rmdir /s /q "%BUILD_DIR%"
)
mkdir "%BUILD_DIR%" > nul 2>&1

if not exist "%OUTPUT_DIR%" (
    mkdir "%OUTPUT_DIR%" > nul 2>&1
)

:: 设置CMake工具链
echo [4/6] 配置CMake...
cd "%BUILD_DIR%"

:: 如果使用vcpkg，设置工具链文件
if "%USE_VCPKG%"=="true" (
    for /f "tokens=*" %%i in ('where vcpkg.exe') do set VCPKG_ROOT=%%~dpi..
    set TOOLCHAIN_FILE=-DCMAKE_TOOLCHAIN_FILE="%VCPKG_ROOT%\scripts\buildsystems\vcpkg.cmake"
    echo 使用vcpkg工具链: %VCPKG_ROOT%
) else (
    set TOOLCHAIN_FILE=
    echo 使用系统默认库查找
)

:: 配置CMake项目
echo 运行CMake配置...
cmake .. ^
    -G "Visual Studio 16 2019" ^
    -A x64 ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DJAVA_HOME="%JAVA_HOME%" ^
    %TOOLCHAIN_FILE%

if !errorlevel! neq 0 (
    echo ❌ CMake配置失败
    echo 常见问题解决方案:
    echo   1. 确保安装了OpenSSL和libcurl开发库
    echo   2. 使用vcpkg安装依赖: vcpkg install openssl curl
    echo   3. 或者手动下载预编译库并设置CMAKE_PREFIX_PATH
    goto :error
)

:: 构建项目
echo [5/6] 构建项目...
cmake --build . --config Release --parallel

if !errorlevel! neq 0 (
    echo ❌ 构建失败
    goto :error
)

:: 复制结果文件
echo [6/6] 复制构建结果...
if exist "Release\pathfinder_native.dll" (
    copy "Release\pathfinder_native.dll" "%OUTPUT_DIR%\" > nul
    echo ✅ DLL文件已复制到: %OUTPUT_DIR%\pathfinder_native.dll
) else (
    echo ❌ 未找到构建的DLL文件
    goto :error
)

:: 显示结果
echo.
echo ======================================================
echo            🎉 Windows构建成功! 🎉
echo ======================================================
echo 构建产物:
echo   📁 DLL文件: %OUTPUT_DIR%\pathfinder_native.dll
echo   📊 文件大小: 
for %%A in ("%OUTPUT_DIR%\pathfinder_native.dll") do echo      %%~zA 字节
echo.
echo 下一步:
echo   1. 将DLL文件复制到Java资源目录
echo   2. 在Java代码中加载对应的native库
echo   3. 运行gradle构建整个项目
echo.

goto :end

:error
echo.
echo ❌ 构建失败!
echo 请查看上面的错误信息并解决问题后重试
exit /b 1

:end
pause 