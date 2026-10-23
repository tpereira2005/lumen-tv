package pt.tomas.lumen.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Weather(val tempC: Int, val minC: Int, val maxC: Int, val icon: String, val label: String)

data class Match(
    val opponent: String,
    val home: Boolean,
    val time: Date,
    val competition: String,
    /** "2–0" quando já terminou; null se ainda não se jogou. */
    val score: String? = null,
    val live: Boolean = false,
    /** Durante o jogo: "67'", "45'+2'" ou "Intervalo". */
    val minute: String? = null,
    /** Escudos (≈125 px), ou null se não foi possível descarregar. */
    val teamCrest: Bitmap? = null,
    val opponentCrest: Bitmap? = null,
)

data class Info(val city: String, val weather: Weather? = null, val team: String, val next: Match? = null, val last: Match? = null)

/**
 * Meteorologia (Open-Meteo, sem conta) e jogos da equipa (ESPN, Liga Portugal), atualizados periodicamente.
 * Só leituras públicas; não envia dados do utilizador.
 */
class InfoRepository(
    private val city: String = "Porto",
    private val lat: Double = 41.15,
    private val lon: Double = -8.61,
    private val team: String = "Benfica",
    private val espnTeamId: Int = 1929,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _info = MutableStateFlow(Info(city = city, team = team))
    val info: StateFlow<Info> = _info

    init {
        scope.launch {
            while (isActive) {
                runCatching { _info.value = _info.value.copy(weather = fetchWeather()) }.onFailure { Log.w(TAG, "Tempo: $it") }
                delay(30 * 60_000L)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching {
                    val (next, last) = fetchMatches()
                    _info.value = _info.value.copy(next = next, last = last)
                }.onFailure { Log.w(TAG, "Futebol: $it") }
                // Em dia de jogo atualiza mais vezes; durante o jogo, a cada minuto (placard em direto).
                val next = _info.value.next
                val soon = next?.let { kotlin.math.abs(it.time.time - System.currentTimeMillis()) < 3 * 3_600_000L } == true
                delay(if (next?.live == true) 60_000L else if (soon) 2 * 60_000L else 2 * 3_600_000L)
            }
        }
    }

    private fun fetchWeather(): Weather {
        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=Europe%2FLisbon&forecast_days=1"
            )
        )
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val (icon, label) = describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
        return Weather(
            tempC = Math.round(cur.getDouble("temperature_2m")).toInt(),
            minC = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            maxC = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            icon = icon, label = label,
        )
    }

    /** Próximo jogo (ou o que está a decorrer) e último resultado. */
    private fun fetchMatches(): Pair<Match?, Match?> {
        val base = "https://site.api.espn.com/apis/site/v2/sports/soccer/por.1/teams/$espnTeamId/schedule"
        val upcoming = JSONObject(get("$base?fixture=true")).getJSONArray("events")
        val past = JSONObject(get(base)).getJSONArray("events")
        val next = (0 until upcoming.length()).map { parse(upcoming.getJSONObject(it)) }
            .filter { it.score == null || it.live }.minByOrNull { it.time }
        val last = (0 until past.length()).map { parse(past.getJSONObject(it)) }
            .filter { it.score != null && !it.live }.maxByOrNull { it.time }
        return next to last
    }

    private fun parse(e: JSONObject): Match {
        val comp = e.getJSONArray("competitions").getJSONObject(0)
        val teams = comp.getJSONArray("competitors")
        val us = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it.getJSONObject("team").getString("id") == espnTeamId.toString() }
        val them = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it !== us }
        val status = comp.getJSONObject("status")
        val type = status.getJSONObject("type")
        val state = type.getString("state") // pre / in / post
        fun score(c: JSONObject) = c.opt("score").let { if (it is JSONObject) it.optString("displayValue") else it?.toString() }.orEmpty()
        return Match(
            opponent = them.getJSONObject("team").optString("shortDisplayName", them.getJSONObject("team").getString("displayName")),
            home = us.optString("homeAway") == "home",
            time = iso.parse(e.getString("date"))!!,
            competition = e.optJSONObject("league")?.optString("abbreviation").orEmpty().ifEmpty { "Liga" },
            score = if (state == "pre") null else "${score(us)}–${score(them)}",
            live = state == "in",
            minute = when {
                state != "in" -> null
                type.optString("name") == "STATUS_HALFTIME" -> "Intervalo"
                else -> status.optString("displayClock").ifEmpty { null }
            },
            teamCrest = crest(us.getJSONObject("team")),
            opponentCrest = crest(them.getJSONObject("team")),
        )
    }

    /** Escudos guardados em memória por endereço: descarregam-se uma vez por arranque. */
    private val crests = HashMap<String, Bitmap>()

    private fun crest(team: JSONObject): Bitmap? {
        val url = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifEmpty { null }
            ?: team.optString("logo").ifEmpty { return null }
        crests[url]?.let { return it }
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            // Os originais têm 500 px; 1/4 chega para o topo e poupa memória.
            val bmp = try { c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
                finally { c.disconnect() }
            bmp?.also { crests[url] = it }
        }.onFailure { Log.w(TAG, "Escudo: $it") }.getOrNull()
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Códigos WMO do Open-Meteo → símbolo e descrição em português. */
    private fun describe(code: Int, day: Boolean): Pair<String, String> = when (code) {
        0 -> (if (day) "☀️" else "🌙") to "Céu limpo"
        1, 2 -> (if (day) "🌤️" else "🌙") to "Pouco nublado"
        3 -> "☁️" to "Nublado"
        45, 48 -> "🌫️" to "Nevoeiro"
        in 51..57 -> "🌦️" to "Chuvisco"
        in 61..67, in 80..82 -> "🌧️" to "Chuva"
        in 71..77, 85, 86 -> "🌨️" to "Neve"
        in 95..99 -> "⛈️" to "Trovoada"
        else -> "🌡️" to ""
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private companion object {
        const val TAG = "LumenInfo"
    }
}
