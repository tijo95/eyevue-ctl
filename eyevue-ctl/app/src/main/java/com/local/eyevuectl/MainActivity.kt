package com.local.eyevuectl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ecran de pilotage, reprise fidele de l'interface de l'app d'origine :
 *   puce appareil "E09-3AA1 +" · reglages + menu · pastille + Connecte + batterie
 *   3 cartes (photo / video / micro) · carte volume
 * Le reste du protocole (statut, luminosite, EQ, capture, medias, langue,
 * frame libre, journal des trames) est depliable sous "Plus de commandes" :
 * il sert a la phase de calibration et ne doit pas gener l'usage courant.
 *
 * PAS D'ELECTROCHROME dans cet ecran, volontairement : le masque de capacites
 * (0x95) d'E09-3AA1 = 0x0000040A ne declare aucun bit electrochrome, les 15 bits
 * definis s'arretent au reglage EQ, et 30 s de gestes sur la branche n'ont produit
 * AUCUNE trame BLE. La teinte des verres est reglee localement par le tactile de la
 * branche, dans le firmware : elle n'est ni lisable ni reglable par BLE. Le 0x37 est
 * conserve uniquement comme APP_PULL_THUMBNAIL_STATUS (vignettes) dans Cmd.kt.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var ble: BleCtl
    private lateinit var eyevue: Eyevue
    private val state = GlassState()
    private var adapter: BluetoothAdapter? = null

    // ---- traduction du texte vu par les lunettes (camera des lunettes, PAS
    //          celle du telephone : la source est 0x22 + l'AP, voir Translate.kt)
    private val main = Handler(Looper.getMainLooper())
    private var tts: Tts? = null
    private var translate: Translate? = null
    /** Langue choisie dans le dialogue ; celle du telephone tant que rien n'est choisi. */
    private var langTag: String = Locale.getDefault().language

    private lateinit var deviceLabel: TextView
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var batteryFill: View
    private lateinit var batteryPctText: TextView
    private lateinit var chargeIcon: TextView
    private lateinit var moreBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var cmdEdit: EditText
    private lateinit var paramEdit: EditText

    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val foundLabels = ArrayList<String>()
    private var deviceAdapter: ArrayAdapter<String>? = null
    private var scanning = false
    private var autoConnect = true
    private var videoOn = false
    private var micOn = false

    /** Les lunettes ont ete vues avec ce nom / ce prefixe MAC : connexion directe. */
    private val preferredName = "E09"
    private val preferredMacPrefix = "C2:01:06"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private fun stamp() = timeFmt.format(Date())

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ensurePermissions()
    }

    override fun onDestroy() {
        try { unregisterReceiver(rxScan) } catch (_: Exception) {}
        BleCtlShared.get(this).listeners.remove(sharedListener)
        translate?.let { BleCtlShared.get(this).listeners.remove(it.bleListener) }
        // shutdown() ferme le proxy A2DP et le service TTS : sans lui le telephone
        // garde une prise A2DP ouverte, ce qui gene l'audio des autres apps.
        tts?.shutdown()
        super.onDestroy()
    }

    /** Le lien est partage avec l'Album : une seule connexion pour les deux ecrans. */
    private val sharedListener = object : BleCtlShared.Listener {
        override fun onState(state: BleCtl.State, detail: String) = listener.onState(state, detail)
        override fun onFrame(rx: Frame.Rx) = listener.onFrame(rx)
    }

    // ------------------------------------------------------------------ permissions

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        if (granted()) openAdapter() else requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) openAdapter()
        else log("permissions refusees : le BLE ne peut pas etre utilise")
    }

    private fun openAdapter() {
        try {
            val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            adapter = mgr?.adapter
            when {
                adapter == null -> log("pas de Bluetooth sur cet appareil")
                !adapter!!.isEnabled -> log("Bluetooth desactive : active-le dans les reglages")
                else -> startScan()          // comme l'app d'origine : elle cherche seule
            }
        } catch (e: SecurityException) {
            log("Bluetooth refuse : $e")
        }
    }

    // ------------------------------------------------------------------ scan + connexion

    private val rxScan = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_FOUND) return
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val name = try { dev.name } catch (e: SecurityException) { null } ?: ""
            if (!found.containsKey(dev.address)) {
                found[dev.address] = dev
                val label = (if (name.isEmpty()) "sans nom" else name) + "   " + dev.address
                foundLabels.add(label)
                deviceAdapter?.notifyDataSetChanged()
                log("trouve: $label")
            }
            // connexion automatique de la device cible, une seule fois
            val cible = name.startsWith(preferredName) || dev.address.startsWith(preferredMacPrefix)
            if (cible && autoConnect && !state.connected) {
                autoConnect = false
                stopScan()
                log("connexion automatique: $name ${dev.address}")
                deviceLabel.text = if (name.isEmpty()) dev.address else name
                ble.connect(dev)
            }
        }
    }

    private val lease = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
            rxScan.onReceive(this@MainActivity, Intent(BluetoothDevice.ACTION_FOUND)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, r.device))
        }
    }

    private fun startScan() {
        val a = adapter ?: return
        if (!granted()) { ensurePermissions(); return }
        if (scanning) return
        try {
            if (Build.VERSION.SDK_INT >= 34)
                registerReceiver(rxScan, IntentFilter(BluetoothDevice.ACTION_FOUND), RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(rxScan, IntentFilter(BluetoothDevice.ACTION_FOUND))
        } catch (_: Exception) {}
        a.bluetoothLeScanner?.startScan(lease)
        scanning = true
        log("scan en cours")
    }

    private fun stopScan() {
        val a = adapter ?: return
        try { a.bluetoothLeScanner?.stopScan(lease) } catch (_: Exception) {}
        scanning = false
        try { unregisterReceiver(rxScan) } catch (_: Exception) {}
    }

    /** Liste des appareils vus : le "+", comme sur la capture. */
    private fun showDevicePicker() {
        if (!scanning) { found.clear(); foundLabels.clear(); startScan() }
        val lv = ListView(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, foundLabels)
        deviceAdapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, pos, _ ->
            val mac = foundLabels[pos].substringAfterLast("   ")
            found[mac]?.let { dev ->
                deviceLabel.text = foundLabels[pos].substringBefore("   ")
                ble.connect(dev)
            }
        }
        AlertDialog.Builder(this).setTitle("Ajouter un appareil").setView(lv)
            .setNegativeButton("Fermer", null).show()
    }

    // ------------------------------------------------------------------ journal / etat

    private fun log(s: String) {
        // logView est une VUE : y toucher depuis un fil secondaire est interdit
        // (CalledFromThreadWrong). Le fil de la traduction (association AP,
        // modele vision, synthese) est justement un fil secondaire : le log est
        // donc poster systematiquement, ce qui est sans effet quand l'appel vient
        // deja du fil UI.
        val line = "${stamp()}  $s\n"
        android.util.Log.i("EyevueCtl", s)
        if (Looper.myLooper() == Looper.getMainLooper()) logView.append(line)
        else main.post { logView.append(line) }
    }

    private fun refreshStatus() {
        dot.background = round(if (state.connected) 0xFF34C759.toInt() else 0xFF8E8E93.toInt(), 20f)
        statusText.text = if (state.connected) "Connect\u00e9" else "D\u00e9connect\u00e9"
        // Aucune tension affichee : 0x17 n'est PAS une tension (voir GlassState.
        // parseLevel0x17, qui explique pourquoi les "volts" calculs sur ces octets
        // etaient faux). Le pourcentage vient des lunettes, 0x53 en priorite, 0x17
        // en repli, et la source est signalee a l'ecran.
        val b = state.batteryPct
        batteryPctText.text = when {
            b != null && state.batteryFrom0x17 -> "$b%  (0x17)"
            b != null -> "$b%"
            state.connected -> "niveau non recu"
            else -> ""
        }
        batteryFill.visibility = if (b == null) View.INVISIBLE else View.VISIBLE
        val w = (dp(20f) * (b ?: 0) / 100).coerceAtLeast(0)
        if (batteryFill.layoutParams.width != w) {
            batteryFill.layoutParams.width = w
            batteryFill.requestLayout()      // sans ceci la barre ne se redessine pas
        }
        chargeIcon.text = if (state.charging == true) "\u26a1" else ""
    }

    private val listener = object : BleCtl.Listener {
        override fun onState(s: BleCtl.State, detail: String) {
            state.connected = (s == BleCtl.State.CONNECTED)
            log("etat $s ${if (detail.isEmpty()) "" else "($detail)"}".trim())
            if (s == BleCtl.State.CONNECTED) {
                eyevue.requestStatus()
                eyevue.getFirmware()
            }
            refreshStatus()
        }

        override fun onFrame(rx: Frame.Rx) {
            val changed = state.apply(rx)
            val extra = if (rx.cmd == Cmd.ACTION_SYNC) "  " + Gesture.describe(rx.payload) else ""
            log("< ${rx}${if (extra.isEmpty()) "" else extra}  [$changed]")
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ style

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()   // cornerRadius est en pixels (Float)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(0xFF1C1C1E.toInt(), 18f)
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
    }

    private fun txt(sizeSp: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { textSize = sizeSp; setTextColor(color) }

    private fun iconBtn(glyph: String, sizeDp: Float = 44f, action: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph; textSize = 16f; setTextColor(0xFFD0D0D0.toInt())
            gravity = Gravity.CENTER
            background = round(0xFF1C1C1E.toInt(), 22f)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setOnClickListener { action() }
        }

    private fun actionCard(label: String, glyph: String, weight: Float, action: () -> Unit): LinearLayout {
        val c = card()
        c.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        val ic = txt(22f); ic.text = glyph; ic.gravity = Gravity.CENTER
        ic.setPadding(0, 0, 0, dp(10f))
        val lb = txt(13f, 0xFF9A9AA0.toInt()); lb.text = label; lb.gravity = Gravity.CENTER
        c.addView(ic); c.addView(lb)
        c.setOnClickListener {
            try { action() } catch (e: Exception) { log("erreur: $e") }
        }
        return c
    }

    // ------------------------------------------------------------------ construction

    private fun buildUi() {
        val shared = BleCtlShared.get(this)
        ble = shared.ble
        eyevue = shared.eyevue
        shared.listeners.add(sharedListener)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16f), dp(24f), dp(16f), dp(16f))

        // --- barre du haut : puce appareil + reglages + menu
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(0xFF1C1C1E.toInt(), 22f)
            setPadding(dp(16f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        deviceLabel = txt(16f); deviceLabel.text = "E09-3AA1"
        val plus = txt(18f, 0xFF9A9AA0.toInt()); plus.text = "  +  "
        plus.setOnClickListener { showDevicePicker() }
        chip.addView(deviceLabel); chip.addView(plus)
        top.addView(chip)
        val sp = View(this); sp.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        top.addView(sp)
        top.addView(iconBtn("\u2699") { showSettings() })
        val gap = View(this); gap.layoutParams = LinearLayout.LayoutParams(dp(10f), 0)
        top.addView(gap)
        top.addView(iconBtn("\u2630") { toggleLog() })
        box.addView(top)

        // --- ligne d'etat : pastille + Connecte + batterie
        val st = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20f), 0, dp(16f))
        }
        dot = View(this).apply {
            background = round(0xFF8E8E93.toInt(), 20f)
            layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f))
        }
        st.addView(dot)
        statusText = txt(15f); statusText.text = "D\u00e9connect\u00e9"
        statusText.setPadding(dp(8f), 0, dp(16f), 0)
        st.addView(statusText)
        val shell = LinearLayout(this).apply {
            background = round(0xFF3A3A3C.toInt(), 5f)
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(13f))
        }
        batteryFill = View(this).apply {
            background = round(0xFF34C759.toInt(), 4f)
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        shell.addView(batteryFill)
        st.addView(shell)
        batteryPctText = txt(15f); batteryPctText.setPadding(dp(8f), 0, 0, 0)
        st.addView(batteryPctText)
        chargeIcon = txt(15f, 0xFF34C759.toInt()); chargeIcon.setPadding(dp(6f), 0, 0, 0)
        st.addView(chargeIcon)
        box.addView(st)

        // --- 3 cartes : photo / video / micro
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(actionCard("Prendre une photo", "\ud83d\udcf7", 1f) {
            log("photo (vignette)"); eyevue.takePhoto(false) })
        val g1 = View(this); g1.layoutParams = LinearLayout.LayoutParams(dp(10f), 0); row.addView(g1)
        row.addView(actionCard("Enregistrer une vid\u00e9o", "\ud83d\udcf9", 1f) {
            if (videoOn) { eyevue.stopRecord(); log("video: arret") }
            else { eyevue.startVideo(); log("video: demarrage") }
            videoOn = !videoOn })
        val g2 = View(this); g2.layoutParams = LinearLayout.LayoutParams(dp(10f), 0); row.addView(g2)
        row.addView(actionCard("enregistrer", "\ud83c\udfa4", 1f) {
            if (micOn) { eyevue.stopMic(); log("micro: arret") }
            else { eyevue.startMic(); log("micro: demarrage") }
            micOn = !micOn })
        box.addView(row)

        // --- carte Album (ouvre AlbumActivity ; le compteur "N fichiers a
        //     importer" vient de la trame 0x42, see AlbumActivity)
        val album = actionCard("Album", "\ud83d\udcc8", 1f) {
            startActivity(android.content.Intent(this, AlbumActivity::class.java))
        }
        val al = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        al.topMargin = dp(10f)
        album.layoutParams = al
        box.addView(album)

        // --- carte Traduire le texte. UN APPUI, pas de dialogue a chaque fois :
        //     declencher la prise de vue (BLE 0x22 en haute qualite), aller
        //     chercher la pleine resolution par l'AP, la traduire, la lire. Les
        //     reglages (serveur, langue, voix) sont depliables plus bas.
        val tr = actionCard("Traduire le texte", "\ud83d\udd24", 1f) { startTranslate() }
        val tl = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tl.topMargin = dp(10f)
        tr.layoutParams = tl
        box.addView(tr)

        // --- carte volume
        val vol = actionCard("R\u00e9glage du volume", "\ud83d\udd0a", 1f) { showVolume() }
        val vl = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        vl.topMargin = dp(10f)
        vol.layoutParams = vl
        box.addView(vol)

        // --- reste du protocole, depliable
        val more = txt(14f, 0xFF9A9AA0.toInt())
        more.text = "Plus de commandes  \u25be"
        more.setPadding(dp(4f), dp(20f), 0, dp(8f))
        box.addView(more)
        moreBox = LinearLayout(this)
        moreBox.orientation = LinearLayout.VERTICAL
        moreBox.visibility = View.GONE
        box.addView(moreBox)

        addSection(moreBox, "traduction du texte",
            "Reglages serveur IA + voix" to { showTranslateSettings() },
            "Arreter la lecture" to { translate?.stopSpeaking() })
        more.setOnClickListener {
            moreBox.visibility = if (moreBox.visibility == View.GONE) View.VISIBLE else View.GONE
            more.text = if (moreBox.visibility == View.VISIBLE)
                "Moins de commandes  \u25b4" else "Plus de commandes  \u25be"
        }
        buildSections()
        buildFreeFrame(box)

        // --- journal
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logBox.visibility = View.GONE
        box.addView(logBox)
        val lt = txt(13f, 0xFF9A9AA0.toInt()); lt.text = "journal des trames"
        lt.setPadding(dp(4f), dp(16f), 0, dp(4f))
        logBox.addView(lt)
        logView = txt(11f, 0xFFB0B0B6.toInt())
        logView.movementMethod = ScrollingMovementMethod()
        logView.setTextIsSelectable(true)
        logView.background = round(0xFF141416.toInt(), 10f)
        logView.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        logView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(280f))
        logBox.addView(logView)
        val clr = Button(this); clr.text = "Effacer le journal"; clr.isAllCaps = false
        clr.setOnClickListener { logView.text = "" }
        logBox.addView(clr)

        scroll.addView(box)
        setContentView(scroll)
        refreshStatus()
    }

    private fun toggleLog() {
        logBox.visibility = if (logBox.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    /** Boutons alignes par 3, dans un conteneur donne. */
    private fun addSection(box: LinearLayout, title: String, vararg cells: Pair<String, () -> Unit>) {
        val t = txt(13f, 0xFF9A9AA0.toInt()); t.text = title
        t.setPadding(dp(4f), dp(14f), dp(4f), dp(4f))
        box.addView(t)
        var line: LinearLayout? = null
        cells.forEachIndexed { i, (label, action) ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                box.addView(line)
            }
            val b = Button(this)
            b.text = label; b.isAllCaps = false; b.textSize = 12f; b.isSingleLine = true
            b.setTextColor(Color.WHITE)
            b.background = round(0xFF2A2A2E.toInt(), 12f)
            b.setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            b.setOnClickListener { try { action() } catch (e: Exception) { log("erreur: $e") } }
            line!!.addView(b)
        }
    }

    private fun buildSections() {
        addSection(moreBox, "statut",
            "Batterie" to { ble.send(Cmd.GET_BATTERY) },
            "Stockage" to { ble.send(Cmd.GET_CAPACITY) },
            "Statut complet" to { eyevue.requestStatus() },
            "Firmware" to { eyevue.getFirmware() },
            "Capacites modele" to { eyevue.getSupport() },
            "Equation" to { ble.send(Cmd.GET_EQ) })
        addSection(moreBox, "affichage / lunettes",
            "Lumiere basse" to { eyevue.setBrightness(PBrightness.Low) },
            "Lumiere moyenne" to { eyevue.setBrightness(PBrightness.Mid) },
            "Lumiere haute" to { eyevue.setBrightness(PBrightness.High) },
            "Port ON" to { eyevue.setWearDetect(true) },
            "Port OFF" to { eyevue.setWearDetect(false) })
        addSection(moreBox, "video : duree",
            "1 min" to { eyevue.setRecordDuration(1) },
            "3 min" to { eyevue.setRecordDuration(3) },
            "5 min" to { eyevue.setRecordDuration(5) },
            "7 min" to { eyevue.setRecordDuration(7) },
            "10 min" to { eyevue.setRecordDuration(10) })
        addSection(moreBox, "audio (casque BT)",
            "Play/Pause" to { eyevue.playPause() },
            "Lecture" to { eyevue.play() },
            "Titre prec." to { eyevue.trackPrev() },
            "Titre suiv." to { eyevue.trackNext() },
            "Decrocher" to { eyevue.callAnswer() },
            "Raccrocher" to { eyevue.callHangup() })
        addSection(moreBox, "equaliseur",
            "EQ normal" to { eyevue.setEq(PSetEq.Standard) },
            "EQ rock" to { eyevue.setEq(PSetEq.Rock) },
            "EQ pop" to { eyevue.setEq(PSetEq.Pop) },
            "EQ classique" to { eyevue.setEq(PSetEq.Classical) },
            "EQ jazz" to { eyevue.setEq(PSetEq.Jazz) },
            "EQ custom" to { eyevue.setEq(PSetEq.Custom) })
        addSection(moreBox, "capture (mode / style / orientation)",
            "Mode standard" to { eyevue.setCaptureMode(true) },
            "Mode classique" to { eyevue.setCaptureMode(false) },
            "Ratio 16:9" to { eyevue.setCaptureStyle(true) },
            "Ratio 4:3" to { eyevue.setCaptureStyle(false) },
            "Orient. vertical" to { eyevue.setCaptureOrientation(true) },
            "Orient. horizontal" to { eyevue.setCaptureOrientation(false) })
        addSection(moreBox, "medias + langue + micro",
            "Tirer la photo" to { eyevue.pullPhoto() },
            "Nb vignettes" to { eyevue.pullThumbnailCount() },
            "Etat vignette" to { eyevue.pullThumbnailStatus() },
            "Langue EN" to { eyevue.setOfflineLanguage(true) },
            "Langue ZH" to { eyevue.setOfflineLanguage(false) },
            "Micro (valeur connue)" to { eyevue.setMic() },
            "Sync horloge" to { eyevue.syncTime() })
    }

    private fun buildFreeFrame(box: LinearLayout) {
        val t = txt(13f, 0xFF9A9AA0.toInt())
        t.text = "Frame libre (calibration) : cmd + param en hex"
        t.setPadding(dp(4f), dp(18f), dp(4f), dp(4f))
        box.addView(t)
        val free = LinearLayout(this)
        cmdEdit = EditText(this); cmdEdit.hint = "cmd (70)"; cmdEdit.textSize = 13f
        paramEdit = EditText(this); paramEdit.hint = "param (31)"; paramEdit.textSize = 13f
        val send = Button(this); send.text = "Envoyer"; send.isAllCaps = false
        send.setOnClickListener { sendFree() }
        cmdEdit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        paramEdit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        free.addView(cmdEdit); free.addView(paramEdit); free.addView(send)
        box.addView(free)
    }

    private fun sendFree() {
        val cmd = cmdEdit.text.toString().trim().removePrefix("0x").toIntOrNull(16)
        if (cmd == null) { log("cmd hexe invalide"); return }
        val p = paramEdit.text.toString().trim().removePrefix("0x")
        val param = if (p.isEmpty()) null else p.toIntOrNull(16)
        if (p.isNotEmpty() && param == null) { log("param hexe invalide"); return }
        ble.send(cmd, param)
        log("> ${Cmd.nameOf(cmd)} (${"0x%02X".format(cmd)}) " +
            "param=${param?.let { "0x%02X".format(it) } ?: "aucun"} " +
            "trame=" + Frame.build(cmd, param).joinToString(" ") { "%02X".format(it) })
    }

    // ------------------------------------------------------------------ dialogues

    // ------------------------------------------------------------------ traduction

    /** Ecoute Wi-Fi de la chaine de traduction (phases de la capture photo). */
    private val translateWifiListener = object : WifiImport.Listener {
        override fun onPhase(phase: String, detail: String) = log("$phase : $detail")
        override fun onProgress(done: Int, total: Int, filePct: Int) {
            if (filePct >= 0) log("AP : telechargement ${filePct}%")
        }
        override fun onFileDone(f: MediaFile) = log("photo recuperee : ${f.name}")
        override fun onFinished(okCount: Int, failed: List<String>) {}
        override fun onLog(s: String) = log(s)
    }

    private fun prefs() = getSharedPreferences("eyevue", MODE_PRIVATE)

    /**
     * Tts et Translate sont crees UNE fois : TextToSpeech lie un service du
     * systeme, le recreer a chaque appui ferait fuiter la liaison, et l'ecoute
     * BLE (0x42) s'empilerait dans BleCtlShared.listeners.
     */
    private fun ensureTranslate(): Translate {
        translate?.let { return it }
        val shared = BleCtlShared.get(this)
        val t = Tts(this)
        tts = t
        val w = WifiImport(this, prefs(), translateWifiListener)
        val tr = Translate(this, prefs(), shared, w, t, { log(it) })
        translate = tr
        shared.listeners.add(tr.bleListener)
        t.init(object : Tts.Listener {
            override fun onLog(s: String) = log(s)
            override fun onReady(available: Boolean, why: String) {
                log(if (available) "synthese vocale prete" else "synthese vocale indisponible : $why")
            }
            override fun onSpeaking(speaking: Boolean) {
                log(if (speaking) "lecture en cours…" else "lecture terminee")
            }
        })
        return tr
    }

    /**
     * Le bouton. Il ne demande rien a l'ecran : la langue est celle deja choisie.
     * Si le serveur n'est pas configure, il ouvre les reglages au lieu d'echouer
     * avec une erreur qui ne dit pas ce qui manque.
     */
    private fun startTranslate() {
        val tr = ensureTranslate()
        val p = prefs()
        val url = p.getString(Vision.PREF_URL, "") ?: ""
        val model = p.getString(Vision.PREF_MODEL, "") ?: ""
        if (url.isBlank() || model.isBlank()) {
            log("serveur IA incomplet (URL " + (if (url.isBlank()) "vide" else "renseignee") +
                ", modele " + (if (model.isBlank()) "vide" else "renseigne") + ") : reglages ouverts")
            showTranslateSettings()
            return
        }
        log("traduction lancee — langue « $langTag » ; ${tr.whereVoiceGoes()}")
        tr.captureTranslateSpeak(langTag)
    }

    /**
     * Reglages : langue, serveur, voix. L'etat de la synthese vocale est AFFICHE
     * avec ses codes reels : un echec de TextToSpeech est muet (onInit renvoie un
     * code, ne leve aucune exception), donc sans cet etat a l'ecran un bouton qui
     * ne dit rien reste incomprehensible.
     */
    private fun showTranslateSettings() {
        val tr = ensureTranslate()
        val p = prefs()
        val sc = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        // --- langue : sert a la traduction ET a la voix. Les deux sont distinctes
        //     dans le moteur (un modele traduit dans presque toute langue ; un TTS
        //     n'a que les voix installees), d'ou le status affiche au changement.
        val lt = txt(13f, 0xFF9A9AA0.toInt())
        lt.text = "LANGUE DE TRADUCTION (aussi celle que la voix lira)"
        lt.setPadding(dp(4f), dp(10f), dp(4f), dp(4f))
        box.addView(lt)
        val sp = Spinner(this)
        val labels = Tts.LANGS.map { it.first }
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val at = Tts.LANGS.indexOfFirst { it.third.equals(langTag, ignoreCase = true) }
        if (at >= 0) sp.setSelection(at)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                                        pos: Int, id: Long) {
                langTag = Tts.LANGS[pos].third
                p.edit().putString(Vision.PREF_LANG, langTag).apply()
                log("langue « ${Tts.LANGS[pos].first} » (tag $langTag) — voix : " +
                    (tts?.voiceStatus(Tts.LANGS[pos].second) ?: "moteur non init"))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        box.addView(sp)

        val loc = Tts.langByTag(langTag)?.second ?: Locale.getDefault()
        val vt = txt(13f)
        vt.setPadding(0, dp(12f), 0, 0)
        vt.text = "SYNTHESE VOCALE : " + (if (tr.ttsReady) "MOTEUR PRET" else "MOTEUR NON PRET") +
            "\n  " + (tts?.initError?.takeIf { it.isNotEmpty() } ?: "aucune erreur d'init") +
            "\n  voix « $langTag » : " + (tts?.voiceStatus(loc) ?: "moteur non init") +
            "\n  " + tr.whereVoiceGoes() +
            "\n\nUn moteur installe mais JAMAIS SELECTIONNE (tts_default_synth = null) " +
            "donne un bouton muet : le regler ici mene, ou dans Systeme > Langue et " +
            "saisie > Sortie de la synthese vocale."
        box.addView(vt)
        addSection(box, "voix",
            "Reglages TTS" to { tr.openTtsSettings() },
            "Installer les voix" to { tr.openVoiceInstall() },
            "Arreter la lecture" to { tr.stopSpeaking() })

        // --- serveur. L'URL n'est JAMAIS codee en dur : ce depot est public, une
        //     IP locale ne doit pas s'y trouver. Elle est saisie, normalisee, stockee.
        val lt2 = txt(13f, 0xFF9A9AA0.toInt())
        lt2.text = "SERVEUR IA LOCAL (llama.cpp, base /v1)"
        lt2.setPadding(dp(4f), dp(14f), dp(4f), dp(4f))
        box.addView(lt2)
        val urlEdit = EditText(this); urlEdit.hint = "http://ADRESSE:8080"; urlEdit.textSize = 13f
        urlEdit.setText(p.getString(Vision.PREF_URL, ""))
        val modelEdit = EditText(this); modelEdit.hint = "nom du modele (voir Lister)"; modelEdit.textSize = 13f
        modelEdit.setText(p.getString(Vision.PREF_MODEL, ""))
        val keyEdit = EditText(this); keyEdit.hint = "cle API (vide si le serveur n'en exige pas)"; keyEdit.textSize = 13f
        keyEdit.setText(p.getString(Vision.PREF_KEY, ""))
        val promptEdit = EditText(this); promptEdit.textSize = 12f
        promptEdit.setText(p.getString(Vision.PREF_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: Vision.DEFAULT_PROMPT)
        for (e in listOf(urlEdit, modelEdit, keyEdit, promptEdit)) {
            e.setTextColor(Color.WHITE); e.setPadding(0, dp(6f), 0, dp(6f)); box.addView(e)
        }
        val speakCb = CheckBox(this)
        speakCb.text = "lire la traduction a voix haute"
        speakCb.setTextColor(Color.WHITE)
        speakCb.isChecked = p.getBoolean(Vision.PREF_AUTO, true)
        box.addView(speakCb)

        val save = Button(this); save.text = "Enregistrer"; save.isAllCaps = false
        save.setOnClickListener {
            p.edit()
                .putString(Vision.PREF_URL, Vision.normalizeBase(urlEdit.text.toString()))
                .putString(Vision.PREF_MODEL, modelEdit.text.toString().trim())
                .putString(Vision.PREF_KEY, keyEdit.text.toString().trim())
                .putString(Vision.PREF_PROMPT, promptEdit.text.toString())
                .putBoolean(Vision.PREF_AUTO, speakCb.isChecked)
                .apply()
            log("reglages IA enregistres — URL normalisee : " + (p.getString(Vision.PREF_URL, "") ?: "") +
                " ; ne collez pas /v1, le client l'ajoute lui-meme")
        }
        box.addView(save)
        val probe = Button(this); probe.text = "Sonder le serveur (/health)"; probe.isAllCaps = false
        probe.setOnClickListener { tr.probeServer() }
        box.addView(probe)
        val models = Button(this); models.text = "Lister les modeles charges"; models.isAllCaps = false
        models.setOnClickListener {
            log("lecture de /v1/models…")
            tr.listModels { list ->
                if (list.isEmpty()) { log("aucun modele liste : serveur injoignable ?"); return@listModels }
                val arr = list.toTypedArray()
                AlertDialog.Builder(this).setTitle("Modeles charges")
                    .setItems(arr) { _, i ->
                        modelEdit.setText(arr[i]); log("modele choisi : ${arr[i]}") }
                    .show()
            }
        }
        box.addView(models)

        sc.addView(box)
        AlertDialog.Builder(this).setTitle("Traduire le texte — reglages")
            .setView(sc).setNegativeButton("Fermer", null).create().show()
    }

    private fun showVolume() {
        val cells = listOf(
            "Volume HAUT (0x70 / 0x31)" to { eyevue.setVolumeHigh() },
            "Volume BAS (0x70 / 0x30)" to { eyevue.setVolumeLow() },
            "Volume + (0x32 / 0x01)" to { eyevue.setVolumeDelta(true) },
            "Volume - (0x32 / 0x00)" to { eyevue.setVolumeDelta(false) })
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        for ((label, act) in cells) {
            val b = Button(this); b.text = label; b.isAllCaps = false
            b.setOnClickListener {
                try { act() } catch (e: Exception) { log("erreur: $e") }
            }
            box.addView(b)
        }
        AlertDialog.Builder(this).setTitle("Volume").setView(box)
            .setNegativeButton("Fermer", null).show()
    }

    private fun showSettings() {
        val sc = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        addSection(box, "statut",
            "Batterie" to { ble.send(Cmd.GET_BATTERY) },
            "Statut complet" to { eyevue.requestStatus() },
            "Firmware" to { eyevue.getFirmware() })
        addSection(box, "affichage",
            "Lumiere basse" to { eyevue.setBrightness(PBrightness.Low) },
            "Lumiere moyenne" to { eyevue.setBrightness(PBrightness.Mid) },
            "Lumiere haute" to { eyevue.setBrightness(PBrightness.High) },
            "Verifier les capacites (0x95)" to { eyevue.getSupport() })
        // Liste des capacites annoncees par le modele (trame 0x95)
        val cap = txt(13f)
        cap.setPadding(0, dp(12f), 0, 0)
        val mask = state.capabilities
        cap.text = if (mask == null) "Capacites: pas encore recues (0x95)"
        else "Capacites (masque 0x%08X):\n".format(mask) +
            GlassState.capabilityNames(mask).joinToString("\n") { "  \u2713 $it" } +
            "\n\nAucune capacite ne declare le verre electrochrome : la teinte est " +
            "reglee par le tactile de la branche, dans le firmware. Les reglages de " +
            "l'app ne concernent donc que ce qui est annoncee ici."
        box.addView(cap)
        val d = AlertDialog.Builder(this).setTitle("Reglages").setView(sc)
            .setNegativeButton("Fermer", null).create()
        sc.addView(box)
        d.show()
    }
}
