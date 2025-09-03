#!/bin/bash

echo "=========================================="
echo "   PathFinder - Linux 交叉编译 Windows DLL"
echo "=========================================="

# 设置颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 脚本目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$(dirname "$(dirname "$NATIVE_DIR")")")"

# 构建目录
BUILD_DIR="$NATIVE_DIR/build-windows-cross"
OUTPUT_DIR="$PROJECT_ROOT/target/native/windows"

echo -e "${BLUE}[1/6]${NC} 检查交叉编译工具链..."

# 检查MinGW-w64工具链
if ! command -v x86_64-w64-mingw32-gcc &> /dev/null; then
    echo -e "${RED}❌ MinGW-w64工具链未安装${NC}"
    echo "请运行: sudo apt install mingw-w64 mingw-w64-tools"
    exit 1
fi
echo -e "${GREEN}✅ MinGW-w64工具链已安装${NC}"

# 检查CMake
if ! command -v cmake &> /dev/null; then
    echo -e "${RED}❌ CMake未安装${NC}"
    exit 1
fi
echo -e "${GREEN}✅ CMake已安装${NC}"

# 检查Java环境
if [ -z "$JAVA_HOME" ]; then
    echo -e "${YELLOW}⚠️ JAVA_HOME未设置，尝试自动检测...${NC}"
    if command -v java &> /dev/null; then
        JAVA_PATH=$(readlink -f $(which java))
        export JAVA_HOME=$(dirname $(dirname $JAVA_PATH))
        echo -e "${GREEN}✅ 自动设置JAVA_HOME: $JAVA_HOME${NC}"
    else
        echo -e "${RED}❌ 无法找到Java环境${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}✅ Java环境已设置: $JAVA_HOME${NC}"
fi

echo -e "${BLUE}[2/6]${NC} 准备构建目录..."

# 清理旧的构建目录
if [ -d "$BUILD_DIR" ]; then
    echo "清理旧的构建目录..."
    rm -rf "$BUILD_DIR"
fi

# 创建构建目录
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

echo -e "${BLUE}[3/6]${NC} 配置CMake交叉编译..."

cd "$BUILD_DIR"

# 使用工具链文件配置CMake
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="../cmake/windows-cross.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DCMAKE_VERBOSE_MAKEFILE=ON

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ CMake配置失败${NC}"
    echo ""
    echo -e "${YELLOW}常见问题解决方案:${NC}"
    echo "1. 确保安装了Windows开发库"
    echo "2. 检查JAVA_HOME是否正确"
    echo "3. 查看上面的错误信息"
    exit 1
fi

echo -e "${GREEN}✅ CMake配置成功${NC}"

echo -e "${BLUE}[4/6]${NC} 编译Windows DLL..."

# 编译
make -j$(nproc)

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 编译失败${NC}"
    exit 1
fi

echo -e "${GREEN}✅ 编译成功${NC}"

echo -e "${BLUE}[5/6]${NC} 检查构建产物..."

# 检查DLL文件是否在输出目录中生成
DLL_FILE="$OUTPUT_DIR/pathfinder_native.dll"

if [ ! -f "$DLL_FILE" ]; then
    echo -e "${RED}❌ 未找到生成的DLL文件: $DLL_FILE${NC}"
    echo "查找构建目录中的产物:"
    find "$BUILD_DIR" -type f -name "*pathfinder*" -o -name "*.dll"
    echo "查找输出目录中的产物:"
    find "$OUTPUT_DIR" -type f 2>/dev/null || echo "输出目录不存在"
    exit 1
fi

echo -e "${GREEN}✅ 找到DLL文件: $(basename "$DLL_FILE")${NC}"

# 检查DLL文件信息
echo ""
echo -e "${BLUE}📊 DLL文件信息:${NC}"
file "$DLL_FILE"
ls -lh "$DLL_FILE"

echo -e "${BLUE}[6/6]${NC} 确认构建产物..."

# DLL已经在正确位置，无需复制
echo -e "${GREEN}✅ DLL已生成在: $OUTPUT_DIR/pathfinder_native.dll${NC}"

# 生成构建报告
REPORT_FILE="$OUTPUT_DIR/BUILD_REPORT.txt"
cat > "$REPORT_FILE" << EOF
PathFinder Native Library - Windows Cross-Compiled Build Report
================================================================

构建时间: $(date)
构建方法: Linux交叉编译
目标平台: Windows x64
工具链: MinGW-w64

构建产物:
- DLL文件: pathfinder_native.dll
- 文件大小: $(stat -c%s "$OUTPUT_DIR/pathfinder_native.dll") 字节
- 位置: $OUTPUT_DIR

编译器信息:
- C编译器: $(x86_64-w64-mingw32-gcc --version | head -1)
- Java版本: $(java -version 2>&1 | head -1)

注意事项:
- 这是交叉编译的Windows DLL
- 在Windows系统上测试功能
- 可能需要Visual C++ Redistributable
EOF

echo ""
echo -e "${GREEN}🎉 交叉编译成功完成! 🎉${NC}"
echo "================================================"
echo -e "${GREEN}📁 构建产物位置:${NC}"
echo "  - DLL文件: $OUTPUT_DIR/pathfinder_native.dll"
echo "  - 构建报告: $REPORT_FILE"
echo ""
echo -e "${BLUE}📋 文件信息:${NC}"
ls -lh "$OUTPUT_DIR/"
echo ""
echo -e "${BLUE}💡 下一步:${NC}"
echo "1. 将DLL文件复制到Java资源目录"
echo "2. 运行 gradle shadowJar 打包"
echo "3. 在Windows系统上测试"
echo ""

# 显示构建报告
echo -e "${BLUE}📋 构建报告:${NC}"
echo "================================================"
cat "$REPORT_FILE"
echo "================================================" 