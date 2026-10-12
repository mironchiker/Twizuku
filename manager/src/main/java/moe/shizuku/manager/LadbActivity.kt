package com.example.twizuku.ladb

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LadbActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var adb: AdbShell

    private val history = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ladb)

        output = findViewById(R.id.output)
        input = findViewById(R.id.input)
        scroll = findViewById(R.id.scrollOutput)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btnRun).setOnClickListener {
            runInput()
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runInput()
                true
            } else false
        }

        // Быстрые чипы
        findViewById<Chip>(R.id.chipPm).setOnClickListener { input.setText("pm ") }
        findViewById<Chip>(R.id.chipSettings).setOnClickListener { input.setText("settings ") }
        findViewById<Chip>(R.id.chipAm).setOnClickListener { input.setText("am ") }
        findViewById<Chip>(R.id.chipClear).setOnClickListener { output.text = "" }

        // Подключение к локальному ADB
        adb = AdbShell()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { adb.connect() }
            appendLine(if (ok) "Подключено к ADB (127.0.0.1)" else "Ошибка подключения")
        }
    }

    private fun runInput() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        history.add(cmd)
        historyIndex = history.size
        input.setText("")
        appendLine("\n$ $cmd")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { adb.exec(cmd) }
            appendLine(result)
        }
    }

    private fun appendLine(text: String) {
        output.append(text + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        adb.close()
    }
}
