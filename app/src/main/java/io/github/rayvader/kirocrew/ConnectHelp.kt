package io.github.rayvader.kirocrew

import android.content.Context
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The "How to connect" guide, shown from the welcome screen, the Servers screen and the Add server box. */
object ConnectHelp {
    fun show(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.connect_help)
            .setMessage(
                HtmlCompat.fromHtml(context.getString(R.string.connect_help_body), HtmlCompat.FROM_HTML_MODE_LEGACY)
            )
            .setPositiveButton(R.string.got_it, null)
            .show()
    }
}
