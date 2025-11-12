package com.lan.discovery.server

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.lan.discovery.server.databinding.ActivityServerBinding
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class ServerActivity : AppCompatActivity() {
    private val TAG = "ServerActivity"
    private lateinit var binding: ActivityServerBinding

    private val hotspotLogs = CopyOnWriteArrayList<String>()

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var httpServer: ProvisioningHttpServer? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                startHotspotAndServer()
            } else {
                showMessage(getString(R.string.server_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 通过 ViewBinding 初始化界面，避免使用 findViewById
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverToolbar.title = getString(R.string.app_name)
        binding.serverToolbar.subtitle = getString(R.string.server_toolbar_subtitle)

        binding.serverRestartButton.setOnClickListener {
            // 手动触发热点重启，便于调试
            restartHotspot()
        }
    }

    override fun onStart() {
        super.onStart()
        ensurePermissionsThenStart()
    }

    override fun onStop() {
        super.onStop()
        // 页面不可见时主动释放热点资源，避免后台占用
        stopHotspot()
    }

    private fun ensurePermissionsThenStart() {
        if (ensureHotspotPermissions()) {
            startHotspotAndServer()
        }
    }

    private fun ensureHotspotPermissions(): Boolean {
        // 收集热点与 Wi-Fi 操作所需的运行时权限
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = needed.filterNot { hasPermission(it) }
        return if (missing.isEmpty()) {
            true
        } else {
            // 缺少权限则一次性申请
            requestPermissionsLauncher.launch(missing.toTypedArray())
            false
        }
    }

    private fun startHotspotAndServer() {
        // 更新状态文本提示用户热点正在启动
        updateStatus(getString(R.string.server_status_starting))
        lifecycleScope.launch {
            // 先停止旧的热点与服务器，保证状态干净
            stopHotspot()
            startHotspot()
            startHttpServer()
        }
    }

    private suspend fun startHotspot() =
        withContext(Dispatchers.Main.immediate) {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try {
                // 调用系统 API 开启本地热点，结果通过回调返回
                val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || !nearbyGranted) {
                    Log.e(TAG, "startHotspot: 缺少关键权限，重新发起申请")
                    ensureHotspotPermissions()
                    return@withContext
                }
                if (ActivityCompat.checkSelfPermission(
                        this@ServerActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                        this@ServerActivity,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "startHotspot: 缺少权限")
                    return@withContext
                }
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        hotspotReservation = reservation
                        val configuration = reservation?.wifiConfiguration
                        val ssid = configuration?.SSID ?: getString(R.string.server_status_unknown)
                        val password =
                            configuration?.preSharedKey ?: getString(R.string.server_status_unknown)
                        updateStatus(
                            getString(
                                R.string.server_status_running,
                                ssid,
                                password
                            )
                        )
                        appendLog(getString(R.string.server_log_hotspot_ready, ssid))
                    }

                    override fun onStopped() {
                        appendLog(getString(R.string.server_log_hotspot_stopped))
                        updateStatus(getString(R.string.server_status_stopped))
                    }

                    override fun onFailed(reason: Int) {
                        appendLog(getString(R.string.server_log_hotspot_failed, reason))
                        updateStatus(getString(R.string.server_status_failed, reason))
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (throwable: Throwable) {
                // 捕获异常并提醒用户启动失败原因
                appendLog(getString(R.string.server_log_exception, throwable.localizedMessage))
                updateStatus(
                    getString(
                        R.string.server_status_exception,
                        throwable.localizedMessage
                    )
                )
            }
        }

    private suspend fun startHttpServer() = withContext(Dispatchers.IO) {
        // 启动新的 HTTP 服务前先停止旧实例
        stopHttpServer()
        httpServer = ProvisioningHttpServer { payload ->
            // 收到客户端配网参数后追加到界面日志
            appendLog(
                getString(
                    R.string.server_log_received_payload,
                    payload.targetSsid,
                    if (payload.targetPassphrase.isEmpty()) getString(R.string.server_log_no_password) else payload.targetPassphrase
                )
            )
        }.also { server ->
            try {
                server.start()
                appendLog(getString(R.string.server_log_http_started, ProvisioningHttpServer.PORT))
            } catch (throwable: Throwable) {
                appendLog(getString(R.string.server_log_http_failed, throwable.localizedMessage))
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun restartHotspot() {
        lifecycleScope.launch {
            // 先彻底停止热点与 HTTP 服务，再重新拉起
            stopHotspot()
            stopHttpServer()
            startHotspotAndServer()
        }
    }

    private fun stopHotspot() {
        hotspotReservation?.close()
        hotspotReservation = null
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
    }

    private fun updateStatus(message: String) {
        binding.serverStatus.text = message
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        hotspotLogs.add("[$timestamp] $message")
        runOnUiThread {
            // 将最新日志拼接展示，方便定位热点配网流程
            binding.serverLog.text = hotspotLogs.joinToString(separator = "\n")
        }
    }

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
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
                    // 仅处理指定路径的 POST 请求，其余返回 404
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
                        // 将 JSON 解析为配网负载结构体并交由外部处理
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
                        // 异常时返回 500，便于客户端重试
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Server error: ${throwable.localizedMessage}"
                        )
                    }
                }
            }
        }

        companion object {
            const val PORT = 8989
            private const val ENDPOINT = "/provision"
        }
    }
}

