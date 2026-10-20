package io.github.rayvader.kirocrew

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.UrlTools
import java.util.UUID

class ProfilesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: ProfileAdapter
    private lateinit var emptyView: TextView
    private var editorUrlField: TextInputEditText? = null
    private var editorNameField: TextInputEditText? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        Theming.applyAccent(this, prefs)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyView = findViewById(R.id.empty)
        adapter = ProfileAdapter(this)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> select(adapter.getItem(position) ?: return@setOnItemClickListener) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { showOptions(it) }
            true
        }
        findViewById<FloatingActionButton>(R.id.add).setOnClickListener { showEditor(null) }
        refresh()

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ADD, false)) showEditor(null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profiles_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_scan -> {
            launchQr()
            true
        }
        R.id.action_connect_help -> {
            ConnectHelp.show(this)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val profiles = prefs.profiles
        adapter.activeId = prefs.activeProfile?.id
        adapter.clear()
        adapter.addAll(profiles)
        emptyView.isVisible = profiles.isEmpty()
    }

    private fun select(profile: Profile) {
        prefs.activeProfileId = profile.id
        finish()
    }

    private fun showOptions(profile: Profile) {
        val items = arrayOf(getString(R.string.open), getString(R.string.edit), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> select(profile)
                    1 -> showEditor(profile)
                    2 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_confirm, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                prefs.profiles = prefs.profiles.filterNot { it.id == profile.id }
                if (prefs.activeProfileId == profile.id) prefs.activeProfileId = prefs.profiles.firstOrNull()?.id
                refresh()
            }
            .show()
    }

    private fun showEditor(existing: Profile?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)
        val nameField = view.findViewById<TextInputEditText>(R.id.name)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.url_layout)
        val urlField = view.findViewById<TextInputEditText>(R.id.url)
        nameField.setText(existing?.name.orEmpty())
        urlField.setText(existing?.baseUrl.orEmpty())
        view.findViewById<MaterialButton>(R.id.scan).setOnClickListener { launchQr() }
        view.findViewById<MaterialButton>(R.id.help).setOnClickListener { ConnectHelp.show(this) }
        editorNameField = nameField
        editorUrlField = urlField

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_server else R.string.edit_server)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnDismissListener {
            editorNameField = null
            editorUrlField = null
        }
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val url = UrlTools.normalizeServerUrl(urlField.text?.toString().orEmpty())
                if (url == null) {
                    urlLayout.error = getString(R.string.bad_url)
                    return@setOnClickListener
                }
                val name = nameField.text?.toString()?.trim().orEmpty().ifEmpty { UrlTools.parse(url)?.host ?: url }
                saveProfile(existing?.id, name, url)
                dialog.dismiss()
                finish()
            }
            // Done on the keyboard (or Enter on a hardware keyboard) saves too.
            urlField.setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter && dialog.isShowing) saveButton.performClick()
                enter
            }
        }
        dialog.show()
    }

    private fun saveProfile(id: String?, name: String, url: String) {
        val list = prefs.profiles.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val profile = Profile(id ?: UUID.randomUUID().toString(), name, url)
        if (index >= 0) list[index] = profile else list.add(profile)
        prefs.profiles = list
        prefs.activeProfileId = profile.id
    }

    private fun launchQr() {
        (application as KiroCrewApp).expectingExternalReturn = true
        qrLauncher.launch(scanOptions(this))
    }

    private fun onScanned(text: String) {
        val target = UrlTools.parseQr(text)
        if (target == null) {
            Toast.makeText(this, R.string.qr_not_server, Toast.LENGTH_SHORT).show()
            return
        }
        val urlField = editorUrlField
        if (urlField != null) {
            // The editor is open: fill it in and let the person confirm.
            urlField.setText(target.baseUrl)
            if (editorNameField?.text.isNullOrBlank()) editorNameField?.setText(UrlTools.parse(target.baseUrl)?.host)
            target.openUrl?.let { prefs.pendingOpenUrl = it }
            return
        }
        val existing = prefs.profiles.firstOrNull { UrlTools.origin(it.baseUrl) == UrlTools.origin(target.baseUrl) }
        saveProfile(existing?.id, existing?.name ?: (UrlTools.parse(target.baseUrl)?.host ?: target.baseUrl), target.baseUrl)
        target.openUrl?.let { prefs.pendingOpenUrl = it }
        finish()
    }

    private class ProfileAdapter(context: Context) : ArrayAdapter<Profile>(context, R.layout.item_profile) {
        var activeId: String? = null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_profile, parent, false)
            val item = getItem(position) ?: return view
            view.findViewById<TextView>(R.id.title).text = item.name
            view.findViewById<TextView>(R.id.subtitle).text = item.baseUrl
            view.findViewById<ImageView>(R.id.active).isVisible = item.id == activeId
            return view
        }
    }

    companion object {
        const val EXTRA_ADD = "io.github.rayvader.kirocrew.ADD"

        fun scanOptions(context: Context): ScanOptions = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(context.getString(R.string.scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    }
}
