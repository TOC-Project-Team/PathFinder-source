#!/bin/bash

# PathFinder Native安全构建脚本
# 功能：编译、混淆、加密和签名native库

set -e  # 出错时退出

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build"
TARGET_DIR="$PROJECT_ROOT/../../../target/native"
TEMP_DIR="/tmp/pathfinder_build_$$"

# 密钥配置（实际使用时应从安全位置读取）
OBFUSCATION_KEY="PathFinderSecure2024"
ENCRYPTION_KEY="NativeValidatorKey2024"

echo -e "${BLUE}[PathFinder] 开始安全构建流程...${NC}"

# 清理和创建目录
cleanup() {
    echo -e "${YELLOW}[清理] 清理临时文件...${NC}"
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

mkdir -p "$BUILD_DIR" "$TARGET_DIR" "$TEMP_DIR"

# 步骤1: 预处理和代码混淆
echo -e "${BLUE}[步骤1] 代码预处理和混淆...${NC}"

# 复制源代码到临时目录
cp -r "$PROJECT_ROOT/src" "$TEMP_DIR/"
cp -r "$PROJECT_ROOT/include" "$TEMP_DIR/"
cp "$PROJECT_ROOT/CMakeLists.txt" "$TEMP_DIR/"

# 应用代码混淆
apply_code_obfuscation() {
    local source_file="$1"
    echo -e "${YELLOW}  - 混淆文件: $(basename "$source_file")${NC}"
    
    # 函数名混淆
    sed -i 's/stealth_anti_debug_check/func_0x7f4a1b2c/g' "$source_file"
    sed -i 's/detect_debugger_processes/func_0x8e5d3a9f/g' "$source_file"
    sed -i 's/check_code_integrity/func_0x9b7c2e1d/g' "$source_file"
    sed -i 's/detect_virtual_environment/func_0xa3f8c6b4/g' "$source_file"
    sed -i 's/protect_memory_sections/func_0xc4d9a5e7/g' "$source_file"
    
    # 字符串混淆（简单XOR）
    sed -i 's/"TracerPid:"/decrypt_str_0/g' "$source_file"
    sed -i 's/"\/proc\/self\/status"/decrypt_str_1/g' "$source_file"
    sed -i 's/"LD_PRELOAD"/decrypt_str_2/g' "$source_file"
    
    # 添加虚假函数和死代码
    cat >> "$source_file" << 'EOF'

// 虚假函数 - 混淆静态分析
static void fake_function_1(void) {
    volatile int dummy = 0;
    for (int i = 0; i < 1000; i++) dummy += i;
}

static void fake_function_2(void) {
    char fake_data[256];
    memset(fake_data, 0xAA, sizeof(fake_data));
}

static int fake_validation_check(void) {
    return rand() % 2;  // 返回随机结果
}
EOF
}

# 对C源文件应用混淆
find "$TEMP_DIR/src" -name "*.c" -exec bash -c 'apply_code_obfuscation "$0"' {} \;

# 步骤2: CMake配置和编译
echo -e "${BLUE}[步骤2] CMake配置和编译...${NC}"

cd "$BUILD_DIR"
rm -rf *

# 获取并设置JAVA_HOME环境变量
if [ -z "$JAVA_HOME" ]; then
    # 尝试从java命令获取JAVA_HOME
    if command -v java >/dev/null 2>&1; then
        JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
        export JAVA_HOME
        echo -e "${YELLOW}  - 自动检测到JAVA_HOME: $JAVA_HOME${NC}"
    else
        echo -e "${RED}[错误] 无法找到Java环境，请设置JAVA_HOME环境变量${NC}"
        exit 1
    fi
else
    echo -e "${YELLOW}  - 使用JAVA_HOME: $JAVA_HOME${NC}"
fi

# 配置CMake - 最高优化级别，设置JAVA_HOME
cmake "$TEMP_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-O3 -s -fomit-frame-pointer -fstack-protector-strong" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--strip-all -Wl,--discard-all" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--strip-all -Wl,--discard-all" \
    -DJAVA_HOME="$JAVA_HOME"

# 编译
make -j$(nproc)

echo -e "${GREEN}[编译] 编译完成${NC}"

# 步骤3: 二进制混淆和加密
echo -e "${BLUE}[步骤3] 二进制混淆和加密...${NC}"

LIBRARY_NAME="libpathfinder_native.so"
SOURCE_LIB="$BUILD_DIR/$LIBRARY_NAME"
TEMP_LIB="$TEMP_DIR/$LIBRARY_NAME"

if [ ! -f "$SOURCE_LIB" ]; then
    echo -e "${RED}[错误] 编译产物不存在: $SOURCE_LIB${NC}"
    exit 1
fi

# 复制到临时位置进行处理
cp "$SOURCE_LIB" "$TEMP_LIB"

# 二进制混淆 - 添加无用节和数据
echo -e "${YELLOW}  - 应用二进制混淆...${NC}"

# 创建混淆脚本
cat > "$TEMP_DIR/obfuscate.py" << 'EOF'
#!/usr/bin/env python3
import sys
import os
import random

def obfuscate_binary(input_file, output_file):
    with open(input_file, 'rb') as f:
        data = bytearray(f.read())
    
    # 在文件末尾添加随机数据（虚假节）
    fake_data_size = random.randint(1024, 4096)
    fake_data = bytearray(random.randint(0, 255) for _ in range(fake_data_size))
    data.extend(fake_data)
    
    # 简单的字节级混淆（跳过ELF头）
    elf_header_size = 64  # ELF64头大小
    for i in range(elf_header_size, len(data) - fake_data_size):
        if i % 16 == 0:  # 每16个字节进行一次异或
            data[i] = data[i] ^ 0x5A
    
    with open(output_file, 'wb') as f:
        f.write(data)

if __name__ == "__main__":
    obfuscate_binary(sys.argv[1], sys.argv[2])
EOF

python3 "$TEMP_DIR/obfuscate.py" "$TEMP_LIB" "$TEMP_DIR/obfuscated_$LIBRARY_NAME"

# 步骤4: 加密保护
echo -e "${BLUE}[步骤4] 加密保护...${NC}"

# 使用OpenSSL进行AES加密
ENCRYPTED_LIB="$TEMP_DIR/encrypted_$LIBRARY_NAME"
echo -e "${YELLOW}  - 执行AES加密...${NC}"

openssl enc -aes-256-cbc -salt -in "$TEMP_DIR/obfuscated_$LIBRARY_NAME" \
    -out "$ENCRYPTED_LIB" -k "$ENCRYPTION_KEY" -pbkdf2

# 创建解密包装器
cat > "$TARGET_DIR/load_native.sh" << EOF
#!/bin/bash
# PathFinder Native库动态解密加载器

ENCRYPTED_LIB="\$1"
TEMP_LIB="/tmp/pathfinder_native_\$\$.so"
ENCRYPTION_KEY="$ENCRYPTION_KEY"

# 解密库文件
openssl enc -aes-256-cbc -d -salt -in "\$ENCRYPTED_LIB" \\
    -out "\$TEMP_LIB" -k "\$ENCRYPTION_KEY" -pbkdf2

# 设置临时文件权限
chmod 755 "\$TEMP_LIB"

# 返回解密后的文件路径
echo "\$TEMP_LIB"
EOF

chmod +x "$TARGET_DIR/load_native.sh"

# 步骤5: 数字签名和完整性保护
echo -e "${BLUE}[步骤5] 数字签名和完整性保护...${NC}"

# 计算校验和
CHECKSUM=$(sha256sum "$ENCRYPTED_LIB" | cut -d' ' -f1)
echo "$CHECKSUM" > "$TARGET_DIR/pathfinder_native.sha256"

# 创建完整性验证脚本
cat > "$TARGET_DIR/verify_integrity.sh" << EOF
#!/bin/bash
# PathFinder Native库完整性验证

LIBRARY_FILE="\$1"
CHECKSUM_FILE="\$2"

if [ ! -f "\$LIBRARY_FILE" ] || [ ! -f "\$CHECKSUM_FILE" ]; then
    echo "文件不存在"
    exit 1
fi

EXPECTED_CHECKSUM=\$(cat "\$CHECKSUM_FILE")
ACTUAL_CHECKSUM=\$(sha256sum "\$LIBRARY_FILE" | cut -d' ' -f1)

if [ "\$EXPECTED_CHECKSUM" = "\$ACTUAL_CHECKSUM" ]; then
    echo "完整性验证通过"
    exit 0
else
    echo "完整性验证失败"
    exit 1
fi
EOF

chmod +x "$TARGET_DIR/verify_integrity.sh"

# 步骤6: 最终处理和部署
echo -e "${BLUE}[步骤6] 最终处理...${NC}"

# 复制加密的库文件
cp "$ENCRYPTED_LIB" "$TARGET_DIR/"

# 创建版本信息
cat > "$TARGET_DIR/VERSION" << EOF
PathFinder Native Validator
Build Date: $(date)
Git Commit: $(git rev-parse HEAD 2>/dev/null || echo "unknown")
Build ID: $(openssl rand -hex 8)
Checksum: $CHECKSUM
EOF

# 创建Java加载器帮助类
cat > "$PROJECT_ROOT/../../../src/main/java/org/momu/tOCplugin/NativeLibraryLoader.java" << 'EOF'
package org.momu.tOCplugin;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

public class NativeLibraryLoader {
    private static final String ENCRYPTION_KEY = "NativeValidatorKey2024";
    private static boolean loaded = false;
    
    public static synchronized boolean loadNativeLibrary() {
        if (loaded) return true;
        
        try {
            // 获取加密的库文件
            String pluginDir = System.getProperty("user.dir") + "/plugins/PathFinder";
            File encryptedLib = new File(pluginDir, "target/native/encrypted_libpathfinder_native.so");
            
            if (!encryptedLib.exists()) {
                System.err.println("加密的native库不存在: " + encryptedLib.getPath());
                return false;
            }
            
            // 验证完整性
            if (!verifyIntegrity(encryptedLib)) {
                System.err.println("Native库完整性验证失败");
                return false;
            }
            
            // 解密并加载
            File tempLib = decryptLibrary(encryptedLib);
            if (tempLib != null) {
                System.load(tempLib.getAbsolutePath());
                loaded = true;
                
                // 定时清理临时文件
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.deleteIfExists(tempLib.toPath());
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }));
                
                return true;
            }
        } catch (Exception e) {
            System.err.println("Native库加载失败: " + e.getMessage());
        }
        
        return false;
    }
    
    private static boolean verifyIntegrity(File encryptedLib) {
        try {
            File checksumFile = new File(encryptedLib.getParent(), "pathfinder_native.sha256");
            if (!checksumFile.exists()) return false;
            
            String expectedChecksum = new String(Files.readAllBytes(checksumFile.toPath())).trim();
            String actualChecksum = calculateSHA256(encryptedLib);
            
            return expectedChecksum.equals(actualChecksum);
        } catch (Exception e) {
            return false;
        }
    }
    
    private static File decryptLibrary(File encryptedLib) {
        try {
            // 创建临时文件
            File tempLib = File.createTempFile("pathfinder_native_", ".so");
            tempLib.deleteOnExit();
            
            // 执行解密（使用外部脚本，因为Java的AES实现可能不兼容OpenSSL）
            ProcessBuilder pb = new ProcessBuilder("bash", 
                new File(encryptedLib.getParent(), "load_native.sh").getPath(),
                encryptedLib.getPath());
            
            Process process = pb.start();
            process.waitFor();
            
            if (process.exitValue() == 0) {
                // 读取解密后的文件路径
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                String decryptedPath = reader.readLine();
                
                if (decryptedPath != null && new File(decryptedPath).exists()) {
                    return new File(decryptedPath);
                }
            }
        } catch (Exception e) {
            System.err.println("解密失败: " + e.getMessage());
        }
        
        return null;
    }
    
    private static String calculateSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = digest.digest(fileBytes);
        
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
EOF

# 步骤7: 生成构建报告
echo -e "${BLUE}[步骤7] 生成构建报告...${NC}"

cat > "$TARGET_DIR/BUILD_REPORT.txt" << EOF
========================================
PathFinder Native 安全构建报告
========================================

构建时间: $(date)
构建主机: $(hostname)
Git提交: $(git rev-parse HEAD 2>/dev/null || echo "unknown")

应用的安全措施:
✓ 代码混淆 (函数名、字符串)
✓ 二进制混淆 (添加虚假节)
✓ AES-256-CBC 加密
✓ SHA-256 完整性校验
✓ 编译器优化 (-O3)
✓ 符号剥离 (--strip-all)
✓ 栈保护 (-fstack-protector-strong)
✓ 反调试保护
✓ 虚拟机检测
✓ API Hook检测

生成的文件:
- encrypted_libpathfinder_native.so (加密的库文件)
- pathfinder_native.sha256 (完整性校验)
- load_native.sh (解密加载器)
- verify_integrity.sh (完整性验证器)
- NativeLibraryLoader.java (Java加载器)

库文件信息:
原始大小: $(stat -c%s "$SOURCE_LIB" 2>/dev/null || echo "unknown") bytes
加密大小: $(stat -c%s "$ENCRYPTED_LIB" 2>/dev/null || echo "unknown") bytes
SHA-256: $CHECKSUM

使用说明:
1. 将生成的文件部署到目标环境
2. 使用NativeLibraryLoader.loadNativeLibrary()加载
3. 定期验证完整性
========================================
EOF

echo -e "${GREEN}[完成] 安全构建流程完成！${NC}"
echo -e "${GREEN}构建产物位置: $TARGET_DIR${NC}"
echo -e "${YELLOW}请查看构建报告: $TARGET_DIR/BUILD_REPORT.txt${NC}"

# 清理构建目录
rm -rf "$BUILD_DIR"

exit 0 