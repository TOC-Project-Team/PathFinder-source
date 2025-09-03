cmake_minimum_required(VERSION 3.10)
project(PathFinderNativeValidator)

# 设置C标准
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 平台检测
if(WIN32)
    set(PLATFORM_NAME "windows")
    set(LIB_EXTENSION "dll")
elseif(APPLE)
    set(PLATFORM_NAME "macos")
    set(LIB_EXTENSION "dylib")
elseif(UNIX)
    set(PLATFORM_NAME "linux")
    set(LIB_EXTENSION "so")
else()
    message(FATAL_ERROR "Unsupported platform")
endif()

# 查找JNI
if(CMAKE_CROSSCOMPILING AND WIN32)
    # 交叉编译时，手动设置JNI路径（优先使用Windows头文件，缺失则回退Linux）
    if(DEFINED JAVA_HOME)
        if(EXISTS "${JAVA_HOME}/include/win32")
            set(JNI_INCLUDE_DIRS 
                "${JAVA_HOME}/include"
                "${JAVA_HOME}/include/win32"
            )
            message(STATUS "Cross-compilation: Using Windows JNI headers from ${JAVA_HOME}")
        else()
            set(JNI_INCLUDE_DIRS 
                "${JAVA_HOME}/include"
                "${JAVA_HOME}/include/linux"
            )
            message(WARNING "win32 JNI headers not found under JAVA_HOME; falling back to linux headers")
        endif()
        message(STATUS "Cross-compilation: Using JNI headers from ${JAVA_HOME}")
    else()
        message(FATAL_ERROR "JAVA_HOME must be set for cross-compilation")
    endif()
else()
find_package(JNI REQUIRED)
endif()

# 平台特定的依赖查找
if(WIN32)
    # Windows特定依赖
    if(CMAKE_CROSSCOMPILING)
        # 交叉编译时，跳过某些依赖或使用简化的查找
        message(STATUS "Cross-compiling for Windows - using simplified dependencies with fallback support")
        
        # 对于交叉编译，我们跳过网络库但提供fallback验证机制
        # 这样可以在没有CURL/OpenSSL的情况下仍然提供基本验证
        set(USE_OPENSSL FALSE)
        set(USE_CURL FALSE)
        
        # 如果用户设置了这些库的路径，则尝试使用
        if(DEFINED OPENSSL_ROOT_DIR OR DEFINED OpenSSL_ROOT)
            find_package(OpenSSL QUIET)
            if(OpenSSL_FOUND)
                set(USE_OPENSSL TRUE)
                message(STATUS "Found OpenSSL for cross-compilation")
            endif()
        endif()
        
        if(DEFINED CURL_ROOT OR DEFINED CURL_ROOT_DIR)
            find_package(CURL QUIET)
            if(CURL_FOUND)
                set(USE_CURL TRUE)
                message(STATUS "Found CURL for cross-compilation")
            endif()
        endif()
        
    else()
        # 原生Windows编译
find_package(OpenSSL REQUIRED)
        find_package(CURL REQUIRED)
        set(USE_OPENSSL TRUE)
        set(USE_CURL TRUE)
    endif()
    
    # Windows不需要pthread（使用Windows线程API）
    set(PTHREAD_LIB "")
    
    # Windows特定的系统库
    set(PLATFORM_LIBS ws2_32 crypt32 wldap32 normaliz advapi32 kernel32 winhttp)
else()
    # Unix系统（Linux/macOS）
    find_package(OpenSSL REQUIRED)
find_package(CURL REQUIRED)
find_package(Threads REQUIRED)
    set(PTHREAD_LIB Threads::Threads)
    set(USE_OPENSSL TRUE)
    set(USE_CURL TRUE)
    
    if(APPLE)
        # macOS特定库
        set(PLATFORM_LIBS "-framework CoreFoundation" "-framework Security")
    else()
        # Linux特定库
        set(PLATFORM_LIBS dl m)
    endif()
endif()

# 包含目录
include_directories(${JNI_INCLUDE_DIRS})
if(USE_OPENSSL)
include_directories(${OPENSSL_INCLUDE_DIR})
endif()
if(USE_CURL)
include_directories(${CURL_INCLUDE_DIRS})
endif()
include_directories(include)

# 源文件
set(SOURCES
    src/startup_validator.c
    src/machine_code.c
    src/native_plugin_manager.c
)

# 创建共享库
add_library(pathfinder_native SHARED ${SOURCES})

# 平台特定的编译选项
if(WIN32)
    # 如果是交叉编译（使用MinGW），使用GCC风格的选项
    if(CMAKE_CROSSCOMPILING)
        target_compile_options(pathfinder_native PRIVATE
            -O3                   # 最高优化级别
            -Wall                 # 开启警告
            -Wextra               # 额外警告
            -DNDEBUG             # 禁用调试信息
            -DWIN32_LEAN_AND_MEAN # 减少Windows.h包含
            -DNOMINMAX            # 避免min/max宏冲突
        )
        
        # 交叉编译链接选项
        target_link_options(pathfinder_native PRIVATE
            -static-libgcc        # 静态链接libgcc
            -static-libstdc++     # 静态链接libstdc++
            -Wl,--strip-all       # 移除符号表
            -Wl,--export-all-symbols  # 导出所有非静态符号，确保JNI入口可见
        )
    else()
        # 原生Windows编译（MSVC）
        target_compile_options(pathfinder_native PRIVATE
            /O2                    # 优化
            /W3                    # 警告级别
            /D_CRT_SECURE_NO_WARNINGS  # 禁用MSVC安全警告
            /DWIN32_LEAN_AND_MEAN  # 减少Windows.h包含
            /DNOMINMAX             # 避免min/max宏冲突
            /DNDEBUG              # 禁用调试信息
        )
        
        # Windows链接选项（MSVC）
        target_link_options(pathfinder_native PRIVATE
            /LTCG                  # 链接时代码生成
            /OPT:REF               # 移除未引用的函数
            /OPT:ICF               # 相同代码合并
        )
    endif()
    
    # Windows特定定义
    target_compile_definitions(pathfinder_native PRIVATE 
        WINDOWS_PLATFORM
        _WIN32_WINNT=0x0601    # Windows 7+
    )
    
    # 根据依赖可用性添加条件编译定义
    if(NOT USE_OPENSSL)
        target_compile_definitions(pathfinder_native PRIVATE NO_OPENSSL)
    endif()
    if(NOT USE_CURL)
        target_compile_definitions(pathfinder_native PRIVATE NO_CURL)
    endif()
    
elseif(APPLE)
    # macOS编译选项
    target_compile_options(pathfinder_native PRIVATE
        -O3                    # 最高优化级别
        -fPIC                  # 位置无关代码
        -Wall                  # 开启警告
        -Wextra                # 额外警告
        -ffunction-sections    # 将每个函数放在独立段中
        -fdata-sections        # 将每个数据放在独立段中
        -DNDEBUG              # 禁用调试信息
    )
    
    # macOS链接选项
    target_link_options(pathfinder_native PRIVATE
        -dead_strip            # 移除未使用的代码
        -Wl,-x                 # 移除本地符号
    )
    
    # macOS特定定义
    target_compile_definitions(pathfinder_native PRIVATE MACOS_PLATFORM)
    
else()
    # Linux编译选项 - 保持原有的安全加固选项
target_compile_options(pathfinder_native PRIVATE
    -O3                    # 最高优化级别
    -fomit-frame-pointer   # 移除栈帧指针，增加逆向难度
    -fstack-protector-all  # 栈保护
    -D_FORTIFY_SOURCE=2    # 缓冲区溢出保护
    -fPIC                  # 位置无关代码
    -Wall                  # 开启警告
    -Wextra                # 额外警告
    -ffunction-sections    # 将每个函数放在独立段中
    -fdata-sections        # 将每个数据放在独立段中
    -DNDEBUG              # 禁用调试信息
)

    # Linux链接选项 - 安全加固
target_link_options(pathfinder_native PRIVATE
    -Wl,--gc-sections      # 移除未使用的段
    -Wl,--strip-all        # 移除所有符号
    -Wl,-z,relro           # 重定位保护
    -Wl,-z,now             # 立即绑定
    -Wl,-z,noexecstack     # 禁用可执行栈
    -Wl,--no-undefined     # 禁止未定义符号
)

    # Linux特定定义
    target_compile_definitions(pathfinder_native PRIVATE LINUX_PLATFORM)
endif()

# 链接库 - 根据可用性条件链接
if(CMAKE_CROSSCOMPILING AND WIN32)
    # 交叉编译时不链接JNI库（运行时由JVM提供）
    set(LINK_LIBRARIES ${PTHREAD_LIB} ${PLATFORM_LIBS})
else()
    set(LINK_LIBRARIES ${JNI_LIBRARIES} ${PTHREAD_LIB} ${PLATFORM_LIBS})
endif()

if(USE_OPENSSL)
    list(APPEND LINK_LIBRARIES ${OPENSSL_LIBRARIES})
endif()

if(USE_CURL)
    list(APPEND LINK_LIBRARIES ${CURL_LIBRARIES})
endif()

target_link_libraries(pathfinder_native ${LINK_LIBRARIES})

# 设置输出目录 - 按平台分别存放
set(OUTPUT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../target/native/${PLATFORM_NAME})
set_target_properties(pathfinder_native PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    RUNTIME_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    ARCHIVE_OUTPUT_DIRECTORY ${OUTPUT_DIR}
)

# Windows特定设置
if(WIN32)
    # 设置DLL导出
    set_target_properties(pathfinder_native PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
)
endif()

# 自定义目标：代码混淆
add_custom_target(obfuscate
    COMMAND ${CMAKE_COMMAND} -E echo "Applying code obfuscation for ${PLATFORM_NAME}..."
    # 这里可以添加代码混淆工具的调用
    DEPENDS pathfinder_native
)

# 自定义目标：打包加密
add_custom_target(pack
    COMMAND ${CMAKE_COMMAND} -E echo "Packing and encrypting library for ${PLATFORM_NAME}..."
    # 这里可以添加打包加密工具的调用
    DEPENDS obfuscate
)

# 安装规则
install(TARGETS pathfinder_native
    LIBRARY DESTINATION lib/${PLATFORM_NAME}
    RUNTIME DESTINATION bin/${PLATFORM_NAME}
    ARCHIVE DESTINATION lib/${PLATFORM_NAME}
)

# 创建调试版本（用于开发）
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(pathfinder_native PRIVATE
        $<$<NOT:$<PLATFORM_ID:Windows>>:-g>         # Unix系统调试信息
        $<$<PLATFORM_ID:Windows>:/Zi>               # Windows调试信息
        $<$<NOT:$<PLATFORM_ID:Windows>>:-DDEBUG>    # Unix调试宏
        $<$<PLATFORM_ID:Windows>:/DDEBUG>           # Windows调试宏
    )
    
    # 调试版本不应用某些优化选项
    if(NOT WIN32)
    target_compile_options(pathfinder_native PRIVATE
        -fno-omit-frame-pointer
    )
endif()
endif()

# 检查编译器支持（仅在非Windows系统）
if(NOT WIN32)
include(CheckCCompilerFlag)
check_c_compiler_flag(-fstack-clash-protection HAS_STACK_CLASH_PROTECTION)
if(HAS_STACK_CLASH_PROTECTION)
    target_compile_options(pathfinder_native PRIVATE -fstack-clash-protection)
endif()

check_c_compiler_flag(-fcf-protection HAS_CF_PROTECTION)
if(HAS_CF_PROTECTION)
    target_compile_options(pathfinder_native PRIVATE -fcf-protection)
    endif()
endif()

# 输出配置信息
message(STATUS "PathFinder Native Validator Configuration:")
message(STATUS "  Platform: ${PLATFORM_NAME}")
message(STATUS "  Library Extension: ${LIB_EXTENSION}")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "  C Compiler: ${CMAKE_C_COMPILER}")
message(STATUS "  JNI Include: ${JNI_INCLUDE_DIRS}")
message(STATUS "  OpenSSL Version: ${OPENSSL_VERSION}")
if(NOT WIN32)
message(STATUS "  CURL Version: ${CURL_VERSION_STRING}")
endif()
message(STATUS "  Output Directory: ${OUTPUT_DIR}") 