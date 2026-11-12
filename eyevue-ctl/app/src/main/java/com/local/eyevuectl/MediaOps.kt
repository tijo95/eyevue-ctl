package com.local.eyevuectl

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Operations sur les medias DEJA imports (galerie) : suppression et copie vers
 * Telechargements, demandes de l'ecran (barre du bas + visualiseur).
 *
 * Localisation par cle canonique (MediaFile.keyOf) : le nom que connait l'ecran
 * vient du listing AP ("20261111204012731.jpg") ou du nom affiche par la galerie
 * ("DH-261111-204012731-20261111204012731.jpg", capture du 12-11 16:51) ; la cle
 * est la plus longue suite de chiffres, commune aux deux.
 *
 * Suppression : les lignes MediaStore sont creees PAR NOUS (WifiImport.publish,
 * insert + IS_PENDING) donc notre paquet en est proprietaire : sur Android 10+
 * contentResolver.delete() est autorise sur ses propres lignes sans dialogue.
 *
 * Convention de retour : null = reussi, String = message d'echec. Rien n'est
 * annonce reussi sans que l'appel ait rendu ce resultat.
 */
object MediaOps {

    private const val TAG = "EyevueMedia"

    class Found(val name: String, val uri: Uri?, val path: String?)

    private val EXTS = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    private val PROJ = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)

    private fun galleryDir() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "100HUNTI")

    /** Retrouve un media importe par sa cle : MediaStore d'abord, dossier secours. */
    fun find(ctx: Context, key: String): Found? {
        if (key.isEmpty()) return null
        for (ext in EXTS) {
            try {
                ctx.contentResolver.query(ext, PROJ, "_display_name LIKE ?",
                    arrayOf("%" + key + "%"), null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(iName) ?: continue
                        return Found(name, ContentUris.withAppendedId(ext, c.getLong(iId)), null)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "find MediaStore: " + e.message) }
        }
        try {
            galleryDir().listFiles()?.forEach { f ->
                if (f.isFile && MediaFile.keyOf(f.name) == key)
                    return Found(f.name, null, f.absolutePath)
            }
        } catch (e: Exception) { Log.w(TAG, "find dossier: " + e.message) }
        return null
    }

    /**
     * TOUTES les lignes/medias d'une cle.
     *
     * indispensable, mesure sur piece reelle du 12-11 : DCIM/100HUNTI contenait
     *   DH-251201-204012731-20261111204012731.jpg  (et ses (1) (2) (3))
     * soit 4 fichiers telephone pour UN fichier des lunettes : MediaStore
     * dedouble a l'insert (suffixe " (1)") a chaque relance d'import. find()
     * s'arrete au premier match, donc supprimer ne retirait qu'UNE ligne sur
     * quatre, rendait null (succès) et le re-scan faisait ressurgir la vignette.
     * La cle est partagee par tous ces fichiers : c'est pourquoi la suppression
     * doit etre iteree sur la liste rendue ici.
     */
    fun findAll(ctx: Context, key: String): List<Found> {
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Found>()
        for (ext in EXTS) {
            try {
                ctx.contentResolver.query(ext, PROJ, "_display_name LIKE ?",
                    arrayOf("%" + key + "%"), null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(iName) ?: continue
                        // Le LIKE est large (il prend "(1)", "(2)"...) : on ne
                        // retient que les noms dont la cle EST la cle cherchee.
                        if (MediaFile.keyOf(name) != key) continue
                        out.add(Found(name, ContentUris.withAppendedId(ext, c.getLong(iId)), null))
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "findAll MediaStore: " + e.message) }
        }
        try {
            galleryDir().listFiles()?.forEach { f ->
                if (f.isFile && MediaFile.keyOf(f.name) == key)
                    out.add(Found(f.name, null, f.absolutePath))
            }
        } catch (e: Exception) { Log.w(TAG, "findAll dossier: " + e.message) }
        return out
    }

    /**
     * Supprime TOUT ce qui porte la cle : lignes MediaStore + fichiers du
     * dossier secours. Rend null si la cle ne laisse plus rien, sinon un message
     * qui cite ce qui reste (jamais de succes affiche sur un residu).
     */
    fun deleteAll(ctx: Context, key: String): String? {
        val all = findAll(ctx, key)
        if (all.isEmpty()) return "introuvable dans la galerie (cle $key)"
        var rows = 0
        val left = ArrayList<String>()
        for (f in all) deleteOne(ctx, f)?.let { left.add(f.name) }
        // Controle, pas de foi dans les compteurs : ce qui porte encore la cle
        // apres les suppressions est la liste de ce qui reste.
        for (r in findAll(ctx, key)) if (!left.contains(r.name)) left.add(r.name)
        rows = all.size - left.size
        Log.i(TAG, "deleteAll cle $key : $rows media(s) retire(s), reste=${left.size}")
        return if (left.isEmpty()) null
        else "suppression incomplete : ${left.size} reste(nt) [${left.first()}]"
    }

    /** Retire la ligne MediaStore (le fichier part avec) et, secours, le fichier. */
    fun deleteOne(ctx: Context, f: Found): String? = try {
        var rows = 0
        if (f.uri != null) rows = ctx.contentResolver.delete(f.uri, null, null)
        val file = File(galleryDir(), f.name)
        val fileGone = !file.exists() || file.delete()
        Log.i(TAG, "delete " + f.name + " : lignes MediaStore=" + rows + " fichierSupprime=" + fileGone)
        if (rows > 0 || fileGone) null
        else "suppression refusee : 0 ligne MediaStore retiree et fichier encore present"
    } catch (e: Exception) {
        "suppression refusee : " + (e.message ?: e.javaClass.simpleName)
    }

    /**
     * Copie le media vers Telechargements/Eyevue (le "Telecharger" de l'ecran).
     *
     * Garde d'API identique a WifiImport.publish : MediaStore.Downloads,
     * RELATIVE_PATH et IS_PENDING n'existent qu'a partir de API 29 alors que
     * minSdkVersion est 23. Sans ce garde, un telphone en Android 6/7/8 casse
     * sur NoSuchFieldError des le clic. Les API 23-28 passent donc par
     * l'ecriture du dossier public (WRITE_EXTERNAL_STORAGE, declaree maxSdk 28).
     */
    fun copyToDownloads(ctx: Context, f: Found): String? {
        val src: java.io.InputStream? =
            f.uri?.let { ctx.contentResolver.openInputStream(it) }
                ?: f.path?.let { if (File(it).isFile) FileInputStream(File(it)) else null }
        if (src == null) return "source illisible : " + f.name
        return if (Build.VERSION.SDK_INT >= 29) viaMediaStore(ctx, f, src) else viaFile(ctx, f, src)
    }

    private fun viaMediaStore(ctx: Context, f: Found, src: java.io.InputStream): String? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(f.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Eyevue")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: return "ecriture Telechargements refusee"
        src.use { input ->
            val out = ctx.contentResolver.openOutputStream(uri)
            if (out == null) {
                ctx.contentResolver.delete(uri, null, null)
                return "ouverture Telechargements refusee"
            }
            out.use { o ->
                val n = input.copyTo(o)
                v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, v, null, null)
                Log.i(TAG, "telecharge " + f.name + " -> Download/Eyevue (" + n + " octets)")
            }
        }
        return null
    }

    /** API 23-28 : ecriture du dossier public + declaration a MediaStore. */
    @Suppress("DEPRECATION")
    private fun viaFile(ctx: Context, f: Found, src: java.io.InputStream): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "Eyevue")
        if (!dir.exists() && !dir.mkdirs()) return "creation Download/Eyevue refusee"
        val dst = File(dir, f.name)
        return try {
            src.use { input -> dst.outputStream().use { input.copyTo(it) } }
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                .setData(Uri.fromFile(dst)))
            Log.i(TAG, "telecharge " + f.name + " -> " + dst.absolutePath + " (" + dst.length() + " octets)")
            null
        } catch (e: Exception) {
            dst.delete()
            "ecriture Download refusee : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png" -> "image/jpeg"
        "mp4", "3gp", "mov", "avi" -> "video/mp4"
        "aac", "wav", "mp3" -> "audio/aac"
        else -> "application/octet-stream"
    }
}
