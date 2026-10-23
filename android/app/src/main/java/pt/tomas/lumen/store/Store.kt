package pt.tomas.lumen.store

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import pt.tomas.lumen.lumen
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Estado de uma app da loja, para a UI. */
data class StoreItem(
    val app: StoreApp,
    val installed: String? = null,
    val latest: String? = null,
    val status: Status = Status.Idle,
) {
    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class Downloading(val percent: Int) : Status
        data object Verifying : Status
        data object Confirming : Status
        data class Error(val message: String) : Status
    }

    val updateAvailable get() = installed != null && latest != null && compareVersions(latest, installed) > 0
}

/**
 * Loja Lumen: descobre a versão mais recente na origem oficial, descarrega, confirma que o APK tem o pacote e a
 * assinatura esperados e só então o entrega ao instalador do sistema. O Android 11 do Fire OS pede sempre
 * confirmação ao utilizador para instalar ou atualizar (não há instalação silenciosa sem privilégios de sistema).
 */
class Store(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("lumen_store", Context.MODE_PRIVATE)

    // As versões encontradas ficam guardadas, para a loja mostrar o estado mesmo antes da próxima verificação.
    private val _items = MutableStateFlow(CATALOG.map { StoreItem(it, latest = prefs.getString("latest_${it.pkg}", null)) })
    val items: StateFlow<List<StoreItem>> = _items

    /** Verifica se passaram mais de [STALE_MS] desde a última verificação (chamado quando o Lumen aparece). */
    fun checkIfStale() {
        refreshInstalled()
        Log.i(TAG, "última verificação: ${prefs.getLong("last_check", 0)}")
        val missing = _items.value.any { it.latest == null }
        if (missing || System.currentTimeMillis() - prefs.getLong("last_check", 0) > STALE_MS) checkAll()
    }

    fun checkAll() = scope.launch {
        refreshInstalled()
        for (app in CATALOG) {
            set(app.pkg) { it.copy(status = StoreItem.Status.Checking) }
            val result = runCatching { latestRelease(app) }
            Log.i(TAG, "${app.pkg}: " + result.fold({ "versão ${it.first} em ${it.second}" }, { "falhou: $it" }))
            set(app.pkg) {
                result.fold(
                    { (version, _) ->
                        prefs.edit().putString("latest_${app.pkg}", version).apply()
                        it.copy(latest = version, status = StoreItem.Status.Idle)
                    },
                    { e -> it.copy(status = StoreItem.Status.Error("Sem ligação à origem: ${e.message}")) },
                )
            }
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
    }

    /** Instala ou atualiza: descarregar → verificar → instalador do sistema. */
    fun install(app: StoreApp) = scope.launch {
        try {
            val (version, url) = latestRelease(app)
            set(app.pkg) { it.copy(latest = version, status = StoreItem.Status.Downloading(0)) }
            val apk = download(url, cacheApk(app)) { pct ->
                set(app.pkg) { it.copy(status = StoreItem.Status.Downloading(pct)) }
            }
            set(app.pkg) { it.copy(status = StoreItem.Status.Verifying) }
            verify(app, apk)
            set(app.pkg) { it.copy(status = StoreItem.Status.Confirming) }
            commit(app, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Falhou ${app.pkg}", e)
            set(app.pkg) { it.copy(status = StoreItem.Status.Error(e.message ?: "erro")) }
        }
    }

    /** Resultado do instalador (chamado pelo [InstallResultReceiver]). */
    internal fun onInstallResult(pkg: String, status: Int, message: String?) {
        File(context.cacheDir, "store/$pkg.apk").delete()
        refreshInstalled()
        set(pkg) {
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> it.copy(status = StoreItem.Status.Idle)
                PackageInstaller.STATUS_FAILURE_ABORTED -> it.copy(status = StoreItem.Status.Idle) // cancelado
                else -> it.copy(status = StoreItem.Status.Error(message ?: "instalação falhou ($status)"))
            }
        }
    }

    private fun refreshInstalled() {
        val pm = context.packageManager
        _items.update { list ->
            list.map { item ->
                item.copy(installed = runCatching { pm.getPackageInfo(item.app.pkg, 0).versionName }.getOrNull())
            }
        }
    }

    private fun set(pkg: String, change: (StoreItem) -> StoreItem) =
        _items.update { list -> list.map { if (it.app.pkg == pkg) change(it) else it } }

    /** Versão e endereço do APK mais recente na origem oficial. */
    private fun latestRelease(app: StoreApp): Pair<String, String> = when (val src = app.source) {
        is ReleaseSource.GitHub -> {
            val json = JSONObject(httpText("https://api.github.com/repos/${src.repo}/releases/latest"))
            val assets = json.getJSONArray("assets")
            val tag = json.getString("tag_name").removePrefix("v")
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstNotNullOfOrNull { a ->
                    src.asset.matchEntire(a.getString("name"))?.let { m ->
                        // "3_0_379" → "3.0.379", para se ler como a versão instalada.
                        (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.replace('_', '.') ?: tag) to a.getString("browser_download_url")
                    }
                } ?: error("APK não encontrado na release")
        }
        is ReleaseSource.Page -> {
            src.link.findAll(httpText(src.page))
                .map { m -> m.groupValues[1] to URL(URL(src.page), m.value).toString() }
                .maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
                ?: error("APK não encontrado na página oficial")
        }
        is ReleaseSource.Direct -> {
            val head = open(src.url, method = "HEAD")
            val finalUrl = head.url.toString()
            val tag = head.getHeaderField("ETag") ?: head.getHeaderField("Last-Modified") ?: ""
            head.disconnect()
            val known = prefs.getString("latest_${app.pkg}", null)
            if (tag.isNotEmpty() && tag == prefs.getString("etag_${app.pkg}", null) && known != null) {
                known to finalUrl
            } else {
                // Ficheiro novo (ou primeira vez): descarregar para saber a versão. Fica na cache para a instalação.
                val apk = download(finalUrl, cacheApk(app)) {}
                val version = context.packageManager.getPackageArchiveInfo(apk.path, 0)?.versionName
                    ?: error("APK inválido na origem oficial")
                prefs.edit().putString("etag_${app.pkg}", tag).putString("latest_${app.pkg}", version).apply()
                version to finalUrl
            }
        }
    }

    /** Pacote certo + assinatura igual à fixada no catálogo + não é uma versão mais antiga do que a instalada. */
    private fun verify(app: StoreApp, apk: File) {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("O ficheiro descarregado não é um APK válido")
        check(info.packageName == app.pkg) { "Pacote inesperado: ${info.packageName}" }
        // No Android 11 o signingInfo de um APK por instalar pode vir vazio; nesse caso usamos a API antiga.
        @Suppress("DEPRECATION")
        val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
            ?: pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures.orEmpty()
        val digests = signers.map { sha256(it.toByteArray()) }
        check(digests.isNotEmpty() && digests.all { it == app.certSha256 }) { "Assinatura não corresponde à oficial — instalação bloqueada" }
        val installed = runCatching { pm.getPackageInfo(app.pkg, 0).longVersionCode }.getOrNull()
        if (installed != null) check(info.longVersionCode >= installed) { "A versão descarregada é mais antiga do que a instalada" }
    }

    private fun commit(app: StoreApp, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.pkg)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_PKG, app.pkg)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun download(url: String, target: File, progress: (Int) -> Unit): File {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return target
    }

    private fun cacheApk(app: StoreApp) = File(context.cacheDir, "store").apply { mkdirs() }.resolve("${app.pkg}.apk")

    private fun httpText(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        var current = URL(url)
        // Segue redireções (incl. GitHub → objects.githubusercontent.com), sempre em HTTPS.
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Lumen/1.0 (Fire TV)")
            val code = c.responseCode
            if (code in 300..399) {
                val next = URL(current, c.getHeaderField("Location"))
                c.disconnect()
                check(next.protocol == "https") { "Redireção insegura recusada" }
                current = next
            } else {
                check(code == 200) { "HTTP $code" }
                return c
            }
        }
        error("Demasiadas redireções")
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val TAG = "LumenStore"
        private const val STALE_MS = 12 * 3_600_000L

        private fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Compara "21.3" com "21.10.1" número a número. */
fun compareVersions(a: String, b: String): Int {
    val x = a.split(Regex("\\D+")).filter { it.isNotEmpty() }.map { it.toLong() }
    val y = b.split(Regex("\\D+")).filter { it.isNotEmpty() }.map { it.toLong() }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** Recebe o resultado do instalador do sistema; se faltar a confirmação do utilizador, mostra-a. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(Store.EXTRA_PKG) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        context.lumen.store.onInstallResult(pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
