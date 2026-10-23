package pt.tomas.lumen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import pt.tomas.lumen.data.AppEntry

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Lumen.PT), color = Lumen.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp, modifier = modifier.padding(start = Lumen.SidePad, top = 14.dp, bottom = 0.dp),
    )
}

/** Cartão 16:9 de uma app. Sem banner, desenha um cartão próprio (ícone + nome) com a cor da app. */
@Composable
fun AppCard(
    app: AppEntry,
    width: Dp,
    modifier: Modifier = Modifier,
    badge: String? = null,
    moving: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .height(width * 9 / 16)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = CardDefaults.shape(Lumen.CardShape),
        scale = CardDefaults.scale(focusedScale = if (moving) 1.14f else 1.09f),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape = Lumen.CardShape),
            focusedBorder = Border(
                BorderStroke(if (moving) 3.dp else 2.dp, if (moving) Lumen.Accent else Color.White),
                shape = Lumen.CardShape,
            ),
        ),
        colors = CardDefaults.colors(containerColor = Lumen.Surface),
    ) {
        Box(Modifier.fillMaxSize()) {
            val branded = BRANDED_TILES[app.pkg]
            when {
                app.banner != null -> Image(app.banner, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                branded != null -> BrandedTile(app, branded, width)
                else -> GeneratedTile(app, width)
            }
            if (badge != null) {
                Text(
                    badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (moving) {
                Text(
                    "‹  mover  ›", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(6.dp)
                        .background(Lumen.Accent.copy(alpha = 0.9f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Apps sem banner de TV para as quais desenhamos um banner completo (cor da marca + ícone real + nome). */
private data class Brand(val title: String, val top: Color, val bottom: Color, val line: Color)

private val BRANDED_TILES = mapOf(
    "com.amazon.venezia" to Brand("Appstore", Color(0xFF2B3A4E), Color(0xFF131A22), Color(0xFFFF9900)),
)

@Composable
private fun BrandedTile(app: AppEntry, brand: Brand, width: Dp) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(brand.top, brand.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                app.icon, null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(width * 0.24f).clip(RoundedCornerShape(width * 0.05f)),
            )
            Column(Modifier.padding(start = width * 0.06f)) {
                Text(
                    brand.title, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = if (width > 180.dp) 22.sp else 18.sp,
                )
                Box(Modifier.padding(top = 3.dp).width(width * 0.18f).height(3.dp).background(brand.line, CircleShape))
            }
        }
    }
}

/**
 * Cartão para apps sem banner de TV: o ícone grande, recortado com cantos suaves, sobre um gradiente com a
 * cor da app. Sem texto — o nome aparece no topo do ecrã quando o cartão está em foco.
 */
@Composable
private fun GeneratedTile(app: AppEntry, width: Dp) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(Lumen.Surface, app.accent, 0.60f), lerp(Lumen.Bg, app.accent, 0.22f)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            app.icon, app.label, contentScale = ContentScale.Fit,
            modifier = Modifier.size(width * 0.30f).clip(RoundedCornerShape(width * 0.06f)),
        )
    }
}

/** Mosaico de ação (Definições, entradas HDMI, estado...). */
@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    selected: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(150.dp)
            .height(66.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            },
        shape = CardDefaults.shape(Lumen.TileShape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(
            border = Border(
                BorderStroke(1.dp, if (selected) Lumen.Accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f)),
                shape = Lumen.TileShape,
            ),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = Lumen.TileShape),
        ),
        colors = CardDefaults.colors(containerColor = Lumen.Surface, focusedContainerColor = Lumen.SurfaceHi),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(title, color = Lumen.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                if (dot != null) Box(Modifier.size(7.dp).background(dot, CircleShape))
                Text(
                    subtitle, color = if (focused) Lumen.TextDim else Lumen.TextFaint, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.padding(start = if (dot != null) 6.dp else 0.dp),
                )
            }
        }
    }
}
