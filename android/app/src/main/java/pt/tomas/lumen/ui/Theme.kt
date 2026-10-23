package pt.tomas.lumen.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Paleta e medidas do Lumen. Ecrã do Fire TV = 960×540 dp (1080p a densidade 2×). */
object Lumen {
    val Bg = Color(0xFF0A0C11)
    val Surface = Color(0xFF161A23)
    val SurfaceHi = Color(0xFF222838)
    val Text = Color(0xFFF4F6FA)
    val TextDim = Color(0xA6F4F6FA)
    val TextFaint = Color(0x66F4F6FA)
    val Accent = Color(0xFF8AA4FF)
    val Ok = Color(0xFF4CD787)
    val Warn = Color(0xFFFF7A6B)

    val CardShape = RoundedCornerShape(14.dp)
    val TileShape = RoundedCornerShape(12.dp)
    val SidePad = 44.dp

    val PT: Locale = Locale("pt", "PT")
}

/** Subtítulo do cartão em destaque, por tipo de app (só as conhecidas). */
fun kindOf(pkg: String): String? = when (pkg) {
    "com.netflix.ninja", "com.disney.disneyplus", "com.hbo.hbonow", "com.apple.atve.amazon.appletv",
    "com.amazon.firebat" -> "Filmes e séries"
    "com.amazon.firetv.youtube", "tv.twitch.android.viewer" -> "Vídeos"
    "com.vodafone.vtv.atv.pt", "ar.tvplayer.tv" -> "Televisão em direto"
    "com.stremio.one" -> "Filmes, séries e add-ons"
    "com.spotify.tv.android", "com.amazon.bueller.music" -> "Música"
    "com.dazn", "com.uefa.uefatv", "com.fifa.plus.android" -> "Desporto"
    "com.esaba.downloader" -> "Ferramentas"
    else -> null
}
