package pt.tomas.lumen.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TvSource(val id: String, val name: String, val hasSignal: Boolean)

data class TvState(
    val connected: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean = false,
    val source: String? = null,
    val sources: List<TvSource> = emptyList(),
)

/**
 * Controlo da TV Hisense (VIDAA) pela rede local: broker MQTT da TV na porta 36669, TLS com o
 * certificado de cliente da app oficial de comando (assets/hisense). A TV autoriza por identidade
 * ([DEVICE]); o emparelhamento com PIN foi feito uma vez com tools/hisense_pair.py.
 *
 * Com a TV em standby o broker não responde, por isso uma ligação bem-sucedida também significa "TV ligada".
 */
class TvRemote(private val context: Context, private val hostProvider: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var client: MqttClient? = null
    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = _state

    fun volumeStep(delta: Int) = launch {
        val current = _state.value.volume ?: run {
            publish("platform_service", "getvolume", "")
            return@launch // a resposta atualiza o estado; a próxima tecla já usa o valor certo
        }
        val target = (current + delta).coerceIn(0, 100)
        _state.value = _state.value.copy(volume = target, muted = false)
        // KEY_VOLUMEUP é ignorado por este firmware; o volume absoluto funciona nos dois sentidos.
        publish("platform_service", "changevolume", target.toString())
    }

    fun toggleMute() = launch { publish("remote_service", "sendkey", "KEY_MUTE") }

    /** Tecla do comando da TV; setas/OK/Voltar seguem por CEC para o Fire TV (testado). */
    fun sendKey(key: String) = launch { publish("remote_service", "sendkey", key) }

    fun changeSource(source: TvSource) = launch {
        publish("ui_service", "changesource", JSONObject().put("sourceid", source.id).put("sourcename", source.name).toString())
    }

    /** Desliga a TV só se ela estiver ligada (se estiver desligada a ligação falha e nada é enviado). */
    fun powerOffIfOn() = launch {
        if (ensureConnected(timeoutSec = 3)) publish("remote_service", "sendkey", "KEY_POWER")
    }

    /** Liga/atualiza o estado (chamado quando o Lumen fica visível). */
    fun refresh() = launch {
        if (ensureConnected()) {
            publish("platform_service", "getvolume", "")
            publish("ui_service", "sourcelist", "")
        }
    }

    fun disconnect() = launch {
        lock.withLock {
            runCatching { client?.disconnect(500) }
            runCatching { client?.close() }
            client = null
            _state.value = _state.value.copy(connected = false)
        }
    }

    private fun launch(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { Log.w(TAG, "Falha: ${it.message}") }
    }

    private suspend fun publish(service: String, action: String, payload: String) {
        if (!ensureConnected()) return
        client?.publish("/remoteapp/tv/$service/$DEVICE/actions/$action", MqttMessage(payload.toByteArray()).apply { qos = 0 })
    }

    private suspend fun ensureConnected(timeoutSec: Int = 5): Boolean = lock.withLock {
        client?.takeIf { it.isConnected }?.let { return true }
        runCatching { client?.close() }
        val c = MqttClient("ssl://${hostProvider()}:36669", "lumen-${System.currentTimeMillis()}", MemoryPersistence())
        val options = MqttConnectOptions().apply {
            userName = "hisenseservice"
            password = "multimqttservice".toCharArray()
            socketFactory = sslFactory
            isHttpsHostnameVerificationEnabled = false
            connectionTimeout = timeoutSec
            keepAliveInterval = 30
            isCleanSession = true
        }
        c.setCallback(callback)
        val ok = runCatching { c.connect(options) }.onFailure { Log.i(TAG, "TV indisponível: ${it.message}") }.isSuccess
        if (ok) {
            c.subscribe("/remoteapp/mobile/$DEVICE/#", 0)
            c.subscribe("/remoteapp/mobile/broadcast/#", 0)
            client = c
        }
        _state.value = _state.value.copy(connected = ok)
        ok
    }

    private val callback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            _state.value = _state.value.copy(connected = false)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

        override fun messageArrived(topic: String, message: MqttMessage) {
            val text = String(message.payload)
            runCatching {
                when {
                    topic.endsWith("volumechange") || topic.endsWith("/getvolume") -> {
                        val json = JSONObject(text)
                        val type = json.optInt("volume_type")
                        val value = json.optInt("volume_value")
                        // volume_type 0 = nível do altifalante, 2 = silêncio (1/0)
                        _state.value = when (type) {
                            0 -> _state.value.copy(volume = value)
                            2 -> _state.value.copy(muted = value == 1)
                            else -> _state.value
                        }
                    }
                    topic.endsWith("ui_service/state") -> {
                        val json = JSONObject(text)
                        if (json.optString("statetype") == "sourceswitch") {
                            _state.value = _state.value.copy(source = json.optString("sourcename"))
                        }
                    }
                    topic.endsWith("sourcelist") -> {
                        val arr = JSONArray(text)
                        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                            TvSource(it.optString("sourceid"), it.optString("sourcename"), it.optString("is_signal") == "1")
                        }
                        _state.value = _state.value.copy(sources = list)
                    }
                }
            }
        }
    }

    private val sslFactory: SSLSocketFactory by lazy {
        val assets = context.assets
        val cert = assets.open("hisense/rcm_certchain_pem.cer").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val pem = assets.open("hisense/rcm_pem_privkey.pkcs8").bufferedReader().readText()
        val der = Base64.decode(pem.lines().filter { !it.startsWith("-----") }.joinToString(""), Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("client", key, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        // A TV usa um certificado próprio, sem cadeia pública; a ligação é só dentro da rede local.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }.socketFactory
    }

    companion object {
        /** Tem de coincidir com DEVICE em tools/hisense_pair.py (identidade emparelhada com a TV). */
        const val DEVICE = "02:4C:55:4D:45:4E\$normal"
        private const val TAG = "LumenTv"
    }
}
