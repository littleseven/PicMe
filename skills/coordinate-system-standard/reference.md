# Coordinate System Standard - 参考文档

> 本文件存放从 SKILL.md 拆分的详细脚本和扩展示例。

---

## §Pre-commit Hook

在项目根目录创建 `.git/hooks/pre-commit`：

```bash
#!/bin/bash
echo "🔍 运行坐标系标注检查..."

STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep -E "\.(kt|md)$")

if [ -z "$STAGED_FILES" ]; then
    exit 0
fi

ERRORS=0

for file in $STAGED_FILES; do
    if [[ $file == *.kt ]]; then
        FUZZY_COMMENTS=$(grep -n "左眼\|右眼\|左眉\|右眉" "$file" | \
            grep -v "\[图像坐标系\]" | \
            grep -v "\[人脸坐标系\]" | \
            grep -v "imageLeft" | \
            grep -v "imageRight")
        
        if [ ! -z "$FUZZY_COMMENTS" ]; then
            echo "❌ $file 中存在未标注坐标系的注释:"
            echo "$FUZZY_COMMENTS"
            ERRORS=$((ERRORS + 1))
        fi
    fi
    
    if [[ $file == *.md ]]; then
        FUZZY_DOCS=$(grep -n "左眼\|右眼\|左眉\|右眉" "$file" | \
            grep -v "\[图像坐标系\]" | \
            grep -v "\[人脸坐标系\]" | \
            grep -v "图像左侧" | \
            grep -v "图像右侧")
        
        if [ ! -z "$FUZZY_DOCS" ]; then
            echo "❌ $file 中存在未标注坐标系的描述:"
            echo "$FUZZY_DOCS"
            ERRORS=$((ERRORS + 1))
        fi
    fi
done

if [ $ERRORS -gt 0 ]; then
    echo "\n❌ 发现 $ERRORS 个文件存在坐标系标注问题"
    exit 1
fi

echo "✅ 坐标系标注检查通过"
exit 0
```

启用：
```bash
chmod +x .git/hooks/pre-commit
```
