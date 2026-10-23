package pt.tomas.lumen

import android.app.Application
import android.content.Context
import pt.tomas.lumen.data.AppRepository
import pt.tomas.lumen.data.InfoRepository
import pt.tomas.lumen.data.Prefs
import pt.tomas.lumen.remote.RemoteServer
import pt.tomas.lumen.store.Store
import pt.tomas.lumen.tv.TvRemote

/**
 * Objetos partilhados pelo launcher e pelo serviço do botão Home (correm no mesmo processo,
 * que o serviço de acessibilidade mantém vivo — por isso a lista de apps fica sempre em memória).
 */
class LumenApp : Application() {
    val prefs by lazy { Prefs(this) }
    val apps by lazy { AppRepository(this) }
    val tv by lazy { TvRemote(this) { prefs.tvHost } }
    val sleepTimer by lazy { SleepTimer(tv) }
    val store by lazy { Store(this) }
    val info by lazy { InfoRepository(city = "Porto", lat = 41.15, lon = -8.61, team = "Benfica", espnTeamId = 1929) }
    val remote by lazy { RemoteServer(this) }
}

val Context.lumen: LumenApp get() = applicationContext as LumenApp
