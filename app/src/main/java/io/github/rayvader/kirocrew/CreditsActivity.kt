package io.github.rayvader.kirocrew

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Settings > About > Credits: who made the app, on a moving kaleidoscope. */
class CreditsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyAccent(this, Prefs(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credits)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<TextView>(R.id.credits_app).text =
            getString(R.string.about_summary, BuildConfig.VERSION_NAME)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
