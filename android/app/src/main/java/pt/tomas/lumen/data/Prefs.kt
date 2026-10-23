package pt.tomas.lumen.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ordem inicial dos favoritos (só entram as que estiverem instaladas). */
private val DEFAULT_FAVORITES = listOf(
    "com.netflix.ninja",
    "com.amazon.firetv.youtube",
    "com.vodafone.vtv.atv.pt",
    "ar.tvplayer.tv",
    "com.stremio.one",
    "com.spotify.tv.android",
)

/**
 * Apps da Amazon escondidas à partida no Lumen. Não são desinstaladas nem desativadas:
 * continuam no sistema e voltam a aparecer em "Apps ocultas" → selecionar.
 */
private val DEFAULT_HIDDEN = setOf(
    "com.amazon.shoptv.firetv.client",   // Amazon Live Shopping
    "com.amazon.minitv.android.app",     // Amazon miniTV
    "com.amazon.spiderpork",             // Amazon Luna
    "com.amazon.gamehub",                // Amazon Game Center
    "com.amazon.hedwig",                 // News
    "com.amazon.smarthomemapviewapp",    // Alexa Smart Home
    "com.amazon.tv.earlyaccess",         // Early Access
    "com.amazon.firetv.troubleshooting", // FireTV Troubleshooting
    "com.amazon.ssm",                    // Monitor do estado do sistema
    "com.amazon.ftv.screensaver",        // Protetor de ecrã
    "com.amazon.bueller.photos",         // Amazon Photos
    "com.amazon.bueller.music",          // Amazon Music
    "com.amazon.cloud9",                 // Amazon Silk
)

/** Preferências do Lumen, expostas como StateFlow para a UI reagir sem recarregar nada. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(readList("favorites") ?: DEFAULT_FAVORITES)
    val favorites: StateFlow<List<String>> = _favorites

    private val _hidden = MutableStateFlow(readList("hidden")?.toSet() ?: DEFAULT_HIDDEN)
    val hidden: StateFlow<Set<String>> = _hidden

    private val _appOrder = MutableStateFlow(readList("app_order") ?: emptyList())
    /** Ordem escolhida para a grelha "Apps" (as apps que não constam ficam no fim, por ordem alfabética). */
    val appOrder: StateFlow<List<String>> = _appOrder

    /** Move uma app da grelha `delta` posições; `visible` é a ordem atual da grelha no ecrã. */
    fun moveApp(pkg: String, delta: Int, visible: List<String>) {
        val list = visible.toMutableList()
        val from = list.indexOf(pkg)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return
        list.add(to, list.removeAt(from))
        _appOrder.value = list
        sp.edit().putString("app_order", list.joinToString(",")).apply()
    }

    private val _recents = MutableStateFlow(readList("recents") ?: emptyList())
    /** Apps abertas a partir do Lumen, da mais recente para a mais antiga. */
    val recents: StateFlow<List<String>> = _recents

    fun recordLaunch(pkg: String) {
        val list = (listOf(pkg) + _recents.value.filter { it != pkg }).take(MAX_RECENTS)
        _recents.value = list
        sp.edit().putString("recents", list.joinToString(",")).apply()
    }

    /** Desligar a TV (pela rede) quando o Fire TV adormece. */
    var syncTvPower: Boolean
        get() = sp.getBoolean("sync_tv_power", true)
        set(v) = sp.edit().putBoolean("sync_tv_power", v).apply()

    var tvHost: String
        get() = sp.getString("tv_host", "192.168.1.82")!!
        set(v) = sp.edit().putString("tv_host", v).apply()

    fun toggleFavorite(pkg: String) {
        val list = _favorites.value.toMutableList()
        if (!list.remove(pkg)) list += pkg
        setFavorites(list)
    }

    /** Move um favorito `delta` posições (negativo = para a esquerda). */
    fun moveFavorite(pkg: String, delta: Int, installed: Collection<String>) {
        // Os favoritos de apps não instaladas ficam guardados mas não contam para a posição visível.
        val visible = _favorites.value.filter { it in installed }.toMutableList()
        val from = visible.indexOf(pkg)
        val to = (from + delta).coerceIn(0, visible.lastIndex)
        if (from < 0 || from == to) return
        visible.add(to, visible.removeAt(from))
        setFavorites(visible + _favorites.value.filter { it !in installed })
    }

    fun toggleHidden(pkg: String) {
        val set = _hidden.value.toMutableSet()
        if (!set.remove(pkg)) set += pkg
        _hidden.value = set
        sp.edit().putString("hidden", set.joinToString(",")).apply()
    }

    private fun setFavorites(list: List<String>) {
        _favorites.value = list
        sp.edit().putString("favorites", list.joinToString(",")).apply()
    }

    private companion object {
        const val MAX_RECENTS = 8
    }

    private fun readList(key: String): List<String>? =
        sp.getString(key, null)?.split(',')?.filter { it.isNotBlank() }
}
