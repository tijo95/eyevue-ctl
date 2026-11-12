package com.local.eyevuectl

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.ContentUris
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Ecran Album : miroir de l'ecran d'origine (bandeau d'etat + bouton Importer,
 * onglets Tout/Photo/Video/Enregistrement, ligne de date, grille de vignettes,
 * case de selection multiple).
 *
 * donnees : compteur "N fichiers a importer" = trame 0x42 APP_RECEIVE_THUMBNAIL_COUNT
 * (payload [00 05] observe -> 5 ; le cycle 0x44 -> 0x42 [00 00] apres import est
 * confirme dans nos journaux de trames). Liste des fichiers etablie par le flux
 * BLE (les 12 noms du log constructeur etaient en memoire AVANT le 1er GET HTTP),
 * en secours par app/getfilelist (endpoint nomme dans le bytecode, jamais observe
 * sur notre paire -> appele seulement si le BLE ne donne rien).
 *
 * Les vignettes par le flux BLE 0x40/0x36/0x37 ne sont PAS implementees : le
 * format de la trame PHOTO_TRANS (0x98) n'a jamais ete observe sur notre paire.
 * En attendant, les cases affichent l'aperu des fichiers DEJA imports (galerie)
 * et une pastille pour les fichiers restants. Rien n'est simule.
 */
@SuppressLint("MissingPermission")
class AlbumActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ble: BleCtlShared
    private lateinit var wifiImport: WifiImport

    private var pendingCount = -1            // compteur 0x42 ; -1 = pas recu
    private var bleNames: List<String> = emptyList()
    private var files: List<MediaFile> = emptyList()
    private var tab = MediaFile.TAB_ALL
    private var selectMode = false
    private var importing = false
    private var wifiPermissionsOk = false

    private companion object {
        const val REQ_WIFI = 7
        const val REQ_VIEW = 8
    }

    private lateinit var bannerText: TextView
    private lateinit var bannerBar: View
    private lateinit var cancelBtn: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var gridBox: LinearLayout
    private lateinit var statusText: TextView
    private val thumbs = HashMap<String, GalleryItem>()   // cle canonique -> media importé

    /** Un media deja importé : uri MediaStore, et/ou chemin du dossier. */
    private class GalleryItem(val name: String, val path: String?, val uri: android.net.Uri?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleCtlShared.get(this)
        wifiImport = WifiImport(this, getSharedPreferences("eyevue", MODE_PRIVATE), importListener)
        // le garde-fou double import lit la table d'aperus, pas le compteur BLE
        wifiImport.alreadyImported = { key -> thumbs.containsKey(key) }
        buildUi()
        ble.listeners.add(bleListener)
        scanGallery()
        ble.eyevue.pullThumbnailCount()      // 0x40 -> la lunette repond 0x42 (compteur)
        refresh()
        ensureWifiPermissions()               // sans NEARBY_WIFI_DEVICES le scan est vide
    }

    /**
     * API 33+ : le scan WiFi est compte comme "appareils a proximite". Sans cette
     * autorisation accordee, wifi.scanResults revient VIDE : la boucle d'import
     * tournerait 20 cycles pour rien et accuserait l'AP d'etre absent, ce qui
     * serait une fausse accusee. On la demande donc a l'ouverture de l'Album.
     */
    private fun ensureWifiPermissions() {
        val needed = when {
            android.os.Build.VERSION.SDK_INT >= 33 ->
                arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            android.os.Build.VERSION.SDK_INT >= 29 -> arrayOf()
            else -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { wifiPermissionsOk = true; return }
        requestPermissions(missing.toTypedArray(), REQ_WIFI)
        bannerText.text = "autorisations WiFi a accorder pour voir l'AP des lunettes"
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_WIFI) {
            wifiPermissionsOk = results.isNotEmpty() && results.all {
                it == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            bannerText.text = if (wifiPermissionsOk) "autorisations WiFi accordees"
                else "autorisations WiFi refusees : l'AP ne sera pas vu au scan"
        }
    }

    override fun onDestroy() {
        ble.listeners.remove(bleListener)
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ BLE

    private val bleListener = object : BleCtlShared.Listener {
        override fun onFrame(rx: Frame.Rx) {
            when (rx.cmd) {
                // 0x42 APP_RECEIVE_THUMBNAIL_COUNT : payload [00 05] observe -> 5.
                Cmd.APP_RECEIVE_THUMBNAIL_COUNT -> {
                    val n = if (rx.payload.size >= 2)
                        ((rx.payload[0].toInt() and 0xFF) shl 8) or (rx.payload[1].toInt() and 0xFF)
                    else rx.payload.firstOrNull()?.toInt()?.and(0xFF)
                    pendingCount = n ?: 0
                    main.post { refresh() }
                }
                // 0x44 FILE_DOWNLOAD_FINISH : le constructeur rafraichit le compteur
                // apres (cycle 0x44 -> 0x42 [00 00] confirme dans nos journaux).
                Cmd.FILE_DOWNLOAD_FINISH -> main.post {
                    ble.eyevue.pullThumbnailCount(); refresh()
                }
                // 0x25 APP_RECEIVE_WIFI_INFO : SSID de l'AP (ASCII ; 13 octets observes,
                // le mot de passe n'y est JAMAIS : c'est une constante du firmware-app).
                Cmd.APP_RECEIVE_WIFI_INFO -> {
                    val s = String(rx.payload, Charsets.ISO_8859_1).trim('\u0000').trim()
                    if (s.isNotEmpty()) wifiImport.bleSsid = s
                }
                // 0x40 APP_PULL_THUMBNAIL_COUNT : reponse portant (selon le bytecode)
                // les entrees de la liste. Les champs du nom n'ont pas ete observes
                // sur notre paire : on journalise l'hexa, on ne decode pas au pif.
                Cmd.APP_PULL_THUMBNAIL_COUNT -> main.post {
                    logLine("0x40 recu : payload=" + rx.payloadHex)
                    harvestNames(rx.payload)
                }
            }
        }
    }

    /**
     * Noms ASCII visibles dans le payload (sequence "2026...jpg" repérable) :
     * on ne retient que ce qui ressembla reellement a un nom de media, jamais
     * une supposition sur les champs binaires.
     */
    private fun harvestNames(payload: ByteArray) {
        val s = String(payload, Charsets.ISO_8859_1)
        val found = Regex("[0-9A-Za-z_.-]{6,120}\\.(jpe?g|mp4|aac|wav|png|3gp|mov|mp3)",
            RegexOption.IGNORE_CASE).findAll(s).map { it.value }.toList()
        if (found.isNotEmpty()) {
            bleNames = found
            refresh()
        }
    }

    // ------------------------------------------------------------------ import

    private val importListener = object : WifiImport.Listener {
        override fun onPhase(phase: String, detail: String) {
            when (phase) {
                "connexion", "importation", "listing" -> { bannerText.text = detail; bannerBar.visibility = View.VISIBLE }
                "echec", "aucun" -> { bannerText.text = detail; bannerBar.visibility = View.GONE }
            }
        }
        override fun onListReceived(files: List<MediaFile>) {
            // le listing de l'AP alimente enfin la grille : c'est LA source des
            // noms, le BLE ne donne qu'un compteur (0x42) et un SSID (0x25)
            this@AlbumActivity.files = files
            bleNames = files.map { it.name }
            if (selectMode) files.forEach { it.checked = true }
            rebuildGrid()
        }
        override fun onProgress(done: Int, total: Int, filePct: Int) {
            bannerText.text = "Importation en cours ${done}/${total}" +
                (if (filePct >= 0) " (${filePct}%)" else "")
            bannerBar.visibility = View.VISIBLE
        }
        override fun onFileDone(f: MediaFile) {
            io.execute { scanGallery() }
        }
        override fun onFinished(okCount: Int, failed: List<String>) {
            importing = false
            cancelBtn.visibility = View.GONE
            bannerBar.visibility = View.GONE
            bannerText.text = if (okCount > 0) "$okCount fichier(s) importé(s)" +
                    (if (failed.isNotEmpty()) " — ${failed.size} échec(s)" else "")
                else "Import arrêté" + (if (failed.isNotEmpty()) " : " + failed.first() else "")
            ble.eyevue.pullThumbnailCount()
            io.execute { scanGallery() }
            // le message de resultat tient 8 s : refresh() repasse sinon le bandeau
            // au compteur dans la seconde, ce qui rend la fin d'import muette
            bannerHoldUntil = android.os.SystemClock.elapsedRealtime() + 8000
            main.post { refresh() }
        }
        override fun onLog(s: String) { logLine(s) }
    }

    private fun startImport() {
        // PAS de liste a ce stade : les noms viennent de l'AP, et l'AP n'est
        // allumee que par 0x39. Le garde-fou "liste vide -> return" rendait le
        // bouton muet : le fichier est detecte par 0x42, le clic partait, mais
        // sel etait vide et le message etait efface par le refresh BLE suivant.
        importing = true
        cancelBtn.visibility = View.VISIBLE
        bannerText.text = "ouverture de l'AP des lunettes\u2026"
        bannerBar.visibility = View.VISIBLE
        ble.eyevue.openWifiAp()          // 0x39 payload [01] : le seul observe sur notre paire
        wifiImport.start(if (pendingCount > 0) pendingCount else 0)
    }

    // ------------------------------------------------------------------ galerie

    /**
     * Aperus = fichiers DEJA presents dans la galerie (imports precedents).
     *
     * MediaStore MediaColumns.DATA est DEPRECIE : il revient NULL sur les lignes
     * creees par un import MediaStore sur Android 10+. L'ancienne version faisait
     * query(proj = DATA, DISPLAY_NAME) puis "if (path != null)" : toute ligne
     * importee etait donc ecartee, et un scan de reouverture ne rapportait rien.
     * Constate a l'ecran (mesure du 12-11 : les 5 vignettes vues a 14:32,
     * "plus rien" aux reouvertures de 14:50-14:52, alors que DCIM/100HUNTI
     * contient les 5 fichiers et que MediaStore a les 5 lignes, is_pending=0).
     *
     * On retient desormais l'URI MediaStore (c'est CE qui se lit, sans chemin
     * physique) et la cle canonique MediaFile.keyOf(display_name).
     */
    private fun scanGallery() {
        val out = HashMap<String, GalleryItem>()
        // 1) MediaStore, source de verite : uri + nom affiche, pas de DATA.
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED)
        val gallery = ArrayList<MediaFile>()
        for (ext in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                           MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                           MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)) {
            try {
                contentResolver.query(ext, proj, "_display_name LIKE 'DH-%'", null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(proj[0])
                    val iName = c.getColumnIndexOrThrow(proj[1])
                    val iAdded = c.getColumnIndexOrThrow(proj[2])
                    while (c.moveToNext()) {
                        val name = c.getString(iName) ?: continue
                        val uri = ContentUris.withAppendedId(ext, c.getLong(iId))
                        val added = c.getLong(iAdded)
                        out[MediaFile.keyOf(name)] = GalleryItem(name, null, uri)
                        gallery.add(MediaFile(uri.toString(), name, 0L, added)
                            .also { it.key = MediaFile.keyOf(name) })
                    }
                }
            } catch (e: Exception) { logLine("scan galerie " + e.message) }
        }
        // 2) Dossier direct, secours (lit sans MediaStore si la ligne est absente).
        try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "100HUNTI")
            dir.listFiles()?.toList()?.forEach { f ->
                if (f.isFile && f.length() > 0) {
                    val key = MediaFile.keyOf(f.name)
                    val seen = thumbs.containsKey(key)
                    if (!seen) out[key] = GalleryItem(f.name, f.absolutePath, null)
                    if (!gallery.any { it.key == key })
                        gallery.add(MediaFile(f.absolutePath, f.name, f.length(),
                            f.lastModified()).also { it.key = key })
                    if (seen && out[key]?.path == null)
                        out[key] = GalleryItem(f.name, f.absolutePath, out[key]?.uri)
                }
            }
        } catch (e: Exception) { logLine("scan dossier : " + e.message) }
        thumbs.putAll(out)
        galleryFiles = gallery
        main.post { rebuildGrid() }
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun txt(sizeSp: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color) }

    private fun buildUi() {
        val bg = 0xFF000000.toInt()
        val root = ScrollView(this); root.setBackgroundColor(bg)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12f), dp(20f), dp(12f), dp(12f))

        // --- bandeau (capture 1 : "5 fichiers a importer" + bouton + "X importer" en cours)
        val band = LinearLayout(this); band.gravity = Gravity.CENTER_VERTICAL
        band.background = round(0xFF1C1C1E.toInt(), 12f)
        band.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        val icon = txt(22f); icon.text = "\uD83D\uDCF8"; band.addView(icon)
        val sp = View(this); sp.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        bannerText = txt(15f); bannerText.text = "lecture du compteur…"
        bannerText.setPadding(dp(10f), 0, dp(10f), 0)
        band.addView(bannerText)
        cancelBtn = txt(15f, 0xFFFF453A.toInt()); cancelBtn.text = "Annuler"
        cancelBtn.visibility = View.GONE
        cancelBtn.setPadding(dp(6f), 0, dp(6f), 0)
        cancelBtn.setOnClickListener { wifiImport.cancel(); bannerText.text = "annulation…" }
        band.addView(cancelBtn)
        box.addView(band)
        bannerBar = View(this)
        bannerBar.background = round(0xFFFFCC00.toInt(), 2f)
        bannerBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4f)).apply { topMargin = dp(8f) }
        bannerBar.visibility = View.GONE
        box.addView(bannerBar)

        // --- ligne Importer + case selection (bouton jaune de la capture 1,
        //     case ☑ de la capture 3)
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12f), 0, dp(8f))
        val imp = txt(16f, 0xFF000000.toInt()); imp.text = "Importer"
        imp.background = round(0xFFFFD60A.toInt(), 20f)
        imp.setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        imp.setOnClickListener { if (!importing) startImport() }
        row.addView(imp)
        val sp2 = View(this); sp2.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        row.addView(sp2)
        val check = txt(18f); check.text = "\u2610"
        check.setOnClickListener {
            selectMode = !selectMode
            check.text = if (selectMode) "\u2611" else "\u2610"
            rebuildGrid()
        }
        row.addView(check)
        box.addView(row)

        // --- onglets (captures 2 et 3)
        tabsRow = LinearLayout(this)
        tabsRow.setPadding(0, dp(6f), 0, dp(10f))
        for ((ix, label) in MediaFile.TAB_LABELS.withIndex()) {
            val t = txt(15f, 0xFF9A9AA0.toInt()); t.text = label
            t.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            t.setOnClickListener { tab = ix; refresh() }
            t.tag = ix
            tabsRow.addView(t)
            if (ix < MediaFile.TAB_LABELS.size - 1) {
                val g = View(this); g.layoutParams = LinearLayout.LayoutParams(dp(6f), 0)
                tabsRow.addView(g)
            }
        }
        box.addView(tabsRow)

        // --- grille (cases carrees, ligne de date avant chaque groupe)
        gridBox = LinearLayout(this); gridBox.orientation = LinearLayout.VERTICAL
        box.addView(gridBox)

        // --- barre du bas demandee a l'ecran (capture 16:51) : Telecharger /
        //     Supprimer / Tout selectionner. Elle agit sur la SELECTION, et ne
        //     propose que ce qui est reellement faisable : un media encore sur
        //     les lunettes n'est ni telechargeable ni supprimable ici (il n'est
        //     pas sur le telephone) -> la reponse le dit, rien n'est simule.
        val bar = LinearLayout(this); bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = round(0xFF1C1C1E.toInt(), 26f)
        bar.setPadding(dp(10f), dp(12f), dp(10f), dp(14f))
        bar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) }
        bar.addView(barAction("\u2193", "Télécharger") { onBarDownload() })
        bar.addView(barAction("\uD83D\uDDD1", "Supprimer") { onBarDelete() })
        bar.addView(barAction("\u2611", "Tout sélectionner") { onBarSelectAll() })
        box.addView(bar)

        statusText = txt(12f, 0xFF8E8E93.toInt()); statusText.setPadding(0, dp(10f), 0, 0)
        box.addView(statusText)

        root.addView(box)
        setContentView(root)
    }

    private var bannerHoldUntil = 0L   // fige le bandeau apres un message important

    private fun logLine(s: String) {
        statusText.text = s
        android.util.Log.i("EyevueAlbum", s)
    }

    /** Compte rendu de la grille : ce qui est importe est compte, pas promis. */
    private fun statusImported(n: Int, restant: Int) {
        val parts = ArrayList<String>()
        if (n > 0) parts.add("$n importe(s) (galerie)")
        if (restant > 0) parts.add("$restant sur les lunettes")
        statusText.text = parts.joinToString(" · ").ifEmpty { "aucun fichier" }
    }

    /** 0x42 = nombre de fichiers PRESENTS SUR L'AP. Mesure du 12-11 : il renvoie
     *  [00 05] alors que les 5 photos sont importees depuis 14:32 (fichiers
     *  presents dans DCIM/100HUNTI, lignes MediaStore is_pending=0). L'AP ne les
     *  efface pas apres import : ce compteur n'est DONC PAS "restant a importer".
     *  Le rendre comme tel ferait annoncer 5 imports a venir sur une paire deja
     *  importee. On l'affiche pour ce qu'il mesure, avec le compte reel de la
     *  galerie. */
    private fun bannerCount(): String {
        // compte sur la liste FUSIONNEE par cle (files.union ne deduplique pas :
        // data class, et le chemin MediaStore n'est pas le chemin du dossier)
        val importedN = mergedFiles().count { thumbs.containsKey(it.key) }
        return when {
            pendingCount < 0 -> "lecture du compteur…"
            pendingCount == 0 && importedN == 0 -> "Aucun fichier à importer"
            pendingCount == 0 -> "AP vide · $importedN dans la galerie"
            importedN == 0 -> "$pendingCount fichiers à importer"
            else -> "$pendingCount fichiers sur les lunettes · $importedN importés"
        }
    }

    /**
     * Grille = galerie (MediaStore + dossier) UNION du listing AP.
     *
     * L'ancienne version dessinait la seule liste du listing AP / du BLE, et
     * effacait tout a la reouverture : "j'ai vu les 5 miniatures, plus rien".
     * Les fichiers importés ne dependent plus de l'AP, qui n'est jointe que
     * pendant un import : un media deja dans la galerie reste affiche, avec son
     * aperu, quoi que renvoie 0x42 (0x42 = nombre de fichiers SUR l'AP, pas le
     * nombre restant a importer -> mesure du 12-11 : 5 apres import des 5).
     */
    private fun mergedFiles(): List<MediaFile> {
        val out = LinkedHashMap<String, MediaFile>()
        // imports deja faits, du plus recent au plus ancien (le listing AP
        // arrive en premier, la galerie comble les trous)
        for (f in files) out[f.key] = f
        for (f in galleryFiles) out[f.key] = f
        // puis ce qui reste sur les lunettes (listing AP / secours BLE)
        for (f in files) out[f.key] = f
        return out.values.sortedBy { -(it.epochSec ?: 0L) }
    }

    private fun refresh() {
        bannerText.text = when {
            importing -> bannerText.text
            android.os.SystemClock.elapsedRealtime() < bannerHoldUntil -> bannerText.text
            else -> bannerCount()
        }
        // Le listing des noms est HTTP, sur l'AP allumee : rien a lire ici hors
        // import. On ne garde les noms BLE qu'en secours si la liste est vide.
        if (bleNames.isNotEmpty() && files.isEmpty())
            files = MediaFile.fromBleNames(bleNames)
        rebuildGrid()
    }

    private fun barAction(glyph: String, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this); col.gravity = Gravity.CENTER
        col.setPadding(dp(14f), 0, dp(14f), 0)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val g = txt(19f); g.text = glyph; g.gravity = Gravity.CENTER
        col.addView(g)
        val l = txt(11f, 0xFFB0B0B6.toInt()); l.text = label; l.gravity = Gravity.CENTER
        l.setPadding(0, dp(2f), 0, 0)
        col.addView(l)
        col.setOnClickListener { onClick() }
        return col
    }

    /** "Tout selectionner" : la case des imports, selon l'onglet affiche. */
    private fun onBarSelectAll() {
        val all = mergedFiles().filter { tab == MediaFile.TAB_ALL || it.tab == tab }
        val allOn = all.isNotEmpty() && all.all { it.checked }
        all.forEach { it.checked = !allOn }
        selectMode = all.any { it.checked }
        statusText.text = if (allOn) "selection retiree"
            else "${all.count { it.checked }} selectionne(s)"
        rebuildGrid()
    }

    private fun onBarDownload() = barOp("telechargement") { f, item ->
        val found = MediaOps.find(this, f.key)
            ?: MediaOps.Found(f.name, item?.uri, item?.path)
        MediaOps.copyToDownloads(this, found)
    }

    /**
     * Barre -> suppression de la selection : telephone (toutes les lignes de la
     * cle) PUIS lunettes. L'ordre est impose : effacer d'abord sur les lunettes
     * laisserait sur le telephone les clones du dedoublonnage MediaStore, sans
     * original a recuperer (mesure du 12-11 : "DH-...407736.jpg" + (1)(2)(3)
     * pour UN fichier AP).
     */
    private fun onBarDelete() = barOp("suppression", apDelete = true) { f, _ ->
        val err = MediaOps.deleteAll(this, f.key)
        if (err != null && MediaOps.findAll(this, f.key).isNotEmpty()) err else null
    }

    /**
     * Operation de barre sur la selection. Les fichiers restes sur les lunettes
     * sont ecartes : ils ne sont pas sur le telephone, les traiter "en reussite"
     * serait une mensonge d'interface. Le resultat est compte a partir des
     * messages que chaque appel a reellement rendus.
     */
    private fun barOp(verb: String, apDelete: Boolean = false,
                      op: (MediaFile, GalleryItem?) -> String?) {
        val sel = mergedFiles().filter { it.checked }
        if (sel.isEmpty()) { statusText.text = "rien de selectionne"; return }
        val targets = sel.filter { thumbs.containsKey(it.key) }
        val skipped = sel.size - targets.size
        if (targets.isEmpty()) {
            statusText.text = "rien a $verb : les $skipped fichier(s) selectionne(s) " +
                "sont sur les lunettes, pas sur le telephone"
            return
        }
        statusText.text = "$verb de ${targets.size} fichier(s)…"
        io.execute {
            var ok = 0
            val done = ArrayList<String>()
            val errs = ArrayList<String>()
            for (f in targets) {
                val err = op(f, thumbs[f.key])
                if (err == null) { ok++; done.add(f.key) } else errs.add(err)
            }
            // Suppression des lunettes, seulement pour ce qui a reellement quitte
            // le telephone (ordre impose, voir ViewerActivity.doDelete). Le nom
            // envoye est celui du LISTING AP, si on l'a : c'est celui que le
            // constructeur concatene, pas le nom local "DH-...".
            var apOk = 0
            val apErrs = ArrayList<String>()
            if (apDelete && done.isNotEmpty()) {
                // phase "listing" : le when() de importListener n'affiche que
                // connexion/importation/listing/echec/aucun ; un libelle "suppression
                // AP" n'aurait rien montre a l'ecran (bandeau muet). Le nom de phase
                // doit donc etre un de ceux que l'ecran sait afficher.
                main.post { importListener.onPhase("listing", "suppression sur les lunettes…") }
                for (k in done) {
                    val listingName = files.firstOrNull { it.key == k }?.name
                        ?: galleryFiles.firstOrNull { it.key == k }?.name
                    if (listingName == null) { apErrs.add("cle $k : nom AP inconnu"); continue }
                    when (val r = wifiImport.deleteOnAp(listingName)) {
                        is WifiImport.ApRes.Ok -> apOk++
                        is WifiImport.ApRes.Err -> apErrs.add("$listingName : ${r.why}")
                    }
                }
            }
            main.post {
                val parts = ArrayList<String>()
                if (ok > 0) parts.add("$ok traitement(s)")
                if (apDelete && apOk > 0) parts.add("$apOk efface(s) des lunettes")
                if (apDelete && apErrs.isNotEmpty()) parts.add("lunettes : ${apErrs.first()}")
                if (skipped > 0) parts.add("$skipped ignore(s) (sur les lunettes)")
                if (errs.isNotEmpty()) parts.add("${errs.size} echec(s) : ${errs.first()}")
                statusText.text = parts.joinToString(" · ")
                // le bandeau ne concerne que la suppression AP : le masquer sur
                // "telechargement" aussi etait un choix de portee abusif
                if (apDelete) bannerBar.visibility = View.GONE
                if (verb == "suppression") {
                    // un media supprime quitte la table d'aperus et la liste de la
                    // galerie : la grille ne doit plus le citer
                    for (k in done) thumbs.remove(k)
                    galleryFiles = galleryFiles.filterNot { it.key in done }
                }
                sel.forEach { it.checked = false }
                rebuildGrid()
                io.execute { scanGallery() }
            }
        }
    }

    /** Clic sur une vignette : le media s'ouvre en grand (ViewerActivity). */
    private fun openViewer(f: MediaFile) {
        val item = thumbs[f.key]
        val i = android.content.Intent(this, ViewerActivity::class.java)
        i.putExtra(ViewerActivity.EXTRA_KEY, f.key)
        i.putExtra(ViewerActivity.EXTRA_NAME, item?.name ?: f.name)
        item?.uri?.let { i.putExtra(ViewerActivity.EXTRA_URI, it) }
        i.putExtra(ViewerActivity.EXTRA_PATH, item?.path)
        startActivityForResult(i, REQ_VIEW)
    }

    override fun onActivityResult(code: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(code, result, data)
        if (code == REQ_VIEW) io.execute { scanGallery() }
    }

    private var galleryFiles: List<MediaFile> = emptyList()

    private fun rebuildGrid() {
        gridBox.removeAllViews()
        val all = mergedFiles()
        val shown = all.filter { tab == MediaFile.TAB_ALL || it.tab == tab }
        val importedN = all.count { thumbs.containsKey(it.key) }
        val restantN = all.size - importedN
        statusImported(importedN, restantN)
        if (shown.isEmpty()) {
            val e = txt(13f, 0xFF8E8E93.toInt())
            e.text = if (all.isEmpty()) "aucun fichier (galerie vide, AP non jointe)"
                else "vide dans cet onglet"
            e.setPadding(0, dp(14f), 0, 0)
            gridBox.addView(e)
            return
        }
        val w = (resources.displayMetrics.widthPixels - dp(24f) - dp(16f)) / 3
        var i = 0
        var lastDay = ""
        while (i < shown.size) {
            val day = shown[i].dayLabel()
            if (day != lastDay) {
                val d = txt(13f, 0xFF9A9AA0.toInt()); d.text = day
                d.setPadding(0, dp(12f), 0, dp(6f))
                gridBox.addView(d)
                lastDay = day
            }
            val row = LinearLayout(this)
            for (k in 0 until 3) {
                if (i + k >= shown.size) break
                row.addView(cell(shown[i + k], w))
                val g = View(this); g.layoutParams = LinearLayout.LayoutParams(dp(8f), 0)
                row.addView(g)
            }
            i += 3
            gridBox.addView(row)
        }
    }

    private fun cell(f: MediaFile, w: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = round(0xFF1C1C1E.toInt(), 10f)
        c.layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
        c.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = round(0xFF2C2C2E.toInt(), 8f)
        img.layoutParams = LinearLayout.LayoutParams(w - dp(12f), w - dp(12f))
        val item = thumbs[f.key]
        if (item != null) {
            c.addView(img)
            io.execute {
                val bmp = decodeSampled(item, w - dp(12f))
                if (bmp != null) main.post { img.setImageBitmap(bmp) }
            }
        } else {
            // fichier restant sur les lunettes : pastille, aucun faux aperu simule
            val ph = txt(24f, 0xFF6D6D72.toInt()); ph.text = "\uD83D\uDCF7"
            ph.gravity = Gravity.CENTER
            ph.background = round(0xFF2C2C2E.toInt(), 8f)
            ph.layoutParams = LinearLayout.LayoutParams(w - dp(12f), w - dp(12f))
            c.addView(ph)
        }
        val name = txt(10f, 0xFFB0B0B6.toInt())
        name.text = f.name.take(18)
        name.setPadding(0, dp(4f), 0, 0)
        c.addView(name)
        val chk = txt(14f); chk.text = if (f.checked) "\u2611" else "\u2610"
        chk.visibility = if (selectMode) View.VISIBLE else View.GONE
        chk.setPadding(0, dp(2f), 0, 0)
        chk.setOnClickListener { f.checked = !f.checked; rebuildGrid() }
        c.addView(chk)
        // Clic sur la vignette : en mode selection elle se coche, sinon le media
        // s'ouvre en grand (demande de l'ecran : "la miniature s'ouvre en grand").
        c.setOnClickListener {
            if (selectMode) { f.checked = !f.checked; rebuildGrid() } else openViewer(f)
        }
        return c
    }

    // thumbPath() a ete supprime : l'association se fait par MediaFile.key
    // (cle canonique, voir keyOf) sur la table thumbs, plus par suffixe de nom.

    /** Decode un aperu depuis l'URI MediaStore (chemin du dossier en secours). */
    private fun decodeSampled(item: GalleryItem, px: Int): android.graphics.Bitmap? {
        val src: () -> java.io.InputStream? = {
            item.uri?.let { contentResolver.openInputStream(it) }
                ?: item.path?.let { java.io.FileInputStream(File(it)) }
        }
        return try {
            val o = android.graphics.BitmapFactory.Options()
            o.inJustDecodeBounds = true
            src()?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / s / 2 >= px && o.outHeight / s / 2 >= px) s *= 2
            val o2 = android.graphics.BitmapFactory.Options()
            o2.inSampleSize = s
            src()?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Exception) {
            logLine("apercu " + item.name + " : " + e.message)
            null
        }
    }
}
