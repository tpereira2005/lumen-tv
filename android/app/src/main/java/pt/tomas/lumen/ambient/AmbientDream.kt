package pt.tomas.lumen.ambient

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random
import pt.tomas.lumen.lumen

/**
 * Ecrã ambiente do Lumen (proteção de ecrã), ao estilo Apple TV / Google TV:
 * fotografias de paisagem em ecrã inteiro com aproximação lenta (Ken Burns), troca suave a cada 90 s,
 * relógio e data no canto inferior esquerdo.
 *
 * - Fotos: "imagem do dia" do Bing ([PhotoCache]), guardadas no Stick; sem fotos, fundo de cor da hora do dia.
 * - Desempenho: Views simples e camadas de hardware durante as animações (uma textura por foto).
 * - Ecrã: o bloco do relógio desloca-se uns pixels a cada minuto, para não marcar.
 */
class AmbientDream : DreamService() {
    private val pt = Locale("pt", "PT")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var clockBlock: LinearLayout
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var extra: TextView
    private lateinit var caption: TextView
    private var photos: List<PhotoCache.Photo> = emptyList()
    private var index = 0
    private var kenBurns: AnimatorSet? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private val photoTick = object : Runnable {
        override fun run() {
            showNextPhoto()
            handler.postDelayed(this, PHOTO_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // qualquer tecla acorda e volta ao que estava
        isFullscreen = true

        val root = FrameLayout(this)
        root.background = skyFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        back = photoView().also { root.addView(it) }
        front = photoView().also { root.addView(it) }

        // Véu escuro em baixo à esquerda, onde fica o texto (legível em qualquer fotografia).
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xCC000000.toInt(), 0x55000000, 0x00000000),
            )
        })

        time = text(104f, "sans-serif-thin", 1f)
        date = text(22f, "sans-serif-light", 0.85f).apply { letterSpacing = 0.04f }
        extra = text(17f, "sans-serif-light", 0.75f).apply { setPadding(0, dp(10), 0, 0) }
        clockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(time)
            addView(date)
            addView(extra)
        }
        root.addView(clockBlock, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            leftMargin = dp(64); bottomMargin = dp(52)
        })

        caption = text(12f, "sans-serif", 0.55f).apply { maxWidth = dp(420) }
        root.addView(caption, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            rightMargin = dp(48); bottomMargin = dp(40)
        })
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.post(clockTick)
        photos = PhotoCache.cached(this)
        if (photos.isNotEmpty()) {
            index = Random.nextInt(photos.size)
            handler.post(photoTick)
        }
        // Atualiza a coleção em segundo plano (no máximo uma vez por dia); as novas entram na rotação.
        thread(name = "lumen-photos") {
            val fresh = PhotoCache.refresh(this)
            handler.post {
                val hadNone = photos.isEmpty()
                photos = fresh
                if (hadNone && photos.isNotEmpty()) handler.post(photoTick)
            }
        }
    }

    override fun onDreamingStopped() {
        handler.removeCallbacksAndMessages(null)
        kenBurns?.cancel()
        super.onDreamingStopped()
    }

    private fun updateClock() {
        val now = Date()
        time.text = SimpleDateFormat("HH:mm", pt).format(now)
        date.text = SimpleDateFormat("EEEE, d 'de' MMMM", pt).format(now).replaceFirstChar { it.titlecase(pt) }
        // Tempo e próximo jogo (os mesmos dados do topo do Lumen).
        val info = lumen.info.info.value
        val weather = info.weather?.let { "${it.icon}  ${it.tempC}° · ${it.label} no ${info.city}" }
        val match = info.next?.let { m ->
            val vs = if (m.home) "${info.team} × ${m.opponent}" else "${m.opponent} × ${info.team}"
            "⚽  $vs · " + SimpleDateFormat("EEE d MMM, HH:mm", pt).format(m.time).replace(".", "")
        }
        extra.text = listOfNotNull(weather, match).joinToString("\n")
        clockBlock.translationX = Random.nextInt(-dp(18), dp(18)).toFloat()
        clockBlock.translationY = Random.nextInt(-dp(12), dp(12)).toFloat()
    }

    private fun showNextPhoto() {
        if (photos.isEmpty()) return
        val photo = photos[index % photos.size]
        index++
        val bmp = decode(photo.file) ?: return

        // Fusão cruzada entre as duas vistas (a ordem das camadas não muda: o véu e o texto ficam por cima).
        val incoming = back
        val outgoing = front
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        caption.text = photo.caption
        startKenBurns(incoming)
        incoming.animate().alpha(1f).setDuration(1_500).start()
        outgoing.animate().alpha(0f).setDuration(1_500).withEndAction { outgoing.setImageDrawable(null) }.start()
        front = incoming
        back = outgoing
    }

    /** Aproximação lenta, com um pequeno desvio aleatório, durante o tempo de cada foto. */
    private fun startKenBurns(view: ImageView) {
        kenBurns?.cancel()
        view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
        val dx = Random.nextInt(-dp(40), dp(40)).toFloat()
        val dy = Random.nextInt(-dp(20), dp(20)).toFloat()
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        kenBurns = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.10f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.10f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, dx),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, dy),
            )
            duration = PHOTO_MS + 2_000
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        // RGB_565: metade da memória, sem diferença visível em fotografias.
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }.getOrNull()

    private fun photoView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(-1, -1)
    }

    /** Fundo sem fotos: gradiente escuro com o tom da hora do dia. */
    private fun skyFor(hour: Int): GradientDrawable {
        val tint = when (hour) {
            in 5..8 -> 0xFF1B2A4A.toInt()
            in 9..16 -> 0xFF14303A.toInt()
            in 17..20 -> 0xFF3A2414.toInt()
            else -> 0xFF1E1633.toInt()
        }
        return GradientDrawable(GradientDrawable.Orientation.TR_BL, intArrayOf(tint, Color.BLACK))
    }

    private fun text(sizeSp: Float, family: String, alpha: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        includeFontPadding = false
        setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x66000000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PHOTO_MS = 90_000L
    }
}
