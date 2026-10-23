package pt.tomas.lumen

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Vigilância de atualizações do Fire OS: a Amazon atualiza sozinha e uma atualização pode reativar serviços
 * que desligámos (tools/aliviar.ps1) ou mexer no botão Home. Ao abrir, o Lumen compara a versão do sistema
 * com a última que viu e, se mudou, diz o que encontrou em vez de falhar em silêncio.
 */
object UpdateWatch {
    /** Mesma lista que tools/aliviar.ps1 desativa. */
    private val RELIEVED = listOf(
        "com.amazon.device.telemetry.emitter", "com.amazon.dp.logger", "com.amazon.neo.minerva",
        "com.amazon.neo.minerva.logcat", "com.amazon.perfcollection", "com.amazon.wirelessmetrics.service",
        "com.amazon.connectivitydiag", "com.amazon.tv.developer.dataservice", "com.amazon.tv.acr", "com.amazon.ceviche",
        "com.amazon.logan", "com.amazon.hybridadidservice", "com.amazon.sneakpeek", "com.amazon.kso.blackbird",
        "com.amazon.shoptv.client", "com.amazon.shoptv.firetv.client", "com.amazon.minitv.android.app",
        "com.amazon.tv.ftvambient", "com.amazon.tv.earlyaccess", "com.amazon.tv.releasenotes",
        "com.amazon.sharingservice.android.client.proxy", "com.amazon.ods.kindleconnect", "com.amazon.tv.ottssocompanionapp",
        "com.android.printservice.recommendation", "com.android.protips", "com.amazon.storm.lightning.tutorial",
        "com.amazon.tmm.tutorial", "com.amazon.tv.website_launcher", "com.amazon.gamehub", "com.amazon.tahoe",
        "com.amazon.hedwig", "com.amazon.spiderpork", "com.amazon.bueller.music", "com.amazon.bueller.photos",
    )

    private fun build() = "${Build.DISPLAY} (${Build.VERSION.INCREMENTAL})"

    /** Devolve um aviso se o Fire OS mudou desde a última vez (e ainda não foi lido), senão null. */
    fun check(context: Context): String? {
        val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        val now = build()
        val seen = sp.getString("fireos_seen", null)
        if (seen == null) {
            sp.edit().putString("fireos_seen", now).apply()
            return null
        }
        if (seen == now) return null
        val home = if (Guard.isEnabled(context)) "botão Home ok" else "botão Home desligado: correr lumen.ps1 -Acao ativar"
        return "${Build.DISPLAY} · $home${reenabledNote(context)}"
    }

    /** Se o Stick foi aliviado (marca posta por aliviar.ps1), quantos desses serviços voltaram a estar ativos. */
    private fun reenabledNote(context: Context): String {
        if (Settings.Global.getString(context.contentResolver, "lumen_aliviado") != "1") return ""
        val pm = context.packageManager
        val back = RELIEVED.count { pkg -> runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false) }
        return if (back > 0) " · $back serviços da Amazon voltaram: correr aliviar.ps1" else " · serviços aliviados ok"
    }

    fun acknowledge(context: Context) {
        context.getSharedPreferences("lumen", Context.MODE_PRIVATE).edit().putString("fireos_seen", build()).apply()
    }
}
