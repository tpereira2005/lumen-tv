package pt.tomas.lumen.store

import androidx.compose.ui.graphics.Color

/** Onde se encontra a versão mais recente de uma app. */
sealed interface ReleaseSource {
    /** Última release de um repositório GitHub; a versão vem do grupo 1 de [asset] ou, se não houver, da etiqueta. */
    data class GitHub(val repo: String, val asset: Regex) : ReleaseSource

    /** Página (ou listagem de pasta) oficial; [link] apanha a versão no grupo 1. Ligações relativas usam [page] como base. */
    data class Page(val page: String, val link: Regex) : ReleaseSource

    /**
     * Endereço oficial fixo de "a versão mais recente" (sem página de versões). A versão só se sabe abrindo o APK,
     * por isso a loja guarda a ETag do servidor e só volta a descarregar quando o ficheiro muda.
     */
    data class Direct(val url: String) : ReleaseSource
}

data class StoreApp(
    val pkg: String,
    val name: String,
    val tagline: String,
    val accent: Color,
    val source: ReleaseSource,
    /** SHA-256 do certificado de assinatura oficial (verificado com apksigner em 2026-10-23). */
    val certSha256: String,
)

/**
 * Catálogo da Loja Lumen: apps que NÃO existem na Appstore da Amazon (as que existem — VLC, LocalSend… — instalam-se
 * e atualizam-se por lá), com origem oficial conhecida e assinatura fixada.
 * Todas em ARM 32-bit (armeabi-v7a), que é o que o Fire TV Stick 4K (2.ª ger.) executa.
 */
val CATALOG = listOf(
    StoreApp(
        pkg = "com.stremio.one",
        name = "Stremio",
        tagline = "Filmes e séries com add-ons",
        accent = Color(0xFF7B5BF2),
        source = ReleaseSource.Page(
            "https://www.stremio.com/downloads",
            Regex("""https://dl\.strem\.io/android/v(\d+\.\d+\.\d+)-androidTV/com\.stremio\.one-[^"'\s]+-armeabi-v7a\.apk"""),
        ),
        certSha256 = "7e6a979c968f771e3fbcf2c2e8718ce61e708d87caf91fc13e2d4c19a8022c6b", // Ivo Georgiev, Stremio
    ),
    StoreApp(
        pkg = "com.nuvio.tv",
        name = "Nuvio",
        tagline = "Filmes e séries com add-ons, feito para TV",
        accent = Color(0xFF6C4DF0),
        // Repositório oficial (os antigos tapframe/* redirecionam para aqui). O nome do APK não tem versão: usa-se a etiqueta.
        source = ReleaseSource.GitHub("NuvioMedia/NuvioTV", Regex("""app-full-armeabi-v7a-release\.apk""")),
        certSha256 = "1cba06fbdf978d0e3f9ad58de6ba3d69e24a948384adde3aed95451e830e5210", // NuvioTV
    ),
    StoreApp(
        pkg = "ar.tvplayer.tv",
        name = "TiviMate",
        tagline = "Televisão em direto (IPTV) com guia de programação",
        accent = Color(0xFF1E88E5),
        source = ReleaseSource.Direct("https://tivimate.com/apk"), // → files.tivimate.com/tivimate.apk
        certSha256 = "cafa2c5e0affcc243b4df23d1a69720a78a92412afc210a051b88ca68fca2c5b", // Alexey Rabets (autor)
    ),
    StoreApp(
        pkg = "org.smarttube.stable",
        name = "SmartTube",
        tagline = "YouTube sem anúncios, com SponsorBlock",
        accent = Color(0xFFE53935),
        source = ReleaseSource.GitHub("yuliskov/SmartTube", Regex("""SmartTube_stable_([\d.]+)_armeabi-v7a\.apk""")),
        certSha256 = "109735aa24fe85e2e1623ebb3b1ecc4bf4263f4c6374e4411d646bfa2eda482a", // Yurii L., SmartTube
    ),
    StoreApp(
        pkg = "org.xbmc.kodi",
        name = "Kodi",
        tagline = "Centro multimédia para os seus ficheiros",
        accent = Color(0xFF17B2E7),
        // Só versões estáveis: "kodi-21.3-Omega-..." (as beta/rc têm "_beta1", "_rc1" e não encaixam).
        source = ReleaseSource.Page(
            "https://mirrors.kodi.tv/releases/android/arm/",
            Regex("""kodi-(\d+\.\d+(?:\.\d+)?)-[A-Za-z]+-armeabi-v7a\.apk"""),
        ),
        certSha256 = "f517b44b5db5e62a6c1ec55ba47526db7de0d61f6ba26a7987520e293499b8d5", // XBMC Foundation
    ),
    StoreApp(
        pkg = "com.phlox.tvwebbrowser",
        name = "TV Bro",
        tagline = "Navegador web feito para o comando",
        accent = Color(0xFF5CA8FF),
        // Variante leve (usa o motor web do sistema): 7 MB em vez de 150 MB.
        source = ReleaseSource.GitHub("truefedex/tv-bro", Regex("""tvbro-([\d.]+)-generic-geckoExcluded\.apk""")),
        certSha256 = "1e5124be7e7fcb7a6462b47a42a8567863c6fccc6fe7708cd278be4f43047c75", // Pheodor Tsapana, PhloX
    ),
    StoreApp(
        pkg = "com.fgl27.twitch",
        name = "SmartTwitchTV",
        tagline = "Twitch para TV, mais leve e sem anúncios",
        accent = Color(0xFF9146FF),
        source = ReleaseSource.GitHub("fgl27/SmartTwitchTV", Regex("""SmartTV_twitch_([\d_]+)\.apk""")),
        certSha256 = "838d9d90b5bcaa89c7376948b0d8da141c2d0cbba3b983b02a0c443dd63744d9", // Felipe Leon (autor)
    ),
)
