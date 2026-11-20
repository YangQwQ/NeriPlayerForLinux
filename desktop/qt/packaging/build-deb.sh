#!/usr/bin/env bash
# 兼容入口：保留原有 desktop/qt/packaging/build-deb.sh <version> 调用方式，
# 实际构建转交给 build-packages.sh --only deb，产物仍在 build/packages/out。
#
# 新脚本与原脚本的差异：
#   - 产物目录从 build-deb/out 改为 build/packages/out（统一的三格式输出目录）；
#   - deb control / 维护脚本改为从同目录模板生成，与 rpm 共享一份 staging。
set -euo pipefail

PACKAGING_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$PACKAGING_DIR/build-packages.sh" "${1:-2.0.0}" --only deb
