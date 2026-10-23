package pt.tomas.lumen.ambient

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Coleção de fotografias do ecrã ambiente: as "imagens do dia" do Bing (paisagens, 1920×1080),
 * guardadas na cache do Lumen. Atualiza no máximo uma vez por dia e mantém as [MAX] mais recentes.
 * Só descarrega imagens; não envia dados do utilizador.
 */
object PhotoCache {
    data class Photo(val file: File, val caption: String)

    private const val TAG = "LumenPhotos"
    private const val MAX = 16
    private const val API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=pt-PT"

    private fun dir(context: Context) = File(context.cacheDir, "ambient").apply { mkdirs() }

    /** Fotos já guardadas, mais recentes primeiro. */
    fun cached(context: Context): List<Photo> =
        dir(context).listFiles { f -> f.extension == "jpg" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { Photo(it, File(it.path.removeSuffix(".jpg") + ".txt").takeIf(File::exists)?.readText().orEmpty()) }

    /** Descarrega as fotos novas (se a última atualização tiver mais de 20 h) e devolve a coleção. */
    fun refresh(context: Context): List<Photo> {
        val dir = dir(context)
        val stamp = File(dir, ".updated")
        if (stamp.exists() && System.currentTimeMillis() - stamp.lastModified() < 20 * 3_600_000L) return cached(context)
        runCatching {
            val images = JSONObject(get(API).decodeToString()).getJSONArray("images")
            for (i in 0 until images.length()) {
                val img = images.getJSONObject(i)
                val base = img.getString("urlbase") // ex.: /th?id=OHR.Nome_PT-PT123
                val name = base.substringAfter("id=").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val jpg = File(dir, "$name.jpg")
                if (!jpg.exists()) {
                    jpg.writeBytes(get("https://www.bing.com${base}_1920x1080.jpg"))
                    File(dir, "$name.txt").writeText(img.optString("copyright"))
                }
            }
            stamp.writeText("")
            stamp.setLastModified(System.currentTimeMillis())
            // Limpa as mais antigas.
            dir.listFiles { f -> f.extension == "jpg" }.orEmpty().sortedByDescending { it.lastModified() }.drop(MAX).forEach {
                it.delete()
                File(it.path.removeSuffix(".jpg") + ".txt").delete()
            }
        }.onFailure { Log.w(TAG, "Sem fotos novas: ${it.message}") }
        return cached(context)
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        return try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
