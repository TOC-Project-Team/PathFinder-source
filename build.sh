#!/bin/bash

echo "🔨 开始编译 PathFinder 插件..."
echo "=========================================="

# 记录开始时间
START_TIME=$(date +%s)

# 检查并设置JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    echo "⚠️  未设置 JAVA_HOME，尝试自动检测..."
    
    # 尝试自动找到Java路径
    if command -v java >/dev/null 2>&1; then
        JAVA_PATH=$(readlink -f $(which java))
        JAVA_HOME=$(dirname $(dirname $JAVA_PATH))
        export JAVA_HOME
        echo "✅ 自动设置 JAVA_HOME: $JAVA_HOME"
    else
        echo "❌ 未找到Java安装，请手动设置JAVA_HOME"
        echo "   例如: export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64"
        exit 1
    fi
else
    echo "✅ JAVA_HOME 已设置: $JAVA_HOME"
fi

# 验证Java版本
echo "🔍 检查Java版本..."
java -version 2>&1 | head -1

# 清理散落的class文件
echo "🧹 清理散落的class文件..."
find . -name "*.class" -not -path "./build/*" -not -path "./.gradle/*" -type f -delete

# 编译Java插件
echo ""
echo "☕ 编译Java插件..."
echo "=========================================="

# 清理并编译
echo "🧹 清理旧的编译文件..."
./gradlew clean

echo "⚙️  开始编译插件..."
./gradlew shadowJar

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Java插件编译成功！"
    
    # 验证编译结果
    echo ""
    echo "📊 编译结果验证..."
    echo "=========================================="
    
    JAR_FILE="build/libs/PathFinder-1.3.1-all.jar"
    if [ -f "$JAR_FILE" ]; then
        echo "📦 插件文件: $JAR_FILE"
        ls -lh "$JAR_FILE"
        
        # 检查文件大小（应该约516KB）
        SIZE=$(stat -c%s "$JAR_FILE")
        if [ $SIZE -gt 400000 ] && [ $SIZE -lt 600000 ]; then
            echo "✅ 文件大小正常: $(($SIZE / 1024))KB"
        else
            echo "⚠️  文件大小异常: $(($SIZE / 1024))KB (期望: ~516KB)"
        fi
        
        # 验证jar包内容
        echo "🔍 检查jar包内容..."
        CLASS_COUNT=$(jar tf "$JAR_FILE" | grep -c "\.class$")
        echo "✅ Java类文件: $CLASS_COUNT 个"
    else
        echo "❌ 未找到编译输出文件"
        exit 1
    fi
    
    # 计算编译耗时
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    echo ""
    echo "⏱️  编译耗时: ${DURATION}秒"
    
    # 可选：复制到release目录
    echo ""
    read -p "📁 是否复制到release目录? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        mkdir -p release
        TIMESTAMP=$(date +%Y%m%d_%H%M%S)
        RELEASE_FILE="release/PathFinder-1.3.1-FULL-${TIMESTAMP}.jar"
        cp "$JAR_FILE" "$RELEASE_FILE"
        echo "✅ 已复制到: $RELEASE_FILE"
        ls -lh "$RELEASE_FILE"
    fi
    
    echo ""
    echo "🎉 编译完成！"
    echo "=========================================="
    echo "📦 最终文件: $JAR_FILE"
    echo "🔧 包含功能: Java安全检测插件"
    echo "🚀 可以直接部署到Minecraft服务器"
    echo ""
    echo "💡 下次编译命令: ./build.sh"
    
else
    echo ""
    echo "❌ Java插件编译失败！"
    echo "🔍 请检查错误信息并修复后重试"
    exit 1
fi 