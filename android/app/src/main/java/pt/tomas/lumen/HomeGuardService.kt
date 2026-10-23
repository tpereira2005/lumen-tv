package pt.tomas.lumen

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
private const val TAG = "LumenGuard"

/** Estado partilhado entre o launcher e o serviço (mesmo processo). */
object Guard {
    /** Enquanto `uptimeMillis() < pausedUntil`, o ecrã da Amazon não é substituído. */
    @Volatile var pausedUntil = 0L

    fun pause(minutes: Int) = pauseMillis(minutes * 60_000L)

    /**
     * Alguns atalhos da Amazon (ex.: Definições) passam primeiro pelo ecrã inicial da Amazon.
     * Ao abrir algo a partir do Lumen damos essa margem para não "roubarmos" o ecrã a meio.
     */
    fun pauseMillis(ms: Long) {
        pausedUntil = maxOf(pausedUntil, SystemClock.uptimeMillis() + ms)
    }

    fun resume() {
        pausedUntil = 0L
        amazonSession = false
    }

    /** Momento em que o Lumen abriu a última app (uptimeMillis). */
    @Volatile var lastLumenLaunchAt = 0L

    /**
     * Algumas "apps" da Amazon (ex.: Appstore) já não são apps à parte no Fire OS 8: abrem uma página
     * dentro do ecrã inicial da Amazon. Se esse ecrã aparece logo depois de o Lumen abrir uma app,
     * estamos numa dessas páginas: deixamos o utilizador usá-la até carregar outra vez em Home.
     */
    @Volatile var amazonSession = false

    /** Só apps da Amazon podem abrir dentro do ecrã da Amazon; nas outras, um Home rápido continua a ser Home. */
    fun noteLaunch(pkg: String) {
        lastLumenLaunchAt = if (pkg.startsWith("com.amazon.")) SystemClock.uptimeMillis() else 0L
    }

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }
}

/**
 * 1. Botão Home: o Fire OS 8 não deixa trocar a app HOME (o `set-home-activity` é ignorado), por isso
 *    reagimos ao ecrã inicial da Amazon a ficar em primeiro plano e trazemos o Lumen para a frente.
 *    Os serviços de acessibilidade estão isentos das restrições de abrir atividades em segundo plano.
 * 2. Energia: quando o Fire TV adormece, desliga a TV pela rede (o Fire volta a ligá-la por CEC ao acordar).
 * 3. Volume: se o Fire OS entregar as teclas de volume a este serviço, reencaminha-as para a TV.
 */
class HomeGuardService : AccessibilityService() {
    private var lastLaunch = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF && lumen.prefs.syncTvPower) {
                Log.i(TAG, "Fire TV a adormecer → desligar TV")
                lumen.tv.powerOffIfOn()
            }
        }
    }

    companion object {
        private const val SESSION_MAX_MS = 10 * 60_000L
        @Volatile private var instance: HomeGuardService? = null

        /** Pede ao Fire TV para adormecer (o recetor de ecrã desligado trata depois da TV). */
        fun sleepFireTv() {
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        lumen.apps // pré-carrega a lista de apps para o primeiro Home ser instantâneo
        lumen.remote.start() // comando do iPhone sempre disponível
        lumen.info // meteorologia e futebol já prontos quando o Lumen abrir
        // Ao (re)arrancar o serviço — reinício do Fire TV, atualização do Lumen — o ecrã da Amazon pode já estar
        // aberto e não volta a gerar sinal; perguntamos ao sistema qual foi a última atividade e corrigimos já.
        if (amazonHomeIsOnScreen()) {
            Log.i(TAG, "serviço ligado com o ecrã Amazon à vista → Lumen")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Última atividade retomada (estatísticas de uso; permissão dada por tools/lumen.ps1). */
    private fun amazonHomeIsOnScreen(): Boolean = runCatching {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 6 * 3_600_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = "${e.packageName}/${e.className}"
        }
        last?.startsWith("$AMAZON_LAUNCHER/") == true && last.contains("HomeActivity")
    }.getOrDefault(false)

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    /** Última atividade do launcher da Amazon vista em primeiro plano (null = desconhecida). */
    private var lastAmazonActivity: String? = null
    private var sessionStart = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != AMAZON_LAUNCHER) return
        val cls = event.className?.toString().orEmpty()
        val isHome = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Outras atividades do launcher da Amazon (Definições, perfis, pesquisa...) ficam; só o ecrã inicial é trocado.
                if (cls.startsWith(AMAZON_LAUNCHER)) lastAmazonActivity = cls
                !cls.startsWith(AMAZON_LAUNCHER) || cls.contains("HomeActivity")
            }
            // Se o ecrã da Amazon já estava visível quando o serviço arrancou, carregar em Home não gera mudança
            // de janela; o primeiro movimento do foco nesse ecrã serve de sinal.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> lastAmazonActivity?.contains("HomeActivity") ?: true
            else -> false
        }
        if (!isHome) return

        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - Guard.lastLumenLaunchAt < 3_000) {
                if (!Guard.amazonSession) {
                    Log.i(TAG, "app aberta pelo Lumen dentro do ecrã Amazon — fica")
                    Toast.makeText(this, "Para voltar ao Lumen, use o botão Voltar", Toast.LENGTH_LONG).show()
                }
                Guard.amazonSession = true
                sessionStart = now
                return
            }
            // Numa página da Amazon, um novo ecrã inicial (depois da transição) é o botão Home.
            if (Guard.amazonSession && now - sessionStart < 1_500) return
            Guard.amazonSession = false
        } else if (Guard.amazonSession) {
            // A navegar dentro da página da Amazon (Appstore...). O Home aí dentro é tratado pela própria Amazon
            // e não gera nenhum sinal (testado: eventos, foco, estatísticas de uso), por isso a sessão expira.
            if (now - sessionStart < SESSION_MAX_MS) return
            Guard.amazonSession = false
        }
        if (now < Guard.pausedUntil || now - lastLaunch < 400) return
        lastLaunch = now
        Log.i(TAG, "ecrã Amazon ($cls) → Lumen")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "tecla ${KeyEvent.keyCodeToString(event.keyCode)}")
        return TvKeys.handle(this, event)
    }

    override fun onInterrupt() = Unit
}

/** Volume/silêncio do comando → TV pela rede. Partilhado pelo serviço e pela atividade do Lumen. */
object TvKeys {
    fun handle(context: Context, event: KeyEvent): Boolean {
        val tv = context.lumen.tv
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { { tv.volumeStep(+1) } }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { { tv.volumeStep(-1) } }
            KeyEvent.KEYCODE_VOLUME_MUTE -> { { tv.toggleMute() } }
            else -> return false
        }
        // Sem ligação à TV deixamos a tecla seguir o caminho normal, para nunca "engolir" o volume.
        if (!tv.state.value.connected) {
            tv.refresh()
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) action()
        return true
    }
}
