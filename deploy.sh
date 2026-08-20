#!/usr/bin/env bash
# ============================================================
# ScreenShare 服务器一键部署脚本（MonkeyCode / 任意 Linux VPS）
# 用法：
#   1. 把代码放到服务器（替换 /workspace 或自定义路径）
#   2. 编辑本脚本顶部的配置（路径、端口、密钥）
#   3. bash deploy.sh
# ============================================================
set -u

# ==================== 配置区（按需修改） ====================
WS_DIR="${WS_DIR:-/workspace}"           # 项目根目录（MonkeyCode 默认 /workspace）
NODE="${NODE:-node}"                     # node 可执行文件
LOG_DIR="${LOG_DIR:-/tmp/screenshare}"   # 运行日志目录

# 安全密钥（必填！建议用 openssl rand -hex 16 生成）
PUBLISH_TOKEN="${PUBLISH_TOKEN:-}"       # 发版接口鉴权（download-server 必填）
KEYSTORE_PASS="${KEYSTORE_PASS:-}"       # APK 签名口令（publish 建议填）
ALBUM_KEY="${ALBUM_KEY:-}"               # 相册访问密钥（album-server 必填，需与 App 端 gradle.properties 一致）
DIAG_TOKEN="${DIAG_TOKEN:-}"             # 崩溃/诊断上报鉴权（建议填）

# 端口
SIGNAL_PORT="${SIGNAL_PORT:-8095}"
ALBUM_PORT="${ALBUM_PORT:-8096}"
DOWNLOAD_PORT="${DOWNLOAD_PORT:-8090}"
RELAY_PORT="${RELAY_PORT:-8097}"
# ============================================================

mkdir -p "$LOG_DIR"

echo "=============================================="
echo " ScreenShare 服务器部署"
echo " 项目目录: $WS_DIR"
echo "=============================================="

# 0. 安全检查
if [ -z "$ALBUM_KEY" ]; then
  echo "❌ 未设置 ALBUM_KEY（相册访问密钥），相册服务将以全开放模式运行，非常危险！"
  echo "   请设置：ALBUM_KEY=<密钥> bash deploy.sh"
  exit 1
fi
if [ -z "$PUBLISH_TOKEN" ]; then
  echo "⚠️  未设置 PUBLISH_TOKEN，发版管理接口将全部返回 403（App 内无法发版）。"
  echo "   设置方法：PUBLISH_TOKEN=<随机串> bash deploy.sh"
fi

# 1. 安装依赖
echo ""
echo "[1/5] 安装依赖..."
cd "$WS_DIR/server" && npm install --omit=dev --no-audit --no-fund 2>&1 | tail -2
cd "$WS_DIR/album-server" && npm install --omit=dev --no-audit --no-fund 2>&1 | tail -2

# 2. 停止旧进程
echo ""
echo "[2/5] 停止旧服务进程..."
pkill -f "node server.js" 2>/dev/null && echo "  信令已停止" || echo "  信令未在运行"
pkill -f "album-server/index.js" 2>/dev/null && echo "  相册已停止" || echo "  相册未在运行"
pkill -f "download-server.js" 2>/dev/null && echo "  下载已停止" || echo "  下载未在运行"
pkill -f "relay-server.js" 2>/dev/null && echo "  中继已停止" || echo "  中继未在运行"
sleep 1

# 3. 启动服务（nohup 常驻 + 日志）
echo ""
echo "[3/5] 启动服务..."
start_svc() {
  local name="$1" port="$2" cmd="$3"
  nohup env "$@" "$NODE" "$cmd" >> "$LOG_DIR/$name.log" 2>&1 &
  echo "  $name(:$port) 已启动 PID=$!"
}

# 信令：不开 DIAG（生产避免 SDP 明文落盘）；不设 DIAG_TOKEN 时诊断上报默认拒绝
start_svc signaling "$SIGNAL_PORT" "$WS_DIR/server/server.js" PORT="$SIGNAL_PORT" DIAG="${DIAG:-0}" DIAG_TOKEN="$DIAG_TOKEN"
# 相册
start_svc album "$ALBUM_PORT" "$WS_DIR/album-server/index.js" PORT="$ALBUM_PORT" ALBUM_ROOT="$WS_DIR/albums" ALBUM_KEY="$ALBUM_KEY"
# 下载/发布
start_svc download "$DOWNLOAD_PORT" "$WS_DIR/server/download-server.js" PORT="$DOWNLOAD_PORT" PUBLISH_TOKEN="$PUBLISH_TOKEN" DOWNLOAD_BASE="${DOWNLOAD_BASE:-}"
# 中继
start_svc relay "$RELAY_PORT" "$WS_DIR/server/relay-server.js" PORT="$RELAY_PORT"

# 4. 等待启动并健康检查
echo ""
echo "[4/5] 健康检查..."
sleep 2
check_port() {
  local name="$1" port="$2"
  if (ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -q ":$port "; then
    echo "  ✅ $name 正在监听 :$port"
  else
    echo "  ❌ $name 未监听 :$port（查看日志 $LOG_DIR/$name.log）"
  fi
}
check_port 信令 "$SIGNAL_PORT"
check_port 相册 "$ALBUM_PORT"
check_port 下载 "$DOWNLOAD_PORT"
check_port 中继 "$RELAY_PORT"

# 5. 接口自测
echo ""
echo "[5/5] 接口自测..."
curl -s -o /dev/null -w "  下载服务器 /version.json -> HTTP %{http_code}\n" "http://127.0.0.1:$DOWNLOAD_PORT/version.json" 2>/dev/null || echo "  ⚠️ curl 不可用，跳过接口自测"
curl -s -o /dev/null -w "  相册服务器 / -> HTTP %{http_code}\n" "http://127.0.0.1:$ALBUM_PORT/api/devices" 2>/dev/null || true
curl -s -o /dev/null -w "  中继服务器 / -> HTTP %{http_code}\n" "http://127.0.0.1:$RELAY_PORT/" 2>/dev/null || true
# 发版接口鉴权测试（无 token 应 403）
curl -s -o /dev/null -w "  发版接口（无 token）-> HTTP %{http_code}（应为 403）\n" -X POST "http://127.0.0.1:$DOWNLOAD_PORT/api/publish" 2>/dev/null || true

echo ""
echo "=============================================="
echo " 部署完成！"
echo " 日志目录: $LOG_DIR"
echo " 若反向代理/域名已配置，App 端无需改动地址"
echo "=============================================="
