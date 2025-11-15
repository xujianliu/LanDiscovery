package com.lan.discovery.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class ServerLauncherActivity : AppCompatActivity() {

    private val REQ_PERMS = 42
    private lateinit var statusTv: TextView
    private lateinit var logsTv: TextView
    private val logListener: (String) -> Unit = { line -> runOnUiThread { appendLogLine(line) } }

    private val requiredPermissions: List<String>
        get() {
            val list = mutableListOf<String>()
            // keep ACCESS_FINE_LOCATION for hotspot discovery on older devices
            list += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.NEARBY_WIFI_DEVICES
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            return list
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_launcher)

        statusTv = findViewById(R.id.statusText)
        logsTv = findViewById(R.id.logsText)
        findViewById<Button>(R.id.btnStart).setOnClickListener { ensurePermsAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { ServerService.stopService(this) }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        // Register LogBus listener for real-time logs
        LogBus.register(logListener)
        // Populate UI with existing logs
        val history = LogBus.getAll()
        logsTv.text = history.joinToString("\n")
    }

    override fun onPause() {
        super.onPause()
        try { LogBus.unregister(logListener) } catch (_: Exception) {}
    }

    private fun ensurePermsAndStart() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            ServerService.startService(this)
            Toast.makeText(this, getString(R.string.server_status_starting), Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                ServerService.startService(this)
                Toast.makeText(this, getString(R.string.server_status_starting), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.server_permission_denied), Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }

    private fun updateStatus() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        statusTv.text = if (missing.isEmpty()) getString(R.string.launcher_status_permissions_ok) else getString(R.string.launcher_status_missing_permissions, missing.joinToString(", "))
    }

    private fun appendLogLine(line: String) {
        // append and scroll
        logsTv.append(line + "\n")
        val sv = logsTv.parent
        // attempt to scroll the parent ScrollView to bottom
        (sv as? android.view.ViewGroup)?.post {
            (sv as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
