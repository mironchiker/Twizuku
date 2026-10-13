package moe.shizuku.manager.ladb

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import java.security.KeyPair
import java.security.KeyPairGenerator

class LadbActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var btnConnect: MaterialButton

    private var adb: AdbClient? = null
    private var keyPair: KeyPair? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ladb)

        output = findViewById(R.id.output)
        input = findViewById(R.id.input)
        scroll = findViewById(R.id.scrollOutput)
        btnConnect = findViewById(R.id.btnConnect)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnRun).setOnClickListener { runInput() }
        btnConnect.setOnClickListener { showConnectDialog() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { runInput(); true } else false
        }

        findViewById<Chip>(R.id.chipPm).setOnClickListener { input.setText("pm ") }
        findViewById<Chip>(R.id.chipSettings).setOnClickListener { input.setText("settings ") }
        findViewById<Chip>(R.id.chipAm).setOnClickListener { input.setText("am ") }
        findViewById<Chip>(R.id.chipClear).setOnClickListener { output.text = "" }

        appendLine("LADB готов.")
        appendLine("1) Включи беспроводную отладку.")
        appendLine("2) Нажми «Подключиться» — появится окно сопряжения.")
        appendLine("3) Введи порт и код из настроек.")
    }

    private fun showConnectDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Нужно разрешение")
                .setMessage("Разреши Twizuku рисовать поверх других приложений.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        startService(Intent(this, PairingOverlayService::class.java))
        appendLine("\n→ Открыто окно сопряжения.")
    }

    fun connectWithPort(port: Int) {
        lifecycleScope.launch {
            appendLine("\n→ Подключение к 127.0.0.1:$port...")
            try {
                val kp = keyPair ?: generateKeyPair().also { keyPair = it }
                val client = AdbClient(port = port)
                val ok = withContext(Dispatchers.IO) { client.connect(kp) }

                if (ok) {
                    adb = client
                    appendLine("✓ Подключено.")
                    btnConnect.text = "Переподключиться"
                } else {
                    client.close()
                    appendLine("✗ Ошибка авторизации.")
                }
            } catch (e: Exception) {
                appendLine("✗ Ошибка: ${e.message}")
            }
        }
    }

    private fun runInput() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.setText("")
        appendLine("\n$ $cmd")

        val client = adb
        if (client == null) { appendLine("Нет подключения."); return }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { client.shell(cmd) } catch (e: Exception) { "ошибка: ${e.message}" }
            }
            appendLine(result.trimEnd())
        }
    }

    private fun appendLine(text: String) {
        output.append(text + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    override fun onDestroy() {
        super.onDestroy()
        adb?.close()
    }
}
