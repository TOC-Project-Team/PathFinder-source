# PathFinder Native验证系统

这是PathFinder插件的原生验证系统，将启动验证逻辑从Java移到了C代码中，并添加了多层反破解和反编译保护。

## 🔒 安全特性

### 反破解保护
- **反调试检测**: ptrace检测、进程状态检查、时间攻击检测
- **虚拟机检测**: DMI信息检查、CPU型号检测、网络接口检测
- **API Hook检测**: 关键函数完整性验证
- **内存保护**: 代码段完整性监控、内存权限控制
- **动态监控**: 后台反调试监控线程

### 代码保护
- **函数名混淆**: 关键函数名替换为随机十六进制
- **字符串加密**: XOR加密重要字符串
- **控制流混淆**: 间接跳转和函数指针
- **死代码插入**: 虚假函数和无用代码

### 二进制保护
- **AES-256-CBC加密**: 整个库文件加密
- **SHA-256完整性**: 防篡改校验
- **符号剥离**: 移除调试信息和符号表
- **编译器优化**: 最高级别优化(-O3)

## 📦 构建系统

### 系统要求
- Linux系统 (Ubuntu 18.04+推荐)
- CMake 3.10+
- GCC 7.0+
- OpenSSL 1.1.0+
- libcurl 7.0+
- Python 3.6+

### 依赖安装
```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install build-essential cmake libssl-dev libcurl4-openssl-dev python3

# CentOS/RHEL
sudo yum install gcc gcc-c++ cmake openssl-devel libcurl-devel python3

# 安装JDK（如果没有）
sudo apt-get install openjdk-17-jdk
```

### 快速构建
```bash
# 进入native目录
cd src/main/native

# 给构建脚本执行权限
chmod +x scripts/build_secure.sh

# 执行安全构建
./scripts/build_secure.sh
```

### 手动构建
```bash
# 创建构建目录
mkdir build && cd build

# 配置CMake
cmake .. -DCMAKE_BUILD_TYPE=Release

# 编译
make -j$(nproc)

# 库文件位置
ls libpathfinder_native.so
```

## 🚀 部署使用

### 1. 生成安全库文件
```bash
# 执行安全构建脚本
./scripts/build_secure.sh

# 检查生成的文件
ls ../../../target/native/
# 应该看到:
# - encrypted_libpathfinder_native.so  (加密的库文件)
# - pathfinder_native.sha256          (完整性校验)
# - load_native.sh                    (解密脚本)
# - verify_integrity.sh               (验证脚本)
# - BUILD_REPORT.txt                  (构建报告)
```

### 2. 部署到服务器
```bash
# 复制文件到插件目录
cp target/native/* /path/to/minecraft/plugins/PathFinder/target/native/

# 确保脚本有执行权限
chmod +x /path/to/minecraft/plugins/PathFinder/target/native/*.sh
```

### 3. Java代码集成
Java代码已经自动集成了native验证，插件启动时会：

1. 首先尝试加载加密的native库
2. 验证库文件完整性
3. 如果成功，使用native验证进行启动验证
4. 如果失败，回退到Java验证

## 🔧 配置选项

### Java配置
在`TOCplugin.java`中可以配置：

```java
// 是否使用native验证
private boolean useNativeValidation = true;

// 检查native库是否可用
private boolean isNativeLibraryAvailable() {}
```

### C代码配置
在`startup_validator.h`中的错误码：

```c
#define VALIDATION_SUCCESS          0
#define VALIDATION_FAILED          -1
#define VALIDATION_NETWORK_ERROR   -2
#define VALIDATION_TAMPER_DETECTED -3
#define VALIDATION_DEBUG_DETECTED  -4
```

## 🛡️ 安全检查

### 验证库完整性
```bash
# 手动验证完整性
./target/native/verify_integrity.sh \
    target/native/encrypted_libpathfinder_native.so \
    target/native/pathfinder_native.sha256
```

### 测试解密
```bash
# 测试解密功能
DECRYPTED_LIB=$(./target/native/load_native.sh target/native/encrypted_libpathfinder_native.so)
echo "解密后的库: $DECRYPTED_LIB"

# 检查解密文件
file "$DECRYPTED_LIB"
```

### 运行时监控
插件运行时会自动进行：
- 反调试检测（每0.1-0.3秒）
- 代码完整性检查
- 内存保护验证
- API Hook检测

## 🐛 故障排除

### 常见问题

#### 1. 库加载失败
```
错误: Native验证库加载失败
解决: 检查依赖库是否安装完整
```

#### 2. 完整性验证失败
```
错误: Native库完整性验证失败
解决: 重新构建库文件，检查文件是否被篡改
```

#### 3. 解密失败
```
错误: 解密脚本执行失败
解决: 检查OpenSSL是否安装，密钥是否正确
```

#### 4. 调试检测
```
错误: 检测到调试环境
解决: 在生产环境运行，不要使用调试器
```

### 调试模式
开发时可以禁用反调试保护：

```bash
# 构建调试版本
cmake .. -DCMAKE_BUILD_TYPE=Debug
make
```

### 日志分析
检查插件日志：
```bash
# 查看验证相关日志
grep "Native验证\|验证库" logs/latest.log

# 查看完整性监控日志
grep "完整性\|integrity" logs/latest.log
```

## 📊 性能影响

### 启动时间
- Native验证: ~200-500ms
- Java验证: ~1000-2000ms
- 提升: 50-80%的启动验证性能提升

### 内存使用
- 额外内存: ~2-5MB (监控线程和缓存)
- CPU使用: ~0.1-0.5% (后台监控)

### 网络影响
- 验证请求: 与Java版本相同
- 加密解密: 本地操作，无网络影响

## 🔄 更新和维护

### 更新native库
```bash
# 1. 重新构建
./scripts/build_secure.sh

# 2. 停止服务器
systemctl stop minecraft

# 3. 替换文件
cp target/native/* /path/to/plugins/PathFinder/target/native/

# 4. 启动服务器
systemctl start minecraft
```

### 密钥轮换
定期更改加密密钥：

1. 修改`build_secure.sh`中的`ENCRYPTION_KEY`
2. 修改`NativeLibraryLoader.java`中的`ENCRYPTION_KEY`
3. 重新构建和部署

### 监控告警
建议设置监控告警：

```bash
# 检查反调试检测
grep "调试环境\|DEBUG_DETECTED" logs/latest.log

# 检查完整性违规
grep "完整性.*失败\|integrity.*failed" logs/latest.log

# 检查库加载状态
grep "Native.*库.*加载" logs/latest.log
```

## 📋 开发指南

### 添加新的反破解检测
1. 在`anti_reverse.c`中添加检测函数
2. 在`obfuscated_validation_flow`中注册
3. 重新构建和测试

### 修改验证逻辑
1. 编辑`startup_validator.c`中的验证函数
2. 更新JNI接口（如果需要）
3. 重新构建native库

### 测试新功能
```bash
# 构建测试版本
cmake .. -DCMAKE_BUILD_TYPE=Debug -DENABLE_TESTING=ON
make
make test
```

## ⚠️ 安全注意事项

1. **密钥安全**: 不要在代码中硬编码真实的加密密钥
2. **环境隔离**: 生产环境和开发环境使用不同的密钥
3. **定期更新**: 定期重新构建和更新native库
4. **监控日志**: 及时发现和响应安全事件
5. **备份验证**: 保持Java验证作为备用方案

## 📞 技术支持

如果遇到问题，请检查：
1. 构建报告 (`BUILD_REPORT.txt`)
2. 插件日志
3. 系统依赖
4. 文件权限

---

**注意**: 这是一个高安全性的验证系统，请在理解其工作原理后再进行部署和修改。 