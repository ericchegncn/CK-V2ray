# CK v2ray 2.3.5-ck.5 — 订阅转换修复版

修复 ck.4 中订阅转换未在所有导入路径生效的问题。

## 修复内容

- **剪贴板/文件/批量导入路径补上 Clash 自动转换**（ck.4 只覆盖了订阅 URL 更新路径；现在主界面「+」导入 clash 订阅同样自动转换）
- 修复节点名含空格时乱码的问题（名称编码改用 %20）
- 解析健壮性兜底

## 使用方式

- **方式一**: 订阅管理 → 添加订阅 URL（返回 Clash YAML 的链接），更新后自动转换导入
- **方式二**: 主界面 + → 从剪贴板导入（粘贴 Clash 订阅全文），自动转换导入

支持 clash 节点: vmess (ws/grpc/tcp+tls) / vless (reality/ws/grpc) / trojan / ss / socks5; 不支持的节点类型自动跳过。

## SHA-256

- `CK-V2ray-2.3.5-ck.5-arm64-v8a.apk`: 见 SHA256SUMS.txt
