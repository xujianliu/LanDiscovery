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

/**
 * 启动器 Activity：负责
 * - 请求所需权限（如果缺失）
 * - 启动/停止 ServerService（前台 Service）
 * - 显示并实时更新日志（通过 LogBus）
 */
class ServerLauncherActivity : AppCompatActivity() {

    // 请求码用于权限回调
    private val REQ_PERMS = 42

    // 状态与日志 TextView（在 onCreate 中绑定）
    private lateinit var statusTv: TextView
    private lateinit var logsTv: TextView

    // LogBus 的监听器：接收实时日志并在主线程更新 UI
    private val logListener: (String) -> Unit = { line -> runOnUiThread { appendLogLine(line) } }

    // 计算所需的运行时权限（根据 API level 动态包含）
    private val requiredPermissions: List<String>
        get() {
            val list = mutableListOf<String>()
            // 保留 ACCESS_FINE_LOCATION 以兼容旧设备的热点发现
            list += Manifest.permission.ACCESS_FINE_LOCATION
            // Android 13 / TIRAMISU 及以上需要 NEARBY_WIFI_DEVICES；若要通知则需要 POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.NEARBY_WIFI_DEVICES
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            return list
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_launcher)

        // 绑定 UI 元素
        statusTv = findViewById(R.id.statusText)
        logsTv = findViewById(R.id.logsText)

        // 启动按钮：先确保权限再启动 Service
        findViewById<Button>(R.id.btnStart).setOnClickListener { ensurePermsAndStart() }
        // 停止按钮：调用 Service 的停止工具方法
        findViewById<Button>(R.id.btnStop).setOnClickListener { ServerService.stopService(this) }

        // 初始化状态显示（例如缺失权限信息）
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        // 注册 LogBus 监听器以接收实时日志（Activity 在前台时注册）
        LogBus.register(logListener)
        // 将历史日志一次性加载到 UI（避免遗漏启动前的日志）
        val history = LogBus.getAll()
        logsTv.text = history.joinToString("\n")
    }

    override fun onPause() {
        super.onPause()
        // 注销监听器，避免内存泄露或多次注册
        try { LogBus.unregister(logListener) } catch (_: Exception) {}
    }

    /**
     * 检查缺失权限并在权限齐全时启动 Service
     *
     * - 若缺失权限：发起运行时权限请求（ActivityCompat.requestPermissions）
     * - 若权限齐全：直接通过 ServerService.startService 启动前台服务
     */
    private fun ensurePermsAndStart() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            // 权限齐全，启动服务并显示提示
            ServerService.startService(this)
            Toast.makeText(this, getString(R.string.server_status_starting), Toast.LENGTH_SHORT).show()
        } else {
            // 请求缺失的权限（系统会弹出权限对话）
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
        // 更新界面状态（显示当前缺失的权限或已就绪）
        updateStatus()
    }

    /**
     * 权限请求回调处理
     *
     * - 若全部授权：启动 Service
     * - 若拒绝：给出长提示说明权限不足
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                ServerService.startService(this)
                Toast.makeText(this, getString(R.string.server_status_starting), Toast.LENGTH_SHORT).show()
            } else {
                // 权限被拒绝，提示用户并保持不启动服务
                Toast.makeText(this, getString(R.string.server_permission_denied), Toast.LENGTH_LONG).show()
            }
            // 更新状态显示（以便用户了解哪些权限仍然缺失）
            updateStatus()
        }
    }

    /**
     * 更新状态栏文本：
     * - 若缺失权限则显示缺失项（用于指导用户）
     * - 否则显示权限正常
     */
    private fun updateStatus() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        statusTv.text = if (missing.isEmpty()) getString(R.string.launcher_status_permissions_ok) else getString(R.string.launcher_status_missing_permissions, missing.joinToString(", "))
    }

    /**
     * 将一行日志追加到 logsTv 并尝试滚动到最底部
     *
     * - logsTv 可能位于一个 ScrollView 中，尝试对 parent 执行 fullScroll
     */
    private fun appendLogLine(line: String) {
        // 追加文本并换行
        logsTv.append(line + "\n")
        val sv = logsTv.parent
        // 如果父容器是 ScrollView，则在 UI 线程延后滚动到底部
        (sv as? android.view.ViewGroup)?.post {
            (sv as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
