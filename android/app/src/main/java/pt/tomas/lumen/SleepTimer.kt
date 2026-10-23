package pt.tomas.lumen

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.tomas.lumen.tv.TvRemote

/**
 * Temporizador para desligar: no fim desliga a TV pela rede e pede ao Fire TV para adormecer.
 * Vive no processo do Lumen, que o serviço de acessibilidade mantém ativo.
 */
class SleepTimer(private val tv: TvRemote) {
    private val handler = Handler(Looper.getMainLooper())
    private val _endsAt = MutableStateFlow<Long?>(null)
    /** Hora (System.currentTimeMillis) a que desliga, ou null se estiver desligado. */
    val endsAt: StateFlow<Long?> = _endsAt

    private val fire = Runnable {
        Log.i("LumenTimer", "Temporizador terminou → desligar")
        _endsAt.value = null
        tv.powerOffIfOn()
        HomeGuardService.sleepFireTv()
    }

    /** Avança pelas opções: desligado → 30 → 60 → 90 → 120 min → desligado. */
    fun cycle() {
        val now = System.currentTimeMillis()
        val remaining = _endsAt.value?.let { (it - now) / 60_000L } ?: -1
        val next = STEPS.firstOrNull { it > remaining + 1 }
        set(next)
    }

    fun set(minutes: Long?) {
        handler.removeCallbacks(fire)
        if (minutes == null) {
            _endsAt.value = null
            return
        }
        _endsAt.value = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(fire, minutes * 60_000L)
    }

    private companion object {
        val STEPS = listOf(30L, 60L, 90L, 120L)
    }
}
