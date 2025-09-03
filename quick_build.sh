#!/bin/bash

echo "⚡ 快速编译 PathFinder 插件（仅Java部分）..."

echo "⚡ 开始快速编译..."

# 清理散落的class文件
echo "🧹 清理散落的class文件..."
find . -name "*.class" -not -path "./build/*" -not -path "./.gradle/*" -type f -delete

# 记录开始时间
START_TIME=$(date +%s)

# 只编译Java部分
./gradlew shadowJar

if [ $? -eq 0 ]; then
    # 计算编译耗时
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    
    echo ""
    echo "✅ 快速编译成功！ (${DURATION}秒)"
    echo "📦 插件文件: build/libs/PathFinder-1.3.1-all.jar"
    ls -lh build/libs/PathFinder-1.3.1-all.jar
    
    # 验证编译结果
    CLASS_COUNT=$(jar tf build/libs/PathFinder-1.3.1-all.jar | grep -c "\.class$")
    echo "✅ Java类文件: $CLASS_COUNT 个"
else
    echo "❌ 快速编译失败！"
    exit 1
fi 