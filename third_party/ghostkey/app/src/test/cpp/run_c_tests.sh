#!/usr/bin/env bash
#
# T1: 在宿主机（不是 Android 设备）上编译并运行 calib_core 的纯计算单测。
#
# 为什么要单独跑：calib_core.c 里的四个函数决定 KASLR slide / page_offset 的
# 解算结果，逻辑漂移在真机上只表现为「exploit 静默失败」，很难定位。它们不依赖
# tracefs / Android，任何有 C 编译器的机器都能验证。
#
# 用法：
#   app/src/test/cpp/run_c_tests.sh          # 自动选择 clang / gcc
#   CC=clang app/src/test/cpp/run_c_tests.sh # 指定编译器
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
CPP_DIR="$ROOT/app/src/main/cpp"
TEST_DIR="$ROOT/app/src/test/cpp"

if [ -z "${CC:-}" ]; then
    for c in clang gcc cc; do
        if command -v "$c" >/dev/null 2>&1; then
            CC="$c"
            break
        fi
    done
fi
if [ -z "${CC:-}" ]; then
    echo "error: 找不到 C 编译器（clang / gcc / cc），请先安装或设置 CC=" >&2
    exit 2
fi

OUT_DIR="$TEST_DIR/.build"
mkdir -p "$OUT_DIR"
BIN="$OUT_DIR/test_calib_core"
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) BIN="$BIN.exe" ;;
esac

echo "== 编译测试 =="
echo "   CC = $CC"
"$CC" -std=c11 -O1 -g -Wall -Wextra \
    -I "$CPP_DIR" \
    "$CPP_DIR/calib_core.c" \
    "$TEST_DIR/test_calib_core.c" \
    -o "$BIN"

echo "== 运行测试 =="
"$BIN"
