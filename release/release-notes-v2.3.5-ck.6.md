# CK v2ray 2.3.5-ck.6 — 订阅转换修复(实测机场验证)

修复特定机场订阅无法更新出节点的问题。

## 修复内容

- **订阅请求默认 UA 改为 Clash 系** (`ClashMetaForAndroid/2.11.33`): 许多机场按 UA 区分返回格式, 对 v2rayNG UA 直接返回空内容导致 0 节点; clash UA 可拿到完整订阅
- **支持 hysteria2 节点转换**: 实测机场(ktmcloud)clash 订阅 35 个节点全为 hysteria2, 现已支持转 hy2:// 导入(v2rayNG 原生支持)
- 用真实机场订阅完成全链路验证(35 节点转换 + Hysteria2Fmt 回验)

订阅设置中仍可自定义 User-Agent(针对个别特殊机场)。

## SHA-256

- `CK-V2ray-2.3.5-ck.6-arm64-v8a.apk`: 见 SHA256SUMS.txt
