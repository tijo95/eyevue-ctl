package com.local.eyevuectl

import android.annotation.SuppressLint
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instance unique du lien BLE, partagee entre l'ecran de pilotage et l'Album :
 * l'Album doit entendre les memes trames (0x42 compteur, 0x25 SSID, 0x44),
 * et l'appairage n'a pas a etre defait quand on change d'ecran.
 *
 * BleCtl n'a qu'un seul slot de listener : le fan-out est ici, en liste.
 */
@SuppressLint("MissingPermission")
class BleCtlShared private constructor(ctx: Context) {

    interface Listener {
        fun onFrame(rx: Frame.Rx) {}
        fun onState(state: BleCtl.State, detail: String) {}
    }

    val ble: BleCtl = BleCtl(ctx.applicationContext)
    val eyevue: Eyevue = Eyevue(ble)
    val listeners = CopyOnWriteArrayList<Listener>()

    private val fanout = object : BleCtl.Listener {
        override fun onState(state: BleCtl.State, detail: String) {
            listeners.forEach { it.onState(state, detail) }
        }
        override fun onFrame(rx: Frame.Rx) {
            listeners.forEach { it.onFrame(rx) }
        }
    }

    companion object {
        @Volatile private var instance: BleCtlShared? = null

        fun get(ctx: Context): BleCtlShared {
            instance?.let { return it }
            synchronized(this) {
                val i = instance ?: BleCtlShared(ctx).also { inst ->
                    inst.ble.setListener(inst.fanout)
                    instance = inst
                }
                return i
            }
        }
    }
}
