package pt.tomas.lumen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import pt.tomas.lumen.data.AppEntry
import pt.tomas.lumen.store.StoreItem
import pt.tomas.lumen.store.StoreItem.Status

/** Loja Lumen: apps verificadas (origem oficial + assinatura fixada), instalar / atualizar / abrir. */
@Composable
fun StoreScreen(
    items: List<StoreItem>,
    installedApps: Map<String, AppEntry>,
    onInstall: (StoreItem) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onDismiss)
    val updates = items.count { it.updateAvailable }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF141826), Lumen.Bg))),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 36.dp)) {
            Text("LOJA LUMEN", color = Lumen.Accent, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Apps verificadas", color = Lumen.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Só da origem oficial e com a assinatura do autor confirmada antes de instalar.",
                        color = Lumen.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when (updates) {
                            0 -> "Tudo atualizado"
                            1 -> "1 atualização"
                            else -> "$updates atualizações"
                        },
                        color = if (updates > 0) Lumen.Accent else Lumen.TextFaint, fontSize = 13.sp,
                    )
                    StoreAction("Procurar atualizações", onCheck)
                }
            }
            Spacer(Modifier.height(22.dp))
            // Grelha de 4 por linha; a lista desloca-se quando o foco desce para a linha seguinte.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 30.dp),
            ) {
                items(items.chunked(4).withIndex().toList(), key = { it.index }) { (row, chunk) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        chunk.forEachIndexed { col, item ->
                            StoreCard(
                                item = item,
                                installed = installedApps[item.app.pkg],
                                modifier = if (row == 0 && col == 0) Modifier.focusRequester(first) else Modifier,
                                onClick = {
                                    val entry = installedApps[item.app.pkg]
                                    if (entry != null && !item.updateAvailable && item.status !is Status.Error) onOpen(entry)
                                    else if (item.status is Status.Idle || item.status is Status.Error) onInstall(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Cartão da loja: banner oficial da app a ocupar o cartão, e por baixo o nome, a descrição e o estado. */
@Composable
private fun StoreCard(item: StoreItem, installed: AppEntry?, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    // Banner da app instalada; se não estiver instalada, o banner oficial guardado no Lumen (assets/store).
    val banner = installed?.banner ?: remember(item.app.pkg) {
        runCatching { context.assets.open("store/${item.app.pkg}.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
    Column(Modifier.width(CARD_W)) {
        Card(
            onClick = onClick,
            modifier = modifier.width(CARD_W).height(CARD_W * 9 / 16),
            shape = CardDefaults.shape(Lumen.CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            colors = CardDefaults.colors(containerColor = lerp(Lumen.Surface, item.app.accent, 0.5f)),
            border = CardDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), shape = Lumen.CardShape),
                focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = Lumen.CardShape),
            ),
        ) {
            if (banner != null) {
                Image(banner, item.app.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(item.app.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(item.app.name, color = Lumen.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text(item.app.tagline, color = Lumen.TextDim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp).height(32.dp))
        StatusBlock(item)
    }
}

@Composable
private fun StatusBlock(item: StoreItem) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        val (title, detail, color) = when (val s = item.status) {
            is Status.Checking -> Triple("A procurar…", "na origem oficial", Lumen.TextDim)
            is Status.Downloading -> Triple("A descarregar", "${s.percent} %", Lumen.Accent)
            is Status.Verifying -> Triple("A verificar", "assinatura do autor", Lumen.Accent)
            is Status.Confirming -> Triple("Confirme no ecrã", "o sistema pede autorização", Lumen.Accent)
            is Status.Error -> Triple("Não foi possível", s.message.take(60), Lumen.Warn)
            is Status.Idle -> when {
                item.installed == null -> Triple("Instalar", item.latest?.let { "versão $it" } ?: "", Lumen.Text)
                item.updateAvailable -> Triple("Atualizar", "${item.installed} → ${item.latest}", Lumen.Accent)
                else -> Triple("Abrir", "versão ${item.installed} · atualizada", Lumen.Ok)
            }
        }
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (detail.isNotEmpty()) Text(detail, color = Lumen.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        val status = item.status
        if (status is Status.Downloading) {
            Box(Modifier.padding(top = 6.dp).width(CARD_W).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(status.percent / 100f).height(4.dp).background(Lumen.Accent, CircleShape))
            }
        }
    }
}

@Composable
private fun StoreAction(label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.padding(top = 8.dp).height(40.dp),
        shape = CardDefaults.shape(CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f), contentColor = Lumen.TextDim,
            focusedContainerColor = Color.White, focusedContentColor = Lumen.Bg,
        ),
    ) {
        // Largura pelo texto: um fillMaxSize aqui esticava o botão (e o cabeçalho) até ocupar o ecrã.
        Box(Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = androidx.tv.material3.LocalContentColor.current)
        }
    }
}

private val CARD_W = 186.dp
