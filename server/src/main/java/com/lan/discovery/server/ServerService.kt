// kotlin
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
 * 前台 Service：负责启动 LocalOnlyHotspot 与嵌入式 HTTP 服务，并维护内存日志。
 *
 * 关键职责（Contract）:
 * - 输入: 通过 startService(context) / stopService(context) 或 Intent action 控制
 * - 输出: 启动热点并对外提供 HTTP /provision 接口接收配网 payload
 * - 日志: 将运行时信息与错误追加到内存日志并通过 LogBus 发布给 UI
 */
class ServerService : Service() {

    // 内存日志列表（线程安全）
    private val hotspotLogs = CopyOnWriteArrayList<String>()

    // LocalOnlyHotspot 的 reservation（用于关闭 hotspot）
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var httpServer: ProvisioningHttpServer? = null

    // 协程作用域（主线程），用于顺序启动/停止操作
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 创建通知渠道（前台服务需显示通知）
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 根据 Intent action 选择启动或停止服务
        when (intent?.action) {
            ACTION_START -> startForegroundServiceWork()
            ACTION_STOP -> {
                // 停止前台服务并自终止
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForegroundServiceWork()
        }
        // 被系统回收后尝试重建服务
        return START_STICKY
    }

    /**
     * 启动前台服务逻辑：显示通知并启动 hotspot 与 HTTP 服务（顺序控制）
     */
    private fun startForegroundServiceWork() {
        // 启用前台通知以降低被系统回收概率
        val notification = buildNotification(getString(R.string.server_status_starting))
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            // 确保先停止再启动，避免残留状态
            stopHotspot()
            stopHttpServer()
            startHotspot()
            startHttpServer()
        }
    }

    override fun onDestroy() {
        // 清理协程与资源
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

    /**
     * 启动热点（关键方法）
     *
     * 主要步骤说明：
     * 1. 检查所需权限（Service 无法弹出权限对话框，仅记录并中止）
     * 2. 尝试通过反射设置 SoftApConfiguration（固定 SSID/密码）以兼容不同 compileSdk
     * 3. 调用 WifiManager.startLocalOnlyHotspot 启动实际热点，并在回调中读取 reservation 中的配置（若可用）
     */
    private suspend fun startHotspot() = withContext(Dispatchers.Main.immediate) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            // 权限检查：Service 不能请求权限，只能记录并退出
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }

            val missing = permissions.filter { permission ->
                ContextCompat.checkSelfPermission(this@ServerService, permission) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                // 记录缺失权限并更新通知，终止 hotspot 启动
                appendLog(getString(R.string.server_log_exception, "Missing permissions: $missing"))
                updateNotification(getString(R.string.server_status_exception, "Missing permissions: $missing"))
                Log.e(TAG, "startHotspot: missing permissions $missing")
                return@withContext
            }

            // 反射尝试应用固定 SSID/密码：兼容较低 compileSdk，运行时在支持的平台上生效
            try {
                try {
                    val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
                    val builder = builderClass.getConstructor().newInstance()
                    // setSsid(String)
                    try {
                        builderClass.getMethod("setSsid", String::class.java).invoke(builder, DEFAULT_HOTSPOT_SSID)
                    } catch (_: NoSuchMethodException) { /* 方法不存在则忽略 */ }

                    // setPassphrase(String, int) — 通过反射查找 SECURITY_TYPE_WPA2_PSK
                    try {
                        val sapClass = Class.forName("android.net.wifi.SoftApConfiguration")
                        val secField = try { sapClass.getField("SECURITY_TYPE_WPA2_PSK") } catch (_: NoSuchFieldException) { null }
                        val secVal = secField?.getInt(null) ?: 4 // 回退常见值
                        try {
                            builderClass.getMethod("setPassphrase", String::class.java, Integer.TYPE).invoke(builder, DEFAULT_HOTSPOT_PASSWORD, secVal)
                        } catch (_: NoSuchMethodException) { /* 忽略 */ }
                    } catch (_: Throwable) { /* 忽略整体反射失败 */ }

                    // setHiddenSsid(boolean)
                    try {
                        builderClass.getMethod("setHiddenSsid", java.lang.Boolean.TYPE).invoke(builder, false)
                    } catch (_: NoSuchMethodException) { /* 忽略 */ }

                    // build()
                    val buildMethod = builderClass.getMethod("build")
                    val softCfg = buildMethod.invoke(builder)

                    // 调用 wifiManager.setSoftApConfiguration(softCfg)
                    try {
                        val wmClass = wifiManager.javaClass
                        val setMethod = wmClass.getMethod("setSoftApConfiguration", Class.forName("android.net.wifi.SoftApConfiguration"))
                        setMethod.invoke(wifiManager, softCfg)
                        // 应用成功则记录日志
                        appendLog(getString(R.string.server_log_hotspot_config_applied, DEFAULT_HOTSPOT_SSID))
                    } catch (se: SecurityException) {
                        // 普通应用可能无权设置 SoftApConfiguration，记录并继续
                        appendLog(getString(R.string.server_log_hotspot_config_failed, se.localizedMessage ?: "security_exception"))
                    } catch (t: Throwable) {
                        appendLog(getString(R.string.server_log_hotspot_config_failed, t.localizedMessage ?: "error"))
                    }
                } catch (t: Throwable) {
                    appendLog(getString(R.string.server_log_hotspot_config_failed, t.localizedMessage ?: "error"))
                }
            } catch (_: Throwable) {
                // 忽略反射过程中的异常，继续以系统默认行为启动 hotspot
            }

            // 启动 LocalOnlyHotspot：平台可能使用上面尝试应用的配置，也可能忽略
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                // onStarted: hotspot 启动成功并返回 reservation，可读取 wifiConfiguration（兼容后备）
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    hotspotReservation = reservation
                    var ssid = getString(R.string.server_status_unknown)
                    var password = getString(R.string.server_status_unknown)

                    // reservation.wifiConfiguration 可能为 null 或已弃用，作为兼容后备读取
                    val configuration = reservation?.wifiConfiguration
                    val confSsid = configuration?.SSID
                    val confPass = configuration?.preSharedKey
                    if (!confSsid.isNullOrEmpty()) ssid = confSsid
                    if (!confPass.isNullOrEmpty()) password = confPass
                    val status = getString(R.string.server_status_running, ssid, password)
                    // 记录热点就绪信息并更新通知
                    appendLog(getString(R.string.server_log_hotspot_ready, ssid, password))
                    updateNotification(status)
                }

                // onStopped: 热点已停止（手动或系统回收）
                override fun onStopped() {
                    appendLog(getString(R.string.server_log_hotspot_stopped))
                    updateNotification(getString(R.string.server_status_stopped))
                }

                // onFailed: 启动失败，记录原因
                override fun onFailed(reason: Int) {
                    appendLog(getString(R.string.server_log_hotspot_failed, reason))
                    updateNotification(getString(R.string.server_status_failed, reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (throwable: Throwable) {
            // 捕获整个流程的异常并记录，防止崩溃
            appendLog(getString(R.string.server_log_exception, throwable.localizedMessage))
            updateNotification(getString(R.string.server_status_exception, throwable.localizedMessage))
        }
    }

    /**
     * 启动嵌入式 HTTP 服务（IO 线程）
     * - 接收 POST 到 /provision 的配网 payload
     * - 收到后通过 appendLog 记录并交由回调处理
     */
    private suspend fun startHttpServer() = withContext(Dispatchers.IO) {
        stopHttpServer()
        httpServer = ProvisioningHttpServer { payload ->
            appendLog(getString(R.string.server_log_received_payload, payload.targetSsid, if (payload.targetPassphrase.isEmpty()) getString(R.string.server_log_no_password) else payload.targetPassphrase))
        }.also { server ->
            try {
                server.start()
                appendLog(getString(R.string.server_log_http_started, PORT))
            } catch (throwable: Throwable) {
                // 启动失败记录错误信息
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

    /**
     * 关闭 hotspot：释放 reservation（若存在）并记录可能的异常
     */
    private fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stopHotspot: exception when closing reservation", t)
        }
        hotspotReservation = null
    }

    /**
     * 停止 HTTP 服务并忽略停止过程中的异常
     */
    private fun stopHttpServer() {
        try {
            httpServer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stopHttpServer: exception when stopping http server", t)
        }
        httpServer = null
    }

    // 更新通知内容（保持前台通知信息同步）
    private fun updateNotification(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(message)
        nm.notify(NOTIFICATION_ID, notification)
    }

    // 构建前台通知（用于 startForeground）
    private fun buildNotification(content: String): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        return builder.build()
    }

    // 创建通知渠道（仅需一次）
    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Server", NotificationManager.IMPORTANCE_LOW)
        channel.description = "LAN Discovery server channel"
        nm.createNotificationChannel(channel)
    }

    /**
     * 追加一行日志到内存并通过 LogBus 发布给 UI（实时）
     * - 格式化时间戳并写入 hotspotLogs（历史）
     * - 同时写入 Logcat 便于调试
     */
    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        hotspotLogs.add(line)
        Log.i(TAG, line)

        // 发布到进程内 LogBus，供 Activity 实时监听
        try { LogBus.post(line) } catch (t: Throwable) { Log.w(TAG, "appendLog: failed to post to LogBus", t) }
    }

    // 配网 payload 的数据类（内部使用）
    data class ProvisioningPayload(
        val targetSsid: String,
        val targetPassphrase: String,
        val extra: JSONObject
    )

    companion object {
        const val PORT = 8989
        private const val TAG = "ServerService"
        const val ENDPOINT = "/provision" // 暴露给外部类使用（ProvisioningHttpServer）
        private const val NOTIFICATION_CHANNEL_ID = "lan_discovery_server_channel"
        private const val NOTIFICATION_ID = 1327

        // 固定热点凭据（可在 UI 中暴露为可配置项）
        const val DEFAULT_HOTSPOT_SSID = "LanDiscoveryAP"
        const val DEFAULT_HOTSPOT_PASSWORD = "lan123456"

        // Activity ↔ Service 的 action 常量
        const val ACTION_START = "com.lan.discovery.server.action.START"
        const val ACTION_STOP = "com.lan.discovery.server.action.STOP"

        // 提示：UI 使用 LogBus 获取实时日志与历史
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

/**
 * 嵌入式 HTTP 服务器实现（独立类，置于同一文件）
 *
 * 说明：
 * - 监听 ServerService.PORT（8989）并处理 POST /provision 请求
 * - 成功解析 payload 后通过 onPayload 回调将数据交给上层处理
 */
private class ProvisioningHttpServer(
    private val onPayload: (ServerService.ProvisioningPayload) -> Unit
) : NanoHTTPD(ServerService.PORT) {

    override fun serve(session: IHTTPSession): Response {
        return when {
            // 非 POST 或 非目标路径返回 404
            session.method != Method.POST || session.uri != ServerService.ENDPOINT -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }

            else -> {
                try {
                    // 解析请求体
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
                    // 通过回调把解析后的 payload 交给 Service 处理（例如记录日志或触发配网）
                    onPayload(
                        ServerService.ProvisioningPayload(
                            targetSsid = targetSsid,
                            targetPassphrase = targetPass,
                            extra = jsonObject
                        )
                    )
                    // 返回成功响应
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_PLAINTEXT,
                        "Provisioning payload accepted"
                    )
                } catch (throwable: Throwable) {
                    // 出错时返回 500（上层会记录日志）
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
