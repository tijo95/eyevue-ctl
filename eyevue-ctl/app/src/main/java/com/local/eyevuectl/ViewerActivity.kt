package com.local.eyevuectl

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Visualiseur d'un media importe : la miniature cliquee s'ouvre en grand, avec
 * les actions demandees a l'ecran -> Telecharger (copie vers Telechargements) et
 * Supprimer. Le constructeur nomme ses boutons "Modifier" / "Supprimer" (capture
 * du 12-11 16:51) : la retouche d'image n'est pas notre objet, "Modifier" est
 * remplace par "Telecharger", qui est ce que l'utilisateur demande.
 *
 * Source de l'image : l'URI MediaStore passe par l'intent (c'est CE qui se lit
 * depuis Android 10, MediaColumns.DATA etant deprecie), le chemin du dossier
 * sert de secours. Une video ou un enregistrement est annonce comme tel : aucun
 * faux aperu n'est simule.
 */
class ViewerActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var img: ImageView
    private var key: String = ""
    private var name: String = ""
    private var uri: Uri? = null
    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY) ?: ""
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        @Suppress("DEPRECATION") val u: Uri? = intent.getParcelableExtra(EXTRA_URI)
        uri = u
        path = intent.getStringExtra(EXTRA_PATH)
        buildUi()
        loadImage()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun txt(sizeSp: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color) }

    private fun buildUi() {
        val root = ScrollView(this); root.setBackgroundColor(0xFF000000.toInt())
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10f), dp(24f), dp(10f), dp(12f))

        // --- entete : retour, nom du fichier, telecharger (capture 16:51)
        val head = LinearLayout(this); head.gravity = Gravity.CENTER_VERTICAL
        val back = txt(20f); back.text = "\u2190"
        back.setPadding(dp(10f), dp(6f), dp(14f), dp(6f))
        back.setOnClickListener { finish() }
        head.addView(back)
        val title = txt(15f); title.text = name
        title.gravity = Gravity.CENTER
        // deux separateurs DISTINCTS : la meme vue ajoutee deux fois a un parent
        // leve "child already has a parent"
        val spL = View(this); spL.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        val spR = View(this); spR.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        head.addView(spL); head.addView(title); head.addView(spR)
        val dl = txt(20f); dl.text = "\u2193"
        dl.setPadding(dp(14f), dp(6f), dp(10f), dp(6f))
        dl.setOnClickListener { doDownload() }
        head.addView(dl)
        box.addView(head)

        // --- media en grand
        img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340f))
        img.setPadding(0, dp(10f), 0, dp(10f))
        box.addView(img)

        statusText = txt(13f, 0xFF8E8E93.toInt())
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(8f), 0, dp(8f))
        box.addView(statusText)

        // --- barre d'actions (Telecharger / Supprimer)
        val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = round(0xFF1C1C1E.toInt(), 26f)
        bar.setPadding(dp(10f), dp(12f), dp(10f), dp(14f))
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) }
        bar.addView(action("\u2193", "T\u00e9l\u00e9charger") { doDownload() })
        bar.addView(action("\uD83D\uDDD1", "Supprimer") { doDelete() })
        box.addView(bar)

        root.addView(box)
        setContentView(root)
    }

    private fun action(glyph: String, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this); col.gravity = Gravity.CENTER
        col.setPadding(dp(18f), 0, dp(18f), 0)
        col.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val g = txt(20f); g.text = glyph; g.gravity = Gravity.CENTER
        col.addView(g)
        val l = txt(12f, 0xFFB0B0B6.toInt()); l.text = label; l.gravity = Gravity.CENTER
        l.setPadding(0, dp(2f), 0, 0)
        col.addView(l)
        col.setOnClickListener { onClick() }
        return col
    }

    private fun loadImage() {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("mp4", "3gp", "mov", "avi")) {
            statusText.text = "vid\u00e9o " + ext + " \u2014 aperu image non disponible"
            return
        }
        if (ext in setOf("aac", "wav", "mp3")) {
            statusText.text = "enregistrement " + ext + " \u2014 pas d'aperu image"
            return
        }
        statusText.text = "chargement\u2026"
        io.execute {
            val bmp = decode()
            main.post {
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                    statusText.text = bmp.width.toString() + "\u00d7" + bmp.height + " \u00b7 " + name
                } else statusText.text = "aperu illisible : " + name
            }
        }
    }

    private fun decode(): Bitmap? = try {
        val src = { uri?.let { contentResolver.openInputStream(it) }
            ?: path?.let { if (File(it).isFile) FileInputStream(File(it)) else null } }
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        src()?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        val px = resources.displayMetrics.widthPixels
        while (o.outWidth / s / 2 >= px && o.outHeight / s / 2 >= px) s *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = s
        src()?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) { null }

    /** Un WifiImport sert ici a la seule commande de suppression sur l'AP. */
    private val wifiImport by lazy {
        WifiImport(this, getSharedPreferences("eyevue", MODE_PRIVATE), object : WifiImport.Listener {
            override fun onPhase(phase: String, detail: String) { main.post { statusText.text = detail } }
            override fun onProgress(done: Int, total: Int, filePct: Int) {}
            override fun onFileDone(f: MediaFile) {}
            override fun onFinished(okCount: Int, failed: List<String>) {}
            override fun onLog(s: String) { main.post { statusText.text = s } }
        })
    }

    private fun doDownload() {
        statusText.text = "t\u00e9l\u00e9chargement\u2026"
        io.execute {
            val f = MediaOps.find(this, key)
            if (f == null) { main.post { statusText.text = "fichier introuvable : " + name }; return@execute }
            val err = MediaOps.copyToDownloads(this, f)
            main.post { statusText.text = err ?: ("copi\u00e9 dans T\u00e9l\u00e9chargements/Eyevue \u2014 " + f.name) }
        }
    }

    /**
     * Suppression du media visualise : TOUTES les lignes de la cle, puis les
     * lunettes.
     *
     * L'ordre est obligatoire. Supprimer d'abord sur les lunettes laisserait, sur
     * le telephone, les clones issus du dedoublonnage de MediaStore (mesure du
     * 12-11 : "DH-...407736.jpg" + (1) (2) (3) pour UN fichier AP) ; l'original
     * serait detruit la-bas, irrecuperable, et la galerie garderait des vignettes
     * qui ne correspondent plus a rien.
     *
     * Les lunettes sont rappelables : si le local reussit et l'AP echoue, on le
     * dit et on ne finit pas, pour que l'ecran reste utilisable (retente /
     * Telecharger avant d'abandonner).
     */
    private fun doDelete() {
        io.execute {
            val errLocal = MediaOps.deleteAll(this, key)
            if (errLocal != null && MediaOps.findAll(this, key).isNotEmpty()) {
                main.post { statusText.text = errLocal }
                return@execute
            }
            val r = wifiImport.deleteOnAp(name)
            main.post {
                if (r is WifiImport.ApRes.Err) {
                    statusText.text = "telephone supprimes, MAIS lunettes pas jointes : " +
                        r.why + " \u2014 le fichier reste sur les lunettes"
                    setResult(RESULT_OK)
                    return@post
                }
                statusText.text = "supprim\u00e9 (telephone + lunettes)"
                setResult(RESULT_OK)
                main.postDelayed({ finish() }, 700)
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_NAME = "name"
        const val EXTRA_URI = "uri"
        const val EXTRA_PATH = "path"
    }
}
