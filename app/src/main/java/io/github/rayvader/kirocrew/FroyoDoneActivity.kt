package io.github.rayvader.kirocrew

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlin.concurrent.thread

/**
 * The widget's Done chip: asks first, then hides the row and offers Undo for a few
 * seconds before the done goes to Froyo. With app lock on, it asks to unlock first.
 */
class FroyoDoneActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private var title: String = ""
    private var marked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isBlank()) {
            finish()
            return
        }
        root = FrameLayout(this)
        setContentView(root)
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (Prefs(this).appLock && AppLock.canUse(this)) {
            AppLock(this, onUnlocked = { confirm() }, onFailed = { finish() }).show()
        } else {
            confirm()
        }
    }

    private fun confirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.froyo_done_confirm, title))
            .setPositiveButton(R.string.froyo_done_yes) { _, _ -> markDone() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!marked) finish() }
            .show()
    }

    private fun markDone() {
        marked = true
        FroyoDone.markDone(applicationContext, title)
        val bar = Snackbar.make(root, getString(R.string.froyo_marked_done, title), FroyoDone.UNDO_MS.toInt())
        bar.setAction(R.string.froyo_undo) {
            val app = applicationContext
            val t = title
            thread(name = "kirocrew-froyo-undo", isDaemon = true) { FroyoDone.undo(app, t) }
        }
        bar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (!isFinishing) finish()
            }
        })
        // A tap anywhere else closes the bar; the done then goes ahead.
        root.setOnClickListener { bar.dismiss() }
        bar.show()
    }

    companion object {
        const val EXTRA_TITLE = "io.github.rayvader.kirocrew.FROYO_TITLE"
    }
}
