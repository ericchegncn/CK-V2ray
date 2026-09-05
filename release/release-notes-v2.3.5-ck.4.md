# CK v2ray 2.3.5-ck.4 — 订阅转换

新增**订阅自动转换**: 添加/更新订阅时, 若链接返回的是 Clash 订阅(YAML), 自动解析节点并转换为 v2rayNG 订阅导入, 无需手动转换。

## 新功能

- **Clash → v2ray 订阅自动转换**: 订阅 URL 返回 clash yaml 时自动转换, 支持:
  - vmess (ws / grpc / tcp + tls)
  - vless (reality / ws / grpc)
  - trojan (ws / tcp)
  - shadowsocks (含 obfs / v2ray-plugin)
  - socks5
- 不支持的节点类型 (ssr / wireguard / hysteria2 等) 自动跳过
- 转换在本地完成, 订阅内容不出设备

## 其他

- CI 修复: 发布签名回退 debug keystore (原 keystore secrets 未配置导致 CI 失败), 构建监听分支改为 main

## SHA-256

- `CK-V2ray-2.3.5-ck.4-arm64-v8a.apk`: 见 SHA256SUMS.txt
