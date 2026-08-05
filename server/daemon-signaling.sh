#!/usr/bin/env bash
# 信令服务器守护脚本：检测 8095 端口无监听时自动重启
set -u
LOG=/tmp/signaling-daemon.log
while true; do
  if ! ss -tln 2>/dev/null | grep -q ":8095 "; then
    echo "$(date '+%F %T') 8095 无监听，重启信令服务器" >> "$LOG"
    cd /workspace/server && DIAG=1 PORT=8095 node server.js >> /tmp/signaling-server.log 2>&1 &
    echo "$(date '+%F %T') 已重启，PID=$!" >> "$LOG"
  fi
  sleep 15
done
