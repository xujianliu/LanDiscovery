package com.lan.discovery.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground Service replacement for the previous ServerActivity.
 *
 * Behavior summary (contract):
 * - Inputs: startService(context) / stopService(context) or startService Intent with ACTION_START/ACTION_STOP
 * - Outputs: starts a LocalOnlyHotspot and an embedded HTTP server that accepts provisioning POSTs
 * - Error modes: logs missing permissions or server/hotspot errors to Logcat and keeps them in an in-memory list
 * - Success: Hotspot + HTTP server running until service is stopped
 */
class ServerService : Service() {

    private val hotspotLogs = CopyOnWriteArrayList<String>()

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var httpServer: ProvisioningHttpServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundServiceWork()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForegroundServiceWork()
        }
        // If killed, let the system recreate the service and call onStartCommand with null intent
        return START_STICKY
    }

    private fun startForegroundServiceWork() {
        // Start as foreground to keep hotspot alive
        val notification = buildNotification(getString(R.string.server_status_starting))
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            stopHotspot()
            stopHttpServer()
            startHotspot()
            startHttpServer()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopHotspot()
        stopHttpServer()
        super.onDestroy()
    }

    private fun startHotspotAndServer() {
        serviceScope.launch {
            stopHotspot()
            startHotspot()
            startHttpServer()
        }
    }

    private suspend fun startHotspot() = withContext(Dispatchers.Main.immediate) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            // Check required permissions; services cannot request runtime permissions so we just log and abort if missing
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }

            val missing = permissions.filter { permission ->
                ContextCompat.checkSelfPermission(this@ServerService, permission) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                appendLog(getString(R.string.server_log_exception, "Missing permissions: $missing"))
                updateNotification(getString(R.string.server_status_exception, "Missing permissions: $missing"))
                Log.e(TAG, "startHotspot: missing permissions $missing")
                return@withContext
            }

            // Try to apply a fixed SSID and passphrase when supported by the platform.
            // Use reflection so this compiles against lower compileSdk versions.
            try {
                try {
                    val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
                    val builder = builderClass.getConstructor().newInstance()
                    // setSsid(String)
                    try {
                        builderClass.getMethod("setSsid", String::class.java).invoke(builder, DEFAULT_HOTSPOT_SSID)
                    } catch (_: NoSuchMethodException) { /* ignore */ }

                    // setPassphrase(String, int) — find SECURITY_TYPE_WPA2_PSK reflectively
                    try {
                        val sapClass = Class.forName("android.net.wifi.SoftApConfiguration")
                        val secField = try { sapClass.getField("SECURITY_TYPE_WPA2_PSK") } catch (_: NoSuchFieldException) { null }
                        val secVal = secField?.getInt(null) ?: 4 // fallback common value
                        try {
                            builderClass.getMethod("setPassphrase", String::class.java, Integer.TYPE).invoke(builder, DEFAULT_HOTSPOT_PASSWORD, secVal)
                        } catch (_: NoSuchMethodException) { /* ignore */ }
                    } catch (_: Throwable) { /* ignore */ }

                    // setHiddenSsid(boolean)
                    try {
                        builderClass.getMethod("setHiddenSsid", java.lang.Boolean.TYPE).invoke(builder, false)
                    } catch (_: NoSuchMethodException) { /* ignore */ }

                    // build()
                    val buildMethod = builderClass.getMethod("build")
                    val softCfg = buildMethod.invoke(builder)

                    // wifiManager.setSoftApConfiguration(softCfg)
                    try {
                        val wmClass = wifiManager.javaClass
                        val setMethod = wmClass.getMethod("setSoftApConfiguration", Class.forName("android.net.wifi.SoftApConfiguration"))
                        setMethod.invoke(wifiManager, softCfg)
                        appendLog(getString(R.string.server_log_hotspot_config_applied, DEFAULT_HOTSPOT_SSID))
                    } catch (se: SecurityException) {
                        appendLog(getString(R.string.server_log_hotspot_config_failed, se.localizedMessage ?: "security_exception"))
                    } catch (t: Throwable) {
                        appendLog(getString(R.string.server_log_hotspot_config_failed, t.localizedMessage ?: "error"))
                    }
                } catch (t: Throwable) {
                    appendLog(getString(R.string.server_log_hotspot_config_failed, t.localizedMessage ?: "error"))
                }
            } catch (_: Throwable) {
                // ignore failures here and fall back to system behavior
            }

            // Start the local-only hotspot; the platform may use the SoftApConfiguration we attempted to set above
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    hotspotReservation = reservation
                    var ssid = getString(R.string.server_status_unknown)
                    var password = getString(R.string.server_status_unknown)

                    val configuration = reservation?.wifiConfiguration
                    val confSsid = configuration?.SSID
                    val confPass = configuration?.preSharedKey
                    if (!confSsid.isNullOrEmpty()) ssid = confSsid
                    if (!confPass.isNullOrEmpty()) password = confPass
                    val status = getString(R.string.server_status_running, ssid, password)
                    appendLog(getString(R.string.server_log_hotspot_ready, ssid,password))
                    updateNotification(status)
                }

                override fun onStopped() {
                    appendLog(getString(R.string.server_log_hotspot_stopped))
                    updateNotification(getString(R.string.server_status_stopped))
                }

                override fun onFailed(reason: Int) {
                    appendLog(getString(R.string.server_log_hotspot_failed, reason))
                    updateNotification(getString(R.string.server_status_failed, reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (throwable: Throwable) {
            appendLog(getString(R.string.server_log_exception, throwable.localizedMessage))
            updateNotification(getString(R.string.server_status_exception, throwable.localizedMessage))
        }
    }

    private suspend fun startHttpServer() = withContext(Dispatchers.IO) {
        stopHttpServer()
        httpServer = ProvisioningHttpServer { payload ->
            appendLog(getString(R.string.server_log_received_payload, payload.targetSsid, if (payload.targetPassphrase.isEmpty()) getString(R.string.server_log_no_password) else payload.targetPassphrase))
        }.also { server ->
            try {
                server.start()
                appendLog(getString(R.string.server_log_http_started, PORT))
            } catch (throwable: Throwable) {
                appendLog(getString(R.string.server_log_http_failed, throwable.localizedMessage))
            }
        }
    }

    private fun restartHotspot() {
        serviceScope.launch {
            stopHotspot()
            stopHttpServer()
            startHotspotAndServer()
        }
    }

    private fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stopHotspot: exception when closing reservation", t)
        }
        hotspotReservation = null
    }

    private fun stopHttpServer() {
        try {
            httpServer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopHttpServer: exception when stopping http server", t)
        }
        httpServer = null
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(message)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(content: String): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Server", NotificationManager.IMPORTANCE_LOW)
        channel.description = "LAN Discovery server channel"
        nm.createNotificationChannel(channel)
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        hotspotLogs.add(line)
        Log.i(TAG, line)

        // Publish to in-process LogBus for UI listeners
        try { LogBus.post(line) } catch (t: Throwable) { Log.w(TAG, "appendLog: failed to post to LogBus", t) }
    }

    data class ProvisioningPayload(
        val targetSsid: String,
        val targetPassphrase: String,
        val extra: JSONObject
    )

    private class ProvisioningHttpServer(
        private val onPayload: (ProvisioningPayload) -> Unit
    ) : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method != Method.POST || session.uri != ENDPOINT -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }

                else -> {
                    try {
                        val body = HashMap<String, String>()
                        session.parseBody(body)
                        val json = body["postData"]?.takeIf { it.isNotEmpty() }
                            ?: return newFixedLengthResponse(
                                Response.Status.BAD_REQUEST,
                                MIME_PLAINTEXT,
                                "Missing request body"
                            )
                        val jsonObject = JSONObject(json)
                        val targetSsid = jsonObject.optString("targetSsid")
                        val targetPass = jsonObject.optString("targetPassphrase")
                        onPayload(
                            ProvisioningPayload(
                                targetSsid = targetSsid,
                                targetPassphrase = targetPass,
                                extra = jsonObject
                            )
                        )
                        newFixedLengthResponse(
                            Response.Status.OK,
                            MIME_PLAINTEXT,
                            "Provisioning payload accepted"
                        )
                    } catch (throwable: Throwable) {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Server error: ${throwable.localizedMessage}"
                        )
                    }
                }
            }
        }

    }

    companion object {
        const val PORT = 8989
        private const val TAG = "ServerService"
        private const val ENDPOINT = "/provision"

        private const val NOTIFICATION_CHANNEL_ID = "lan_discovery_server_channel"
        private const val NOTIFICATION_ID = 1327

        // Fixed hotspot credentials (change as needed)
        const val DEFAULT_HOTSPOT_SSID = "LanDiscoveryAP"
        const val DEFAULT_HOTSPOT_PASSWORD = "lan123456"

        // Actions exported for Activity ↔ Service communication
        const val ACTION_START = "com.lan.discovery.server.action.START"
        const val ACTION_STOP = "com.lan.discovery.server.action.STOP"

        // NOTE: UI should use LogBus to receive realtime logs and to get history

        fun startService(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
