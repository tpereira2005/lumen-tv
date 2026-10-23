package pt.tomas.lumen.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import pt.tomas.lumen.data.AppEntry

/** Fundo comum das janelas por cima do ecrã inicial. */
@Composable
private fun OverlayFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141826), Lumen.Bg))),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 36.dp)) { content() }
    }
}

@Composable
private fun OverlayHeader(kicker: String, title: String, subtitle: String) {
    Text(kicker, color = Lumen.Accent, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
    Text(title, color = Lumen.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    Text(subtitle, color = Lumen.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Pesquisa: filtra as apps instaladas e as da Loja Lumen enquanto se escreve (teclado do Fire TV ou do iPhone).
 * OK numa app instalada abre-a; numa app da Loja, abre a Loja.
 */
@Composable
fun SearchScreen(
    initial: String,
    apps: List<AppEntry>,
    storeNames: List<String>,
    onLaunch: (AppEntry) -> Unit,
    onOpenStore: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val q = query.trim().lowercase()
    val found = if (q.isEmpty()) emptyList() else apps.filter { q in it.label.lowercase() || q in it.pkg }
    val inStore = if (q.isEmpty()) emptyList() else storeNames.filter { q in it.lowercase() && apps.none { a -> a.label.equals(it, true) } }

    OverlayFrame(onDismiss) {
        OverlayHeader("PESQUISAR", "O que quer ver?", "Escreva com o comando, ou no iPhone pelo comando Lumen.")
        Spacer(Modifier.height(18.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Lumen.Text, fontSize = 22.sp),
            cursorBrush = SolidColor(Lumen.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { found.firstOrNull()?.let(onLaunch) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .background(Lumen.Surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            items(found, key = { it.pkg }) { app ->
                ResultRow(app.label, "Abrir", app.accent) { onLaunch(app) }
            }
            items(inStore, key = { "store-$it" }) { name ->
                ResultRow(name, "Na Loja Lumen", Lumen.Accent, onOpenStore)
            }
            if (q.isNotEmpty() && found.isEmpty() && inStore.isEmpty()) {
                item { Text("Nada encontrado para \"$query\".", color = Lumen.TextFaint, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, action: String, accent: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CardDefaults.shape(Lumen.TileShape),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        colors = CardDefaults.colors(containerColor = Lumen.Surface, focusedContainerColor = Lumen.SurfaceHi),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = Lumen.TileShape)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(accent, RoundedCornerShape(3.dp)))
            Text(title, color = Lumen.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp).weight(1f))
            Text(action, color = Lumen.TextDim, fontSize = 13.sp)
        }
    }
}

/** Instruções e QR code para abrir o comando no iPhone. */
@Composable
fun RemoteScreen(url: String?, onDismiss: () -> Unit) {
    val qr = remember(url) { url?.let { qrBitmap(it, 360) } }
    OverlayFrame(onDismiss) {
        OverlayHeader("COMANDO NO IPHONE", "Controle a TV com o telemóvel", "Funciona em qualquer app do Fire TV: setas, OK, volume, entradas, pesquisa e favoritos.")
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
                if (qr != null) Image(qr.asImageBitmap(), "QR code do comando", modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Step("1", "Abra a câmara do iPhone e aponte para o código.")
                Step("2", "Toque na ligação que aparece (o iPhone tem de estar no Wi-Fi de casa).")
                Step("3", "No Safari: Partilhar → \"Adicionar ao ecrã principal\". Fica como uma app.")
                Text(url ?: "Sem rede", color = Lumen.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(Lumen.Accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(n, color = Lumen.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Lumen.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp).width(440.dp))
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0A0C11.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
