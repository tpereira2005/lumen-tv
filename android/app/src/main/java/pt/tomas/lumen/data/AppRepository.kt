package pt.tomas.lumen.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    /** Banner 16:9 da app; sem banner, a UI desenha um cartão com o ícone e o nome. */
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    val accent: Color,
)

/**
 * Lista de apps lançáveis, carregada uma vez e mantida em memória.
 * Só volta a ler o PackageManager quando uma app é instalada, removida ou atualizada,
 * para o Lumen aparecer instantaneamente ao carregar em Home.
 */
class AppRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, filter)
        refresh()
    }

    fun refresh() {
        scope.launch { _apps.value = load() }
    }

    private fun load(): List<AppEntry> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        // Primeiro as apps de TV (com banner), depois as restantes apps com ícone.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName || !canLaunch(ai)) continue
                if (!seen.add(ai.packageName)) continue
                runCatching { result += entryFor(pm, ai, category) }
                    .onFailure { Log.w(TAG, "Ignorada ${ai.packageName}: ${it.message}") }
            }
        }
        result.sortBy { it.label.lowercase() }
        Log.i(TAG, "Apps: " + result.joinToString { "${it.label}=${it.pkg}" })
        return result
    }

    /** Algumas entradas do sistema (ex.: com.amazon.whasettings) exigem permissões que só a Amazon tem. */
    private fun canLaunch(ai: ActivityInfo): Boolean {
        if (!ai.exported || !ai.enabled) return false
        val perm = ai.permission ?: return true
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun entryFor(pm: PackageManager, ai: ActivityInfo, category: String): AppEntry {
        val res = runCatching { pm.getResourcesForApplication(ai.applicationInfo) }.getOrNull()
        fun hiRes(id: Int, density: Int): Drawable? =
            if (id == 0 || res == null) null
            else runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()

        val bannerId = ai.bannerResource.takeIf { it != 0 } ?: ai.applicationInfo.banner
        val banner = (hiRes(bannerId, DisplayMetrics.DENSITY_XXHIGH) ?: ai.loadBanner(pm))?.render(400, 225)
        val iconId = ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon
        val icon = (hiRes(iconId, DisplayMetrics.DENSITY_XXXHIGH) ?: ai.loadIcon(pm)).render(192, 192)

        val source = banner ?: icon
        return AppEntry(
            pkg = ai.packageName,
            label = ai.loadLabel(pm).toString(),
            launch = Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            banner = banner?.asImageBitmap(),
            icon = icon.asImageBitmap(),
            accent = vividAverage(source),
        )
    }

    private fun Drawable.render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, w, h)
        draw(Canvas(bmp))
        return bmp
    }

    /** Cor média, puxada para tons mais vivos (banners brancos dariam um halo cinzento). */
    private fun vividAverage(bmp: Bitmap): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0), hsv)
        hsv[1] = (hsv[1] * 1.4f).coerceIn(0.25f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.85f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private companion object {
        const val TAG = "LumenApps"
    }
}
