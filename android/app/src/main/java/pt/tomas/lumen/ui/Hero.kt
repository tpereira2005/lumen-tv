package pt.tomas.lumen.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import pt.tomas.lumen.data.Info
import pt.tomas.lumen.data.Match
import pt.tomas.lumen.tv.TvState
import java.text.SimpleDateFormat
import java.util.Date

/** O que o topo mostra: a app (ou mosaico) em foco e a cor que pinta o fundo. */
data class HeroInfo(
    val title: String,
    val subtitle: String?,
    val accent: Color = Lumen.Accent,
)

/**
 * Fundo "aurora": dois brilhos suaves com a cor da app em foco (e um tom vizinho), sobre fundo escuro.
 *
 * Desempenho: gradientes de ecrã inteiro desenhados pela GPU custavam ~60 ms por frame neste Stick.
 * Por isso a aurora é pintada pela CPU numa imagem pequena e opaca (192×108) e ampliada para o ecrã —
 * para a GPU é só uma textura, como um papel de parede. Durante a transição de cor gera-se uma imagem
 * por frame (custo desprezável a esta resolução).
 */
@Composable
fun AuroraBackground(infoState: State<HeroInfo>, modifier: Modifier = Modifier) {
    val main by animateColorAsState(infoState.value.accent, tween(700), label = "aurora-main")
    val second by animateColorAsState(shiftHue(infoState.value.accent, 38f), tween(900), label = "aurora-second")
    val bitmap = remember(main, second) { auroraBitmap(main, second) }
    Image(
        bitmap, null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
        modifier = modifier.fillMaxSize(),
    )
}

private const val AURORA_W = 192
private const val AURORA_H = 108

private fun auroraBitmap(main: Color, second: Color): ImageBitmap {
    val bmp = Bitmap.createBitmap(AURORA_W, AURORA_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Lumen.Bg.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = RadialGradient(
        AURORA_W * 0.82f, -AURORA_H * 0.10f, AURORA_W * 0.62f,
        intArrayOf(main.copy(alpha = 0.42f).toArgb(), main.copy(alpha = 0.10f).toArgb(), 0),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, AURORA_W.toFloat(), AURORA_H.toFloat(), paint)
    paint.shader = RadialGradient(
        AURORA_W * 0.05f, AURORA_H * 1.05f, AURORA_W * 0.55f,
        intArrayOf(second.copy(alpha = 0.20f).toArgb(), 0), null, Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, AURORA_W.toFloat(), AURORA_H.toFloat(), paint)
    return bmp.asImageBitmap()
}

/** Topo compacto: título da app em foco à esquerda, relógio à direita, indicador de volume momentâneo. */
@Composable
fun TopBar(infoState: State<HeroInfo>, tv: TvState, extra: Info, modifier: Modifier = Modifier) {
    val info = infoState.value
    // Altura fixa: o indicador de volume flutua por baixo do relógio sem mudar o tamanho do topo,
    // por isso o título e as linhas nunca saltam quando ele aparece ou desaparece.
    Box(modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(start = Lumen.SidePad, end = Lumen.SidePad, top = 26.dp, bottom = 4.dp)) {
        Column(Modifier.align(Alignment.BottomStart).padding(end = 260.dp)) {
            Text(
                (info.subtitle ?: " ").uppercase(Lumen.PT), color = lighten(info.accent), fontSize = 11.sp,
                letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                info.title, color = Lumen.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.Top) {
            WeatherBlock(extra)
            Column(horizontalAlignment = Alignment.End) {
                Clock()
                MatchLine(extra)
            }
        }
        VolumePill(tv, Modifier.align(Alignment.TopEnd).offset(y = 92.dp))
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val hm = SimpleDateFormat("HH:mm", Lumen.PT).format(now).split(':')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(hm[0], color = Lumen.Text, fontSize = 38.sp, fontWeight = FontWeight.Light)
        // Os dois pontos na cor de destaque: um pequeno toque de identidade.
        Text(":", color = Lumen.Accent, fontSize = 38.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 1.dp))
        Text(hm[1], color = Lumen.Text, fontSize = 38.sp, fontWeight = FontWeight.Light)
    }
    Text(
        SimpleDateFormat("EEEE · d MMM", Lumen.PT).format(now).replace(".", "").uppercase(Lumen.PT),
        color = Lumen.TextDim, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium,
    )
}

/** Aparece 2,5 s quando o volume ou o silêncio da TV mudam (pela rede ou pelo comando da TV). */
@Composable
private fun VolumePill(tv: TvState, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    // Último estado conhecido: a primeira leitura (ao abrir o Lumen) não é uma mudança e não mostra nada.
    var last by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(tv.volume, tv.muted) {
        val now = (tv.volume ?: return@LaunchedEffect) to tv.muted
        val previous = last
        last = now
        if (previous == null || previous == now) return@LaunchedEffect
        visible = true
        delay(2_500)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(400)), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.background(Color.White.copy(alpha = 0.10f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(if (tv.muted) "Sem som" else "Volume ${tv.volume}", color = Lumen.Text, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(90.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                val fraction = if (tv.muted) 0f else ((tv.volume ?: 0) / 100f).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Lumen.Accent, CircleShape))
            }
        }
    }
}

private fun shiftHue(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Versão clara da cor de destaque, legível como texto sobre o fundo escuro. */
private fun lighten(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtMost(0.45f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private val TOP_BAR_HEIGHT = 112.dp

/** Tempo no Porto à esquerda do relógio: símbolo, temperatura e mínima/máxima. */
@Composable
private fun WeatherBlock(info: Info) {
    val w = info.weather ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 22.dp, top = 6.dp)) {
        Text(w.icon, fontSize = 26.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text("${w.tempC}°", color = Lumen.Text, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text("${info.city.uppercase(Lumen.PT)} · ${w.minC}°/${w.maxC}°", color = Lumen.TextFaint, fontSize = 10.sp, letterSpacing = 1.2.sp)
        }
        Box(Modifier.padding(start = 22.dp).width(1.dp).height(40.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

/** Próximo jogo (ou em direto, ou o último resultado se foi há pouco) da equipa. */
@Composable
private fun MatchLine(info: Info) {
    val now = System.currentTimeMillis()
    val next = info.next
    val last = info.last
    val text = when {
        next != null && next.live -> "🔴 ${teams(info.team, next, next.score)} · ao vivo"
        last != null && now - last.time.time < 36 * 3_600_000L -> "⚽ ${teams(info.team, last, last.score)} · final"
        next != null -> "⚽ ${teams(info.team, next, null)} · ${whenText(next.time)}"
        else -> return
    }
    Text(text, color = Lumen.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
}

private fun teams(team: String, m: Match, score: String?): String {
    val sep = score ?: "×"
    return if (m.home) "$team $sep ${m.opponent}" else "${m.opponent} ${score?.split('–')?.reversed()?.joinToString("–") ?: sep} $team"
}

private fun whenText(d: Date): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.time = d
    val hm = SimpleDateFormat("HH:mm", Lumen.PT).format(d)
    return when (cal.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> "hoje $hm"
        1 -> "amanhã $hm"
        else -> SimpleDateFormat("EEE d MMM", Lumen.PT).format(d).replace(".", "") + " $hm"
    }
}
