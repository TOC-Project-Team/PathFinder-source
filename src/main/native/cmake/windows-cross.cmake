# CMake toolchain file for Linux to Windows cross-compilation
# Using MinGW-w64 toolchain

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# Set the compilers
set(CMAKE_C_COMPILER x86_64-w64-mingw32-gcc)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++)
set(CMAKE_RC_COMPILER x86_64-w64-mingw32-windres)

# Set the target environment
set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)

# Configure search behavior
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

# Force static linking for Windows libraries
set(BUILD_SHARED_LIBS OFF)

# Windows specific compiler flags
set(CMAKE_C_FLAGS_INIT "-static-libgcc -static-libstdc++")
set(CMAKE_CXX_FLAGS_INIT "-static-libgcc -static-libstdc++")

# Set the file extension for executables and libraries
set(CMAKE_EXECUTABLE_SUFFIX ".exe")
set(CMAKE_SHARED_LIBRARY_SUFFIX ".dll")
set(CMAKE_STATIC_LIBRARY_SUFFIX ".a")

# Don't search for programs in the build host directories
set(CMAKE_SYSTEM_INCLUDE_PATH "")
set(CMAKE_SYSTEM_LIBRARY_PATH "")

message(STATUS "Cross-compiling for Windows using MinGW-w64")
message(STATUS "C Compiler: ${CMAKE_C_COMPILER}")
message(STATUS "CXX Compiler: ${CMAKE_CXX_COMPILER}") 