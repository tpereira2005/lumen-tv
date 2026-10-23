package pt.tomas.lumen

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tomas.lumen.data.AppEntry
import pt.tomas.lumen.store.StoreItem
import pt.tomas.lumen.tv.TvSource
import pt.tomas.lumen.ui.HomeActions
import pt.tomas.lumen.ui.HomeScreen
import pt.tomas.lumen.ui.HomeState
import pt.tomas.lumen.ui.SearchRequest

class MainActivity : ComponentActivity(), HomeActions {
    private var guardEnabled by mutableStateOf(false)
    private var homeTick by mutableIntStateOf(0)
    private var searchRequest by mutableStateOf<SearchRequest?>(null)
    private var updateNotice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No ecrã inicial o botão Voltar não faz nada (não há para onde voltar).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        val app = lumen
        setContent {
            val apps by app.apps.apps.collectAsStateWithLifecycle()
            val favorites by app.prefs.favorites.collectAsStateWithLifecycle()
            val hidden by app.prefs.hidden.collectAsStateWithLifecycle()
            val tv by app.tv.state.collectAsStateWithLifecycle()
            val recents by app.prefs.recents.collectAsStateWithLifecycle()
            val timerEndsAt by app.sleepTimer.endsAt.collectAsStateWithLifecycle()
            val storeItems by app.store.items.collectAsStateWithLifecycle()
            val appOrder by app.prefs.appOrder.collectAsStateWithLifecycle()
            val info by app.info.info.collectAsStateWithLifecycle()
            HomeScreen(
                HomeState(
                    apps, favorites, hidden, recents, tv, guardEnabled, timerEndsAt, storeItems, appOrder, homeTick,
                    info = info, searchRequest = searchRequest, updateNotice = updateNotice, remoteUrl = app.remote.url(),
                ),
                this,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotice = UpdateWatch.check(this)
        Guard.resume()
        guardEnabled = Guard.isEnabled(this)
        lumen.tv.refresh()
        lumen.store.checkIfStale() // atualizações da Loja: no máximo uma verificação a cada 12 h
    }

    /** No Lumen as teclas de volume vão sempre para a TV (pela rede), mesmo que o serviço não as receba. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        TvKeys.handle(this, event) || super.dispatchKeyEvent(event)

    override fun launch(app: AppEntry) {
        if (safeStart(app.launch)) lumen.prefs.recordLaunch(app.pkg)
    }

    override fun cycleSleepTimer() = lumen.sleepTimer.cycle()

    override fun storeInstall(item: StoreItem) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Ative \"Instalar apps desconhecidas\" para o Lumen nas Opções de programador", Toast.LENGTH_LONG).show()
            return
        }
        lumen.store.install(item.app)
    }

    override fun dismissUpdateNotice() {
        UpdateWatch.acknowledge(this)
        updateNotice = null
    }

    override fun storeCheck() {
        lumen.store.checkAll()
    }

    override fun toggleFavorite(pkg: String) = lumen.prefs.toggleFavorite(pkg)

    override fun moveFavorite(pkg: String, delta: Int) =
        lumen.prefs.moveFavorite(pkg, delta, lumen.apps.apps.value.map { it.pkg })

    override fun moveApp(pkg: String, delta: Int, visible: List<String>) = lumen.prefs.moveApp(pkg, delta, visible)

    override fun toggleHidden(pkg: String) = lumen.prefs.toggleHidden(pkg)

    /**
     * O `Settings.ACTION_SETTINGS` no Fire OS salta para o ecrã inicial da Amazon antes das Definições;
     * abrimos diretamente o ecrã de Definições da Amazon e só usamos o genérico como recurso.
     */
    override fun openSettings() {
        val direct = Intent("amazon.intent.action.MAINSETTINGS")
            .addCategory("android.intent.category.MAINSETTINGS")
            .setClassName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.MainSettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!safeStart(direct, quiet = true)) {
            safeStart(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), viaAmazonHome = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val search = intent.getStringExtra(EXTRA_SEARCH)
        if (search != null) {
            searchRequest = SearchRequest((searchRequest?.id ?: 0) + 1, search) // pedido do comando do iPhone
            return
        }
        homeTick++ // botão Home: volta ao topo, com o foco no primeiro favorito
    }

    override fun changeSource(source: TvSource) {
        lumen.tv.changeSource(source)
    }

    /** Escape para o ecrã da Amazon: o serviço fica em pausa 10 min ou até o Lumen voltar. */
    override fun openAmazonHome() {
        Guard.pause(minutes = 10)
        safeStart(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.HomeActivity_vNext"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun safeStart(intent: Intent, quiet: Boolean = false, viaAmazonHome: Boolean = false): Boolean = try {
        if (viaAmazonHome) Guard.pauseMillis(2_500)
        Guard.noteLaunch(intent.component?.packageName.orEmpty())
        startActivity(intent)
        true
    } catch (e: Exception) {
        if (!quiet) Toast.makeText(this, "Não foi possível abrir: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    companion object {
        /** Texto de pesquisa vindo do comando do iPhone. */
        const val EXTRA_SEARCH = "pt.tomas.lumen.SEARCH"
    }
}
