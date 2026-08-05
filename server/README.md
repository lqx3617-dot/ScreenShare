# ScreenShare 信令服务器部署指南

口令共享模式依赖本信令服务器中转 SDP/ICE。预览环境的地址是临时的，正式使用请部署到自己的服务器。

## 1. 快速启动

```bash
cd server
npm install
node server.js
```

默认监听 `0.0.0.0:8080`，WebSocket 路径为 `/ws`。可用环境变量 `PORT` 修改端口。

## 2. 部署为 systemd 常驻服务（推荐）

创建 `/etc/systemd/system/screenshare-signal.service`：

```ini
[Unit]
Description=ScreenShare WebRTC Signaling Server
After=network.target

[Service]
WorkingDirectory=/opt/screenshare/server
ExecStart=/usr/bin/node server.js
Environment=PORT=8080
Restart=always
RestartSec=3
User=www-data

[Install]
WantedBy=multi-user.target
```

启用并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now screenshare-signal
sudo systemctl status screenshare-signal
```

## 3. HTTPS（wss）配置

Android 客户端默认使用 `wss://`。生产环境务必加 HTTPS，避免信令明文裸奔。

### 方案 A：Caddy 反代（自动 HTTPS，推荐）

```bash
# Caddyfile
signal.example.com {
    reverse_proxy localhost:8080
}
```

### 方案 B：Nginx 反代

```nginx
server {
    listen 443 ssl;
    server_name signal.example.com;
    ssl_certificate     /etc/letsencrypt/live/signal.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/signal.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

## 4. 客户端指向你的服务器

编辑根目录 `gradle.properties`：

```properties
screenshare.signal.url=wss://signal.example.com/ws
```

重新构建 APK：

```bash
./gradlew assembleDebug
```

## 5. 安全建议

- 信令内容（SDP/ICE）走 wss 加密，但服务器可看到明文。如需端到端保密，可在应用层对信令负载做加密，或加入消息签名。
- 当前协议未做身份认证：任何知道口令的人都能加入房间。房间口令即访问凭证，建议口令足够随机（6+ 位）。
- 可用 `nftables`/`ufw` 限制仅开放 443/8080 端口。

## 6. 协议参考

见 `server/server.js` 头部注释：`create` / `join` / `relay` 三类消息，房间一对一同口令配对。
