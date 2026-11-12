package com.ridedash.app

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Shows the 6-digit pairing code and asks the user to confirm it matches the other phone. */
object PairingDialog {
    private var dialog: AlertDialog? = null

    fun update(a: AppCompatActivity) {
        val code = RideHub.pairingCode
        if (code == null) {
            detach()
            return
        }
        if (dialog?.isShowing == true || a.isFinishing) return
        val pretty = code.substring(0, 3) + " " + code.substring(3)
        dialog = AlertDialog.Builder(a)
            .setTitle(R.string.pair_title)
            .setMessage(a.getString(R.string.pair_msg, pretty))
            .setCancelable(false)
            .setPositiveButton(R.string.pair_match) { _, _ -> LinkService.confirmPairing(true) }
            .setNegativeButton(R.string.pair_no_match) { _, _ -> LinkService.confirmPairing(false) }
            .show()
    }

    fun detach() {
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null
    }
}
