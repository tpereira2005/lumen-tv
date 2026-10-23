package pt.tomas.lumen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import pt.tomas.lumen.data.AppEntry
import pt.tomas.lumen.data.Info
import pt.tomas.lumen.store.StoreItem
import pt.tomas.lumen.tv.TvSource
import pt.tomas.lumen.tv.TvState
import java.text.SimpleDateFormat
import java.util.Date

/** Tudo o que o ecrã inicial precisa; a MainActivity liga isto aos dados e às ações. */
data class HomeState(
    val apps: List<AppEntry>,
    val favorites: List<String>,
    val hidden: Set<String>,
    val recents: List<String>,
    val tv: TvState,
    val guardEnabled: Boolean,
    /** Hora a que o temporizador desliga a TV, ou null. */
    val timerEndsAt: Long?,
    val storeItems: List<StoreItem> = emptyList(),
    val appOrder: List<String> = emptyList(),
    /** Incrementa a cada botão Home, para voltar ao topo. */
    val homeTick: Int = 0,
    val info: Info = Info(city = "", team = ""),
    /** Texto vindo do comando do iPhone para abrir a pesquisa (null = nada pedido). */
    val searchRequest: SearchRequest? = null,
    /** Aviso de atualização do Fire OS (null = nada a dizer). */
    val updateNotice: String? = null,
    val remoteUrl: String? = null,
)

/** Pedido de pesquisa; o [id] muda a cada pedido para a mesma palavra poder ser pedida duas vezes. */
data class SearchRequest(val id: Int, val text: String)

interface HomeActions {
    fun launch(app: AppEntry)
    fun toggleFavorite(pkg: String)
    fun moveFavorite(pkg: String, delta: Int)
    fun moveApp(pkg: String, delta: Int, visible: List<String>)
    fun toggleHidden(pkg: String)
    fun openSettings()
    fun openAmazonHome()
    fun changeSource(source: TvSource)
    fun cycleSleepTimer()
    fun storeInstall(item: StoreItem)
    fun storeCheck()
    fun dismissUpdateNotice()
}

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val byPkg = remember(state.apps) { state.apps.associateBy { it.pkg } }
    val favApps = state.favorites.mapNotNull { byPkg[it] }
    val others = remember(state.apps, state.hidden, state.favorites, state.appOrder) {
        val rank = state.appOrder.withIndex().associate { it.value to it.index }
        state.apps.filter { it.pkg !in state.hidden && it.pkg !in state.favorites }
            .sortedWith(compareBy({ rank[it.pkg] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }
    val hiddenApps = state.apps.filter { it.pkg in state.hidden }
    // Recentes que não estão já nos favoritos (esses estão à vista na primeira linha).
    val recentApps = state.recents.filter { it !in state.favorites && it !in state.hidden }.mapNotNull { byPkg[it] }.take(6)

    // O topo lê este estado sozinho (Hero recebe o State, não o valor): mudar o foco
    // só recompõe o topo, e não o ecrã inteiro com todas as linhas.
    val hero = remember { mutableStateOf(HeroInfo("Lumen", null)) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    // Um FocusRequester por cartão, para devolver o foco ao cartão certo quando o menu fecha.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var menuOrigin by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var showStore by rememberSaveable { mutableStateOf(false) }
    var showRemote by rememberSaveable { mutableStateOf(false) }
    var searchText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.searchRequest?.id) { state.searchRequest?.let { searchText = it.text } }
    val storeUpdates = state.storeItems.count { it.updateAvailable }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(menuFor) {
        val key = menuOrigin ?: return@LaunchedEffect
        if (menuFor != null) return@LaunchedEffect
        // Se a app saiu desta linha (ocultada / removida dos favoritos), o foco volta ao início.
        if (runCatching { requesters.getValue(key).requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
        menuOrigin = null
    }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    // Depois de mover, o cartão pode mudar de linha (outro composable): devolvemos-lhe o foco.
    LaunchedEffect(state.appOrder, state.favorites) {
        moving?.let { key -> runCatching { requesters[key]?.requestFocus() } }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.homeTick) {
        if (state.homeTick > 0) {
            menuFor = null
            moving = null
            listState.scrollToItem(0)
            runCatching { firstFocus.requestFocus() }
        }
    }

    LaunchedEffect(favApps.isNotEmpty()) {
        if (favApps.isNotEmpty() && !focusedOnce) {
            focusedOnce = runCatching { firstFocus.requestFocus() }.isSuccess
        }
    }

    fun focusApp(app: AppEntry) {
        hero.value = HeroInfo(app.label, kindOf(app.pkg), app.accent)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(hero)
            Column(Modifier.fillMaxSize()) {
                TopBar(hero, state.tv, state.info)
                Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (favApps.isNotEmpty()) {
                        item(key = "fav-title") { SectionTitle("Favoritos") }
                        item(key = "fav-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(horizontal = Lumen.SidePad, vertical = 16.dp),
                            ) {
                                items(favApps, key = { it.pkg }) { app ->
                                    val isMoving = moving == "fav:" + app.pkg
                                    AppCard(
                                        app = app,
                                        width = 196.dp,
                                        moving = isMoving,
                                        modifier = Modifier
                                            .then(if (app == favApps.first()) Modifier.focusRequester(firstFocus) else Modifier)
                                            .focusRequester(requester("fav:" + app.pkg))
                                            .moveKeys(isMoving, onMove = { d -> actions.moveFavorite(app.pkg, d) }, onDone = { moving = null }),
                                        onFocus = { focusApp(app) },
                                        onClick = { if (!isMoving) actions.launch(app) },
                                        onLongClick = { menuOrigin = "fav:" + app.pkg; menuFor = app },
                                    )
                                }
                            }
                        }
                    }

                    if (recentApps.isNotEmpty()) {
                        item(key = "rec-title") { SectionTitle("Recentes") }
                        item(key = "rec-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = Lumen.SidePad, vertical = 12.dp),
                            ) {
                                items(recentApps, key = { "rec-" + it.pkg }) { app ->
                                    AppCard(
                                        app = app,
                                        width = 158.dp,
                                        modifier = Modifier.focusRequester(requester("rec:" + app.pkg)),
                                        onFocus = { focusApp(app) },
                                        onClick = { actions.launch(app) },
                                        onLongClick = { menuOrigin = "rec:" + app.pkg; menuFor = app },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "apps-title") { SectionTitle("Apps") }
                    items(others.chunked(APPS_PER_ROW), key = { row -> "apps-" + row.first().pkg }) { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = Lumen.SidePad, vertical = 9.dp),
                        ) {
                            row.forEach { app ->
                                val isMoving = moving == "app:" + app.pkg
                                AppCard(
                                    app = app,
                                    width = 158.dp,
                                    moving = isMoving,
                                    modifier = Modifier
                                        .focusRequester(requester("app:" + app.pkg))
                                        .moveKeys(
                                            isMoving,
                                            onMove = { d -> actions.moveApp(app.pkg, d, others.map { it.pkg }) },
                                            onDone = { moving = null },
                                            rowSize = APPS_PER_ROW,
                                        ),
                                    onFocus = { focusApp(app) },
                                    onClick = { if (!isMoving) actions.launch(app) },
                                    onLongClick = { menuOrigin = "app:" + app.pkg; menuFor = app },
                                )
                            }
                        }
                    }

                    if (state.tv.sources.isNotEmpty()) {
                        item(key = "src-title") { SectionTitle("Entradas da TV") }
                        item(key = "src-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = Lumen.SidePad, vertical = 10.dp),
                            ) {
                                items(state.tv.sources.filter { it.name in PINNED_SOURCES || it.hasSignal || it.name == state.tv.source }, key = { it.id }) { src ->
                                    val current = src.name == state.tv.source
                                    ActionTile(
                                        title = sourceLabel(src.name),
                                        subtitle = when {
                                            current -> "a ver agora"
                                            src.hasSignal -> "com sinal"
                                            else -> "sem sinal"
                                        },
                                        dot = if (src.hasSignal) Lumen.Ok else null,
                                        selected = current,
                                        onFocus = { hero.value = HeroInfo(sourceLabel(src.name), "Entrada da TV · " + src.name) },
                                        onClick = { actions.changeSource(src) },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "sys-title") { SectionTitle("Sistema") }
                    item(key = "sys-row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = Lumen.SidePad, vertical = 10.dp),
                        ) {
                            item {
                                ActionTile("Pesquisar", "apps e Loja",
                                    onFocus = { hero.value = HeroInfo("Pesquisar", "Também pode escrever no iPhone") },
                                    onClick = { searchText = "" })
                            }
                            item {
                                ActionTile("Comando", "no iPhone",
                                    modifier = Modifier.focusRequester(requester("sys:comando")),
                                    onFocus = { hero.value = HeroInfo("Comando no iPhone", "Setas, volume, entradas e apps no telemóvel") },
                                    onClick = { showRemote = true })
                            }
                            state.tv.sources.firstOrNull { it.name == "HDMI4" }?.let { ps5 ->
                                item {
                                    ActionTile("Jogar", "PlayStation 5",
                                        onFocus = { hero.value = HeroInfo("Jogar na PlayStation 5", "Muda a TV para a PS5. Para voltar: Home no comando do Fire TV") },
                                        onClick = { actions.changeSource(ps5) })
                                }
                            }
                            item {
                                ActionTile("Definições", "Fire TV", onFocus = { hero.value = HeroInfo("Definições", "Fire TV") },
                                    onClick = actions::openSettings)
                            }
                            item {
                                ActionTile("Loja Lumen",
                                    when (storeUpdates) { 0 -> "apps verificadas"; 1 -> "1 atualização"; else -> "$storeUpdates atualizações" },
                                    dot = if (storeUpdates > 0) Lumen.Accent else null,
                                    modifier = Modifier.focusRequester(requester("sys:loja")),
                                    onFocus = { hero.value = HeroInfo("Loja Lumen", "Stremio · SmartTube · Kodi · VLC") },
                                    onClick = { showStore = true })
                            }
                            item {
                                ActionTile("Apps ocultas", "${hiddenApps.size} " + if (showHidden) "· esconder" else "· mostrar",
                                    onFocus = { hero.value = HeroInfo("Apps ocultas", "Não são desinstaladas — só saem do ecrã inicial") },
                                    onClick = { showHidden = !showHidden })
                            }
                            item {
                                ActionTile("Temporizador",
                                    state.timerEndsAt?.let { "desliga às " + SimpleDateFormat("HH:mm", Lumen.PT).format(Date(it)) } ?: "desligado",
                                    dot = if (state.timerEndsAt != null) Lumen.Accent else null,
                                    onFocus = { hero.value = HeroInfo("Temporizador", "OK: 30 → 60 → 90 → 120 min → desligado. No fim desliga a TV e o Fire TV") },
                                    onClick = actions::cycleSleepTimer)
                            }
                            item {
                                ActionTile("Ecrã Amazon", "abrir por 10 min",
                                    onFocus = { hero.value = HeroInfo("Ecrã inicial da Amazon", "O Lumen volta quando sair") },
                                    onClick = actions::openAmazonHome)
                            }
                        }
                    }

                    if (showHidden) {
                        item(key = "hidden-title") { SectionTitle("Apps ocultas — selecione para voltar a mostrar") }
                        items(hiddenApps.chunked(5), key = { row -> "hidden-" + row.first().pkg }) { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(horizontal = Lumen.SidePad, vertical = 9.dp),
                            ) {
                                row.forEach { app ->
                                    AppCard(
                                        app = app,
                                        width = 158.dp,
                                        badge = "oculta",
                                        modifier = Modifier.focusRequester(requester("hid:" + app.pkg)),
                                        onFocus = { focusApp(app) },
                                        onClick = { actions.toggleHidden(app.pkg) },
                                        onLongClick = { menuOrigin = "hid:" + app.pkg; menuFor = app },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "footer") { Footer(state.tv.connected, state.guardEnabled, state.updateNotice, actions::dismissUpdateNotice) }
                }
                }
            }

            searchText?.let { initial ->
                SearchScreen(
                    initial = initial,
                    apps = state.apps,
                    storeNames = state.storeItems.map { it.app.name },
                    onLaunch = { app -> searchText = null; actions.launch(app) },
                    onOpenStore = { searchText = null; showStore = true },
                    onDismiss = { searchText = null },
                )
            }
            if (showRemote) {
                RemoteScreen(state.remoteUrl) {
                    showRemote = false
                    runCatching { requesters["sys:comando"]?.requestFocus() }
                }
            }
            if (showStore) {
                StoreScreen(
                    items = state.storeItems,
                    installedApps = byPkg,
                    onInstall = actions::storeInstall,
                    onOpen = actions::launch,
                    onCheck = actions::storeCheck,
                    onDismiss = {
                        showStore = false
                        runCatching { requesters["sys:loja"]?.requestFocus() }
                    },
                )
            }

            menuFor?.let { app ->
                val isFav = app.pkg in state.favorites
                val isHidden = app.pkg in state.hidden
                AppMenu(
                    app = app,
                    actions = buildList {
                        add(MenuAction("Abrir") { actions.launch(app) })
                        add(MenuAction(if (isFav) "Remover dos favoritos" else "Adicionar aos favoritos") { actions.toggleFavorite(app.pkg) })
                        val origin = menuOrigin.orEmpty()
                        if (isFav && origin.startsWith("fav:")) add(MenuAction("Mover") { moving = "fav:" + app.pkg })
                        if (!isFav && !isHidden && origin.startsWith("app:")) add(MenuAction("Mover") { moving = "app:" + app.pkg })
                        add(MenuAction(if (isHidden) "Mostrar no ecrã inicial" else "Ocultar") { actions.toggleHidden(app.pkg) })
                    },
                    onDismiss = { menuFor = null },
                )
            }
        }
    }
}

/** Entradas sempre visíveis (as outras só aparecem com sinal). */
private val PINNED_SOURCES = setOf("TV", "HDMI1", "HDMI4")

/** Nomes amigáveis das entradas da TV deste quarto. */
private fun sourceLabel(name: String) = when (name) {
    "HDMI1" -> "Fire TV"
    "HDMI4" -> "PlayStation 5"
    "TV" -> "Televisão"
    else -> name
}

/** Rodapé discreto: estado das ligações à esquerda, teclas à direita. */
@Composable
private fun Footer(tvConnected: Boolean, guardEnabled: Boolean, notice: String?, onDismissNotice: () -> Unit) {
    if (notice != null) {
        // Aviso de atualização do Fire OS: visível até ser lido (OK fecha).
        ActionTile("Fire OS atualizado", notice, dot = Lumen.Accent,
            modifier = Modifier.padding(start = Lumen.SidePad, top = 18.dp).width(520.dp),
            onClick = onDismissNotice)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = Lumen.SidePad, end = Lumen.SidePad, top = 22.dp),
    ) {
        StatusDot(tvConnected, "TV ligada à rede", "TV sem ligação")
        Spacer(Modifier.width(22.dp))
        StatusDot(guardEnabled, "Botão Home ativo", "Botão Home inativo")
        Spacer(Modifier.weight(1f))
        KeyHint("OK", "abrir")
        Spacer(Modifier.width(18.dp))
        KeyHint("manter OK", "opções")
    }
}

@Composable
private fun StatusDot(ok: Boolean, okText: String, badText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (ok) Lumen.Ok else Lumen.Warn, CircleShape))
        Text(if (ok) okText else badText, color = Lumen.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key, color = Lumen.TextDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Text(action, color = Lumen.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

private const val APPS_PER_ROW = 5

/**
 * Modo mover: setas trocam a posição (cima/baixo saltam uma linha quando há [rowSize]); OK/Voltar terminam.
 * Consumimos o premir E o soltar do OK — se o soltar chegasse ao cartão, abria a app.
 */
private fun Modifier.moveKeys(active: Boolean, onMove: (Int) -> Unit, onDone: () -> Unit, rowSize: Int? = null) =
    onPreviewKeyEvent { e ->
        if (!active) return@onPreviewKeyEvent false
        val down = e.type == KeyEventType.KeyDown
        when (e.key) {
            Key.DirectionLeft -> { if (down) onMove(-1); true }
            Key.DirectionRight -> { if (down) onMove(+1); true }
            Key.DirectionUp -> { if (down && rowSize != null) onMove(-rowSize); true }
            Key.DirectionDown -> { if (down && rowSize != null) onMove(+rowSize); true }
            Key.DirectionCenter, Key.Enter, Key.Back -> { if (!down) onDone(); true }
            else -> false
        }
    }
