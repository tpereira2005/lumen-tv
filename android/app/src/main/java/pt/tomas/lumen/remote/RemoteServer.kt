package pt.tomas.lumen.remote

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import pt.tomas.lumen.MainActivity
import pt.tomas.lumen.lumen
import pt.tomas.lumen.tv.TvSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Comando do iPhone: pequeno servidor HTTP na rede local (porta [PORT]) que serve a página do comando
 * (assets/remote/index.html) e uma API JSON.
 *
 * - Setas, OK, Voltar e teclas de reprodução vão para a TV pela rede; a TV passa-as ao Fire TV por CEC,
 *   por isso o comando funciona em qualquer app (não só no Lumen).
 * - Segurança: só aceita pedidos com o código secreto ([token]) que o Lumen mostra no QR code; só endereços da rede local.
 */
class RemoteServer(private val context: Context) {
    private var socket: ServerSocket? = null

    val token: String by lazy {
        val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        sp.getString("remote_token", null) ?: ByteArray(9).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { sp.edit().putString("remote_token", it).apply() }
    }

    /** Endereço para abrir no iPhone (o QR code no Lumen aponta para aqui). */
    fun url(): String? = localIp()?.let { "http://$it:$PORT/?t=$token" }

    fun start() {
        if (socket != null) return
        thread(name = "lumen-remote", isDaemon = true) {
            try {
                ServerSocket(PORT).use { server ->
                    socket = server
                    Log.i(TAG, "Comando à escuta em ${url()}")
                    while (!server.isClosed) {
                        val client = server.accept()
                        thread(isDaemon = true) { runCatching { handle(client) }.onFailure { Log.w(TAG, "Pedido falhou: $it") } }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Servidor parou: ${e.message}")
            } finally {
                socket = null
            }
        }
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 5_000
        if (!isLocal(s.inetAddress)) return respond(s.getOutputStream(), 403, "text/plain", "Só na rede local")
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val (method, target) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = s.getOutputStream()

        // Ficheiros da página (públicos; não fazem nada sem o código).
        when (path) {
            "/", "/index.html" -> return asset(out, "remote/index.html", "text/html; charset=utf-8")
            "/manifest.json" -> return asset(out, "remote/manifest.json", "application/manifest+json")
            "/icon.png" -> return asset(out, "remote/icon.png", "image/png")
        }
        val auth = headers["x-lumen-token"] ?: query["t"]
        if (auth != token) return respond(out, 401, "application/json", """{"erro":"código inválido"}""")
        if (method != "GET" && method != "POST") return respond(out, 405, "text/plain", "")

        val result: Any = when (path) {
            "/api/state" -> state()
            "/api/key" -> key(query["k"].orEmpty())
            "/api/volume" -> { context.lumen.tv.volumeStep(query["d"]?.toIntOrNull() ?: 0); ok() }
            "/api/mute" -> { context.lumen.tv.toggleMute(); ok() }
            "/api/power" -> { context.lumen.tv.powerOffIfOn(); ok() }
            "/api/source" -> source(query["id"].orEmpty())
            "/api/launch" -> launch(query["pkg"].orEmpty())
            "/api/home" -> { openLumen(null); ok() }
            "/api/search" -> { openLumen(query["q"].orEmpty()); ok() }
            "/api/timer" -> { context.lumen.sleepTimer.cycle(); ok() }
            else -> return respond(out, 404, "application/json", """{"erro":"não existe"}""")
        }
        respond(out, 200, "application/json; charset=utf-8", result.toString())
    }

    private fun ok() = JSONObject().put("ok", true)

    /** Teclas aceites e o nome que a TV Hisense lhes dá; seguem por CEC para o Fire TV. */
    private fun key(k: String): JSONObject {
        val tvKey = mapOf(
            "up" to "KEY_UP", "down" to "KEY_DOWN", "left" to "KEY_LEFT", "right" to "KEY_RIGHT",
            "ok" to "KEY_OK", "back" to "KEY_RETURNS", "play" to "KEY_PLAY", "pause" to "KEY_PAUSE",
            "rew" to "KEY_BACK", "ff" to "KEY_FORWARDS", "menu" to "KEY_MENU",
        )[k] ?: return JSONObject().put("ok", false).put("erro", "tecla desconhecida")
        context.lumen.tv.sendKey(tvKey)
        return ok()
    }

    private fun source(id: String): JSONObject {
        val src = context.lumen.tv.state.value.sources.firstOrNull { it.id == id } ?: return JSONObject().put("ok", false)
        context.lumen.tv.changeSource(TvSource(src.id, src.name, src.hasSignal))
        return ok()
    }

    private fun launch(pkg: String): JSONObject {
        val app = context.lumen.apps.apps.value.firstOrNull { it.pkg == pkg } ?: return JSONObject().put("ok", false)
        // O processo do Lumen tem o serviço de acessibilidade ligado, por isso pode abrir apps em segundo plano.
        context.startActivity(Intent(app.launch))
        context.lumen.prefs.recordLaunch(pkg)
        return ok()
    }

    private fun openLumen(search: String?) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (search != null) putExtra(MainActivity.EXTRA_SEARCH, search) }
        )
    }

    private fun state(): JSONObject {
        val app = context.lumen
        val tv = app.tv.state.value
        // O comando pode ser usado sem o Lumen estar no ecrã: liga-se à TV (e atualiza volume/entradas) por conta própria.
        if (!tv.connected || tv.sources.isEmpty()) app.tv.refresh()
        val byPkg = app.apps.apps.value.associateBy { it.pkg }
        val favs = JSONArray()
        app.prefs.favorites.value.mapNotNull { byPkg[it] }.forEach { a ->
            favs.put(JSONObject().put("pkg", a.pkg).put("nome", a.label).put("cor", "#%06X".format(a.accent.toArgb() and 0xFFFFFF)))
        }
        val sources = JSONArray()
        tv.sources.forEach { sources.put(JSONObject().put("id", it.id).put("nome", it.name).put("sinal", it.hasSignal)) }
        return JSONObject()
            .put("tv", tv.connected).put("volume", tv.volume ?: JSONObject.NULL).put("silencio", tv.muted)
            .put("entrada", tv.source ?: JSONObject.NULL).put("entradas", sources).put("favoritos", favs)
            .put("temporizador", app.sleepTimer.endsAt.value ?: JSONObject.NULL)
    }

    private fun asset(out: OutputStream, name: String, type: String) {
        val bytes = context.assets.open(name).use { it.readBytes() }
        respond(out, 200, type, bytes)
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) = respond(out, code, type, body.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = mapOf(200 to "OK", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "OK"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun parseQuery(q: String) = q.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun isLocal(a: InetAddress) = a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress

    companion object {
        const val PORT = 8765
        private const val TAG = "LumenRemote"
    }
}
