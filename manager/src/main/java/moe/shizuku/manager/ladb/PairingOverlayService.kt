package moe.shizuku.manager.ladb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R

class PairingOverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(4201, buildNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 32
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_pairing, null)

        val inputPort = overlayView.findViewById<EditText>(R.id.inputPairPort)
        val inputCode = overlayView.findViewById<EditText>(R.id.inputPairCode)
        val btnPair = overlayView.findViewById<Button>(R.id.btnDoPair)
        val btnCancel = overlayView.findViewById<Button>(R.id.btnCancelPair)

        btnPair.setOnClickListener {
            val port = inputPort.text.toString().trim().toIntOrNull()
            val code = inputCode.text.toString().trim()

            if (port == null || port !in 1..65535) {
                Toast.makeText(this, "Неверный порт", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.length != 6 || !code.all { it.isDigit() }) {
                Toast.makeText(this, "Код — 6 цифр", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try { AdbPairing(port = port).pair(code) } catch (e: Exception) { false }
                }
                Toast.makeText(
                    this@PairingOverlayService,
                    if (ok) "✓ Сопряжение успешно" else "✗ Ошибка",
                    Toast.LENGTH_LONG
                ).show()
                if (ok) stopSelf()
            }
        }

        btnCancel.setOnClickListener { stopSelf() }
        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel("twizuku_pairing") == null) {
                nm.createNotificationChannel(
                    NotificationChannel("twizuku_pairing", "ADB Pairing", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, "twizuku_pairing")
            .setContentTitle("Сопряжение ADB")
            .setContentText("Введи порт и код")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
