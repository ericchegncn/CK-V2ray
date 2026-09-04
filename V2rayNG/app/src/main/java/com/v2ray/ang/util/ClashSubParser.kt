package com.v2ray.ang.util

import com.google.gson.Gson
import org.yaml.snakeyaml.Yaml
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * CK v2ray 订阅转换: 将 Clash 订阅(YAML, proxies 列表)转为 v2rayNG 可导入的 share link 行。
 *
 * 与 Android 订阅更新链路衔接: 内容既不是 base64 节点订阅、也不是 v2rayN 自定义 JSON 时,
 * 尝试按 Clash 订阅解析, 把每个 proxy 反向生成为对应协议的 share link URI
 * (vmess:// / vless:// / trojan:// / ss:// / socks5://), 复用 v2rayNG 原生 fmt 解析导入。
 *
 * 设计: 不依赖 android.*, 便于 JVM 单元测试。
 */
object ClashSubParser {

    private val yaml = Yaml()
    private val gson = Gson()

    /** 嗅探: 文本是否是可解析的 Clash YAML 且含顶层 proxies 列表 */
    fun looksLikeClash(text: String?): Boolean = parseProxies(text) != null

    /**
     * Clash 订阅内容 → share link 行列表(每行一个 URI, 顺序同订阅)。
     * 无法识别的 proxy 类型(ssr/wireguard/hysteria2/tuic 等)自动跳过。
     */
    fun toShareLinks(text: String?): List<String> {
        val proxies = parseProxies(text) ?: return emptyList()
        return proxies.mapNotNull { proxyToLink(it) }
    }

    // ---------- 解析 ----------

    private fun parseProxies(text: String?): List<Map<String, Any?>>? {
        if (text.isNullOrBlank()) return null
        val root = try {
            yaml.load<Any>(text.trimStart('\uFEFF'))
        } catch (e: Exception) {
            return null
        }
        if (root !is Map<*, *>) return null
        val proxies = root["proxies"] as? List<*> ?: return null
        if (proxies.isEmpty()) return emptyList()
        return proxies.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
        }
    }

    private fun proxyToLink(p: Map<String, Any?>): String? {
        val type = p.str("type")?.lowercase() ?: return null
        val name = p.str("name") ?: ""
        val server = p.str("server") ?: return null
        val port = p.num("port") ?: return null
        return try {
            when (type) {
                "vmess" -> vmessToLink(p, name, server, port)
                "vless" -> vlessToLink(p, name, server, port)
                "trojan" -> trojanToLink(p, name, server, port)
                "ss" -> ssToLink(p, name, server, port)
                "socks5" -> socksToLink(p, name, server, port)
                else -> null // ssr/wireguard/hysteria2/tuic/anytls 等暂不支持
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 传输层公共字段 ----------

    private class Transport(
        val network: String?,       // tcp / ws / grpc (h2/http 少见, 保守处理)
        val tls: Boolean,
        val servername: String?,
        val fp: String?,
        val alpn: List<String>?,
        val skipCertVerify: Boolean,
        val wsPath: String?,
        val wsHost: String?,
        val grpcServiceName: String?,
        val flow: String?,
        val pbk: String?,
        val sid: String?,
    )

    private fun readTransport(p: Map<String, Any?>): Transport {
        val network = p.str("network") ?: "tcp"
        val wsOpts = p.sub("ws-opts")
        val grpcOpts = p.sub("grpc-opts")
        val reality = p.sub("reality-opts")
        val alpn = (p["alpn"] as? List<*>)?.mapNotNull { it?.toString() }
        return Transport(
            network = network,
            tls = p.bool("tls"),
            servername = p.str("servername"),
            fp = p.str("client-fingerprint"),
            alpn = alpn,
            skipCertVerify = p.bool("skip-cert-verify"),
            wsPath = wsOpts?.str("path"),
            wsHost = wsOpts?.sub("headers")?.str("Host"),
            grpcServiceName = grpcOpts?.str("grpc-service-name"),
            flow = p.str("flow"),
            pbk = reality?.str("public-key"),
            sid = reality?.str("short-id"),
        )
    }

    // ---------- vmess ----------

    private fun vmessToLink(p: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val uuid = p.str("uuid") ?: return null
        val t = readTransport(p)
        val json = linkedMapOf<String, Any?>(
            "v" to "2",
            "ps" to name,
            "add" to server,
            "port" to port.toString(),
            "id" to uuid,
            "aid" to (p.num("alterId")?.toString() ?: "0"),
            "scy" to (p.str("cipher") ?: "auto"),
        )
        // network: 仅保留 v2rayNG 支持的 tcp/ws/grpc; http 伪装归入 tcp
        when (t.network) {
            "ws", "grpc", "kcp" -> json["net"] = t.network
            else -> json["net"] = "tcp"
        }
        json["type"] = if (t.network == "http") "http" else "none"
        when (t.network) {
            "ws" -> {
                json["host"] = t.wsHost ?: ""
                json["path"] = t.wsPath ?: ""
            }
            "grpc" -> {
                // v2rayN vmess grpc 约定: path 存 service-name
                json["host"] = ""
                json["path"] = t.grpcServiceName ?: ""
            }
            else -> {
                json["host"] = ""
                json["path"] = ""
            }
        }
        json["tls"] = if (t.tls) "tls" else ""
        if (t.servername != null) json["sni"] = t.servername
        if (!t.alpn.isNullOrEmpty()) json["alpn"] = t.alpn.joinToString(",")
        if (t.fp != null) json["fp"] = t.fp
        if (t.skipCertVerify) json["insecure"] = "1"
        return "vmess://" + Base64.getEncoder()
            .encodeToString(gson.toJson(json).toByteArray(StandardCharsets.UTF_8))
    }

    // ---------- vless ----------

    private fun vlessToLink(p: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val uuid = p.str("uuid") ?: return null
        val t = readTransport(p)
        val security = when {
            t.tls && t.pbk != null -> "reality"
            t.tls -> "tls"
            else -> "none"
        }
        val sb = StringBuilder("vless://$uuid@$server:$port?")
        appendParam(sb, "encryption", "none")
        appendParam(sb, "type", t.network?.takeIf { it == "ws" || it == "grpc" || it == "tcp" } ?: "tcp")
        appendParam(sb, "security", security)
        if (security == "reality") {
            appendParam(sb, "pbk", t.pbk)
            appendParam(sb, "sid", t.sid)
            appendParam(sb, "flow", t.flow?.takeIf { it.isNotEmpty() } ?: "xtls-rprx-vision")
        } else {
            appendParam(sb, "flow", t.flow)
        }
        if (t.tls) appendParam(sb, "sni", t.servername)
        appendParam(sb, "fp", t.fp)
        if (t.network == "ws") {
            appendParam(sb, "host", t.wsHost)
            appendParam(sb, "path", t.wsPath)
        } else if (t.network == "grpc") {
            appendParam(sb, "serviceName", t.grpcServiceName)
        }
        if (t.skipCertVerify) appendParam(sb, "allowInsecure", "1")
        return sb.toString().removeSuffix("&") + "#" + enc(name)
    }

    // ---------- trojan ----------

    private fun trojanToLink(p: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val password = p.str("password") ?: return null
        val t = readTransport(p)
        val sb = StringBuilder("trojan://${enc(password)}@$server:$port?")
        appendParam(sb, "security", if (t.tls) "tls" else "none")
        if (t.tls) appendParam(sb, "sni", t.servername)
        appendParam(sb, "fp", t.fp)
        if (t.network == "ws") {
            appendParam(sb, "type", "ws")
            appendParam(sb, "host", t.wsHost)
            appendParam(sb, "path", t.wsPath)
        } else if (t.network == "grpc") {
            appendParam(sb, "type", "grpc")
            appendParam(sb, "serviceName", t.grpcServiceName)
        }
        if (t.skipCertVerify) appendParam(sb, "allowInsecure", "1")
        return sb.toString().removeSuffix("&") + "#" + enc(name)
    }

    // ---------- shadowsocks ----------

    private fun ssToLink(p: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val cipher = p.str("cipher") ?: return null
        val password = p.str("password") ?: return null
        val userInfo = Base64.getEncoder()
            .encodeToString("$cipher:$password".toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder("ss://$userInfo@$server:$port")
        // Clash 的 ss 插件(obfs / v2ray-plugin)转 SIP002 plugin 参数
        val plugin = p.str("plugin")
        val pluginOpts = p.sub("plugin-opts")
        if (plugin != null && pluginOpts != null) {
            val param = when (plugin) {
                "obfs" -> {
                    val mode = pluginOpts.str("mode") ?: "http"
                    val host = pluginOpts.str("host")
                    buildString {
                        append("obfs-local;obfs=").append(mode)
                        if (host != null) append(";obfs-host=").append(host)
                    }
                }
                "v2ray-plugin" -> {
                    buildString {
                        append("v2ray-plugin")
                        pluginOpts.str("mode")?.let { append(";mode=").append(it) }
                        if (pluginOpts.bool("tls")) append(";tls")
                        pluginOpts.str("host")?.let { append(";host=").append(it) }
                        pluginOpts.str("path")?.let { append(";path=").append(it) }
                    }
                }
                else -> null
            }
            if (param != null) {
                sb.append("?plugin=").append(URLEncoder.encode(param, "UTF-8"))
            }
        }
        sb.append('#').append(enc(name))
        return sb.toString()
    }

    // ---------- socks5 ----------

    private fun socksToLink(p: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val username = p.str("username")
        val password = p.str("password")
        val cred = if (username != null && password != null) "$username:$password@" else ""
        return "socks5://$cred$server:$port#${enc(name)}"
    }

    // ---------- helpers ----------

    private fun Map<String, Any?>.str(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.num(key: String): Int? =
        when (val v = this[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }

    private fun Map<String, Any?>.bool(key: String): Boolean = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        else -> false
    }

    private fun Map<String, Any?>.sub(key: String): Map<String, Any?>? =
        (this[key] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

    private fun appendParam(sb: StringBuilder, key: String, value: String?) {
        if (value.isNullOrEmpty()) return
        sb.append(key).append('=').append(enc(value)).append('&')
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
