package pt.tomas.lumen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import pt.tomas.lumen.data.AppEntry

data class MenuAction(val label: String, val run: () -> Unit)

/** Painel lateral de opções de uma app (mantendo OK premido, ou tecla Menu). */
@Composable
fun AppMenu(app: AppEntry, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // O menu abre com OK mantido premido; o "soltar" desse OK não pode ativar "Abrir".
    // Só aceitamos cliques depois de uma tecla ser premida já dentro do menu.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(app.pkg) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(300.dp)
                .background(Lumen.Surface)
                // Manter OK premido gera repetições de "premir"; só uma tecla nova (repeatCount 0) conta.
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) armed = true; false }
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(app.icon, null, modifier = Modifier.size(56.dp))
            Text(app.label, color = Lumen.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(app.pkg, color = Lumen.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(bottom = 14.dp))
            actions.forEachIndexed { i, action ->
                Card(
                    onClick = { if (armed) { action.run(); onDismiss() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.03f),
                    colors = CardDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Lumen.Text,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Lumen.Bg,
                    ),
                    border = CardDefaults.border(
                        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = RoundedCornerShape(10.dp)),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        MenuLabel(action.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    // Texto escuro quando o cartão fica branco (em foco): a tv-material propaga a cor de conteúdo.
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = androidx.tv.material3.LocalContentColor.current)
}
