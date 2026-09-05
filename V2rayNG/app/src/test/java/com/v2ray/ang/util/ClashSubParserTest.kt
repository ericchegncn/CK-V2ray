package com.v2ray.ang.util

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mockStatic
import java.util.Base64 as JavaBase64

/**
 * CK v2ray: ClashSubParser 单元测试。
 * 喂真实 Clash YAML → 断言生成的 share link 能被 v2rayNG 原生 fmt 重新解析(端到端回验)。
 */
class ClashSubParserTest {

    private lateinit var mockBase64: MockedStatic<Base64>
    private lateinit var mockLog: MockedStatic<Log>
    private lateinit var mockTextUtils: MockedStatic<TextUtils>

    private val sampleYaml = """
        proxies:
          - name: HK-WS
            type: vmess
            server: hk.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            alterId: 0
            cipher: auto
            udp: true
            tls: true
            servername: hk.example.com
            skip-cert-verify: false
            network: ws
            ws-opts:
              path: /ray
              headers:
                Host: hk.example.com
          - name: JP-Reality
            type: vless
            server: 203.0.113.9
            port: 443
            uuid: 22222222-3333-4444-5555-666666666666
            network: tcp
            tls: true
            udp: true
            flow: xtls-rprx-vision
            client-fingerprint: chrome
            servername: www.microsoft.com
            reality-opts:
              public-key: REALITY_PUBKEY_VALUE
              short-id: abcd
          - name: SG-TJ
            type: trojan
            server: sg.example.com
            port: 443
            password: pass123
            network: ws
            tls: true
            sni: sg.example.com
            ws-opts:
              path: /tj
              headers:
                Host: sg.example.com
            skip-cert-verify: true
          - name: US-SS
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-256-gcm
            password: secret123
            udp: true
          - name: HY2-NODE
            type: hysteria2
            server: hy.example.com
            port: 443
            password: hy2pass
            sni: hy.example.com
            skip-cert-verify: true
            obfs: salamander
            obfs-password: obfs-secret
          - name: RU-SSR
            type: ssr
            server: 9.9.9.9
            port: 1234
            cipher: aes-256-cfb
            password: old
            protocol: auth_aes128_md5
            obfs: plain
    """.trimIndent()

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
        mockTextUtils = mockStatic(TextUtils::class.java, Mockito.RETURNS_DEFAULTS)
        mockTextUtils.`when`<Boolean> { TextUtils.isEmpty(Mockito.any<CharSequence?>()) }
            .thenAnswer { inv -> (inv.arguments[0] as? CharSequence)?.isEmpty() ?: true }
        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<ByteArray> {
            Base64.decode(anyString(), anyInt())
        }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as String
            val flags = invocation.arguments[1] as Int
            val isUrlSafe = (flags and Base64.URL_SAFE) != 0
            val decoder = if (isUrlSafe) JavaBase64.getUrlDecoder() else JavaBase64.getDecoder()
            decoder.decode(input)
        }
        mockBase64.`when`<String> {
            Base64.encodeToString(any(ByteArray::class.java), anyInt())
        }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as ByteArray
            val flags = invocation.arguments[1] as Int
            val encoder = if ((flags and Base64.URL_SAFE) != 0) {
                JavaBase64.getUrlEncoder().withoutPadding()
            } else {
                JavaBase64.getEncoder()
            }
            encoder.encodeToString(input)
        }
    }

    @After
    fun tearDown() {
        mockBase64.close()
        mockTextUtils.close()
        mockLog.close()
    }

    // ---------- 嗅探 ----------

    @Test
    fun looksLikeClash_positive() {
        assertTrue(ClashSubParser.looksLikeClash(sampleYaml))
        assertTrue(ClashSubParser.looksLikeClash("# comment\nproxies: []\n"))
    }

    @Test
    fun looksLikeClash_negative() {
        assertFalse(ClashSubParser.looksLikeClash("vmess://eyJhZGQiOiIxLjIuMy40In0="))
        assertFalse(ClashSubParser.looksLikeClash("plain text without proxies"))
        assertFalse(ClashSubParser.looksLikeClash(null))
        assertFalse(ClashSubParser.looksLikeClash(""))
        assertFalse(ClashSubParser.looksLikeClash("{\"log\":{\"level\":\"info\"}}"))
    }

    // ---------- 转换与回验 ----------

    @Test
    fun convertsVmessWsTls_roundTrip() {
        val links = ClashSubParser.toShareLinks(sampleYaml)
        val vmessLink = links.first { it.startsWith("vmess://") }
        val config = VmessFmt.parse(vmessLink)
        assertNotNull(config)
        assertEquals(EConfigType.VMESS, config!!.configType)
        assertEquals("HK-WS", config.remarks)
        assertEquals("hk.example.com", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", config.password)
    }

    @Test
    fun convertsVlessReality_roundTrip() {
        val links = ClashSubParser.toShareLinks(sampleYaml)
        val vlessLink = links.first { it.startsWith("vless://") }
        val config = VlessFmt.parse(vlessLink)
        assertNotNull(config)
        assertEquals(EConfigType.VLESS, config!!.configType)
        assertEquals("JP-Reality", config.remarks)
        assertEquals("203.0.113.9", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("22222222-3333-4444-5555-666666666666", config.password)
        assertNotNull(config.publicKey)
    }

    @Test
    fun convertsTrojanWs_roundTrip() {
        val links = ClashSubParser.toShareLinks(sampleYaml)
        val trojanLink = links.first { it.startsWith("trojan://") }
        val config = TrojanFmt.parse(trojanLink)
        assertNotNull(config)
        assertEquals(EConfigType.TROJAN, config!!.configType)
        assertEquals("SG-TJ", config.remarks)
        assertEquals("sg.example.com", config.server)
        assertEquals("pass123", config.password)
        assertEquals(true, config.insecure)
    }

    @Test
    fun convertsSs_roundTrip() {
        val links = ClashSubParser.toShareLinks(sampleYaml)
        val ssLink = links.first { it.startsWith("ss://") }
        val config = ShadowsocksFmt.parse(ssLink)
        assertNotNull(config)
        assertEquals(EConfigType.SHADOWSOCKS, config!!.configType)
        assertEquals("US-SS", config.remarks)
        assertEquals("1.2.3.4", config.server)
        assertEquals("8388", config.serverPort)
        assertEquals("secret123", config.password)
    }

    @Test
    fun skipsUnsupportedProxyTypes() {
        // sampleYaml 含 1 个 ssr(不支持), 其余 5 条应全部转换
        val links = ClashSubParser.toShareLinks(sampleYaml)
        assertEquals(5, links.size)
        assertFalse(links.any { it.startsWith("ssr://") })
    }

    @Test
    fun convertsHysteria2_roundTrip() {
        val links = ClashSubParser.toShareLinks(sampleYaml)
        val hy2Link = links.first { it.startsWith("hy2://") }
        assertTrue(hy2Link.contains("obfs-password="))
        val config = Hysteria2Fmt.parse(hy2Link)
        assertNotNull(config)
        assertEquals(EConfigType.HYSTERIA2, config.configType)
        assertEquals("HY2-NODE", config.remarks)
        assertEquals("hy.example.com", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("hy2pass", config.password)
    }

    @Test
    fun nonClashContent_yieldsEmpty() {
        assertTrue(ClashSubParser.toShareLinks("plain text").isEmpty())
        assertTrue(ClashSubParser.toShareLinks(null).isEmpty())
    }

    // ---------- 真实世界风格 clash 订阅 ----------

    @Test
    fun realWorldClashSubscription() {
        // 模仿机场订阅: 注释/emoji 名称/flow 风格 map/带引号值
        val realYaml = """
            # Profile: AirPort Example
            mixed-port: 7890
            allow-lan: false
            mode: rule
            proxies:
              - name: "🇭🇰 HK 香港 01"
                type: vmess
                server: hk01.example.com
                port: 443
                uuid: 33333333-4444-5555-6666-777777777777
                alterId: 0
                cipher: auto
                udp: true
                tls: true
                client-fingerprint: chrome
                skip-cert-verify: false
                servername: hk01.example.com
                network: ws
                ws-opts: {path: /data, headers: {Host: hk01.example.com}}
              - name: '🇯🇵 JP 东京'
                type: trojan
                server: jp01.example.com
                port: 443
                password: "trojan-pass-01"
                udp: true
                sni: jp01.example.com
                skip-cert-verify: true
                network: ws
                ws-opts:
                  path: /tr
                  headers:
                    Host: jp01.example.com
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [DIRECT]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val links = ClashSubParser.toShareLinks(realYaml)
        assertEquals(2, links.size)
        assertTrue(links[0].startsWith("vmess://"))
        assertTrue(links[1].startsWith("trojan://"))

        // emoji 名称经回验保留
        val vmessConfig = VmessFmt.parse(links[0])
        assertNotNull(vmessConfig)
        assertEquals("🇭🇰 HK 香港 01", vmessConfig!!.remarks)
        val trojanConfig = TrojanFmt.parse(links[1])
        assertNotNull(trojanConfig)
        assertEquals("🇯🇵 JP 东京", trojanConfig!!.remarks)
        assertEquals(true, trojanConfig.insecure)
    }
}
