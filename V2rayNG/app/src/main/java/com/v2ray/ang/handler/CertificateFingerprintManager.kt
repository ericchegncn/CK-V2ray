package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CertSha256Request
import com.v2ray.ang.dto.CertSha256Result
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import libv2ray.Libv2ray

object CertificateFingerprintManager {
    private const val TIMEOUT_MS = 5000L

    fun fetchForManualFill(profile: ProfileItem): String? {
        val request = buildRequest(profile) ?: return null
        val result = if (profile.configType == EConfigType.HYSTERIA2) {
            fetch("quic", request) { Libv2ray.fetchQuicCertSha256(it) }
        } else {
            fetch("tls", request) { Libv2ray.fetchTlsCertSha256(it) }
        }

        return result
            ?.takeIf { it.error.isBlank() }
            ?.sha256
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildRequest(profile: ProfileItem): CertSha256Request? {
        if (!isFetchable(profile)) return null

        val server = profile.server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it > 0 } ?: AppConfig.DEFAULT_PORT

        return CertSha256Request(
            address = resolveDialAddress(server),
            port = port,
            serverName = inferServerName(profile),
            timeoutMs = TIMEOUT_MS,
        )
    }

    private fun isFetchable(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.HYSTERIA2 || profile.security == AppConfig.TLS
    }

    private fun fetch(
        type: String,
        request: CertSha256Request,
        fetcher: (String) -> String,
    ): CertSha256Result? {
        return try {
            JsonUtil.fromJsonSafe(fetcher(JsonUtil.toJson(request)), CertSha256Result::class.java)
        } catch (e: UnsatisfiedLinkError) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 API missing in libv2ray", e)
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 failed", e)
            null
        }
    }

    private fun resolveDialAddress(server: String): String {
        if (Utils.isPureIpAddress(server) || !Utils.isDomainName(server)) return server

        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false)
        return HttpUtil.resolveHostToIP(server, preferIpv6)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: server
    }

    private fun inferServerName(profile: ProfileItem): String? {
        val sni = profile.sni?.takeIf { it.isNotBlank() }
        return sni?.takeUnless { Utils.isPureIpAddress(it) }
    }

    /**
     * CK v2ray: 订阅导入后批量自动抓取 hy2 自签证书指纹(按 server:port 去重并发)。
     * Xray core 26.2.6+ 不再允许 allowInsecure, 自签证书节点必须固定 pinnedCA256 才能连接。
     * 成功: 填入 pinnedCA256 并关闭 insecure → 节点开箱即用;
     * 失败(网络不可达等): 静默保留原状, 用户可手动获取或网络可达后重新更新订阅自动补抓。
     */
    fun batchFetchForHy2(configs: List<ProfileItem>) {
        val targets = configs.filter {
            it.configType == EConfigType.HYSTERIA2 &&
                it.insecure == true &&
                it.pinnedCA256.isNullOrEmpty() &&
                it.server.isNotNullEmpty()
        }
        if (targets.isEmpty()) return

        val groups = targets.groupBy { "${it.server}:${it.serverPort}" }
        val results = java.util.concurrent.ConcurrentHashMap<String, String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(4, groups.size).coerceAtLeast(1)
        )
        val latch = java.util.concurrent.CountDownLatch(groups.size)

        groups.keys.forEach { key ->
            pool.execute {
                try {
                    val profile = targets.first { "${it.server}:${it.serverPort}" == key }
                    fetchForManualFill(profile)?.let { results[key] = it }
                } catch (_: Exception) {
                    // 静默失败
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pool.shutdownNow()

        var fetched = 0
        targets.forEach { cfg ->
            results["${cfg.server}:${cfg.serverPort}"]?.let { fp ->
                cfg.pinnedCA256 = fp
                cfg.insecure = false
                fetched++
            }
        }
        if (fetched > 0) {
            LogUtil.i(AppConfig.TAG, "Auto fetched cert fingerprint for $fetched hysteria2 node(s)")
        }
    }
}
