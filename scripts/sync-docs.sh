#!/usr/bin/env bash
# 同步 docs/ -> docs-site/docs/（docsify 文档站产物），排除不上线的敏感/内部文档。
# 无构建：docsify 为浏览器运行时渲染，rsync 文件即可。
# deploy-docs-site.sh 与本地预览均调用本脚本。
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="docs"
DST="docs-site/docs"

rsync -a --delete \
  --exclude 'superpowers/' \
  --exclude '03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md' \
  --exclude '03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md' \
  --exclude '07-STANDARDS/REPO_REORGANIZATION_PLAN.md' \
  --exclude '07-STANDARDS/CODE_REFACTORING_PLAN.md' \
  --exclude '05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md' \
  "$SRC/" "$DST/"

echo "✓ 已同步 $SRC -> $DST（docsify 文档站）"
echo "  上线文档数: $(find "$DST" -name '*.md' | wc -l | tr -d ' ')"
echo "  确认排除: superpowers/ 与含服务器敏感信息的部署/内部计划文档"
