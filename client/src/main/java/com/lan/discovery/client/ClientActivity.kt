package com.lan.discovery.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.lan.discovery.client.databinding.ActivityClientBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean


class ClientActivity : AppCompatActivity() {
    private val TAG = "ClientActivity"
    private lateinit var binding: ActivityClientBinding

    private var connectivityManager: ConnectivityManager? = null
    private var hotspotCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotNetwork: Network? = null
    private val isConnecting = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                attemptConnection()
            } else {
                showMessage(getString(R.string.client_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 通过 ViewBinding 构建界面
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 缓存 ConnectivityManager，后续用于请求热点网络
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        binding.clientToolbar.title = getString(R.string.app_name)
        binding.clientToolbar.subtitle = getString(R.string.client_toolbar_subtitle)

        binding.clientConnectButton.setOnClickListener {
            // 用户点击后检查权限并尝试连接热点
            ensurePermissionsThenConnect()
        }
        findIp()

        binding.clientSendButton.setOnClickListener {
            // 已连接热点时发送配网数据
            sendProvisioningPayload()
        }
    }

    override fun onStop() {
        super.onStop()
        // 页面离开时释放网络资源，避免占用热点
        releaseNetwork()
    }

    private fun ensurePermissionsThenConnect() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_NETWORK_STATE,
//            Manifest.permission.WRITE_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_WIFI_STATE)
            permissions.add(Manifest.permission.CHANGE_WIFI_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            attemptConnection()
        } else {
            Log.i(TAG, "ensurePermissionsThenConnect: missing $missing")
            // 缺失权限时发起一次性申请
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun attemptConnection() {
        if (isConnecting.get()) return
        val serverSsid = binding.serverSsidInput.text?.toString().orEmpty()
        val serverPassword = binding.serverPasswordInput.text?.toString().orEmpty()
        if (serverSsid.isBlank()) {
            showMessage(getString(R.string.client_input_server_ssid))
            return
        }
        // 仅在输入合法后才真正发起连接
        connectToHotspot(serverSsid, serverPassword)
    }

    private fun connectToHotspot(ssid: String, password: String) {
        if (!isConnecting.compareAndSet(false, true)) {
            return
        }
        updateStatus(getString(R.string.client_status_connecting, ssid))
        releaseNetwork()
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotBlank()) {
            specBuilder.setWpa2Passphrase(password)
        }
        // 构建临时网络请求，只允许连接目标 SSID
        val networkSpecifier = specBuilder.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(networkSpecifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 连接成功后将进程绑定到热点网络，保证后续请求走热点
                connectivityManager?.bindProcessToNetwork(network)
                hotspotNetwork = network
                isConnecting.set(false)
                runOnUiThread {
                    updateStatus(getString(R.string.client_status_connected, ssid))
                    Snackbar.make(
                        binding.root,
                        R.string.client_status_connected_snackbar,
                        Snackbar.LENGTH_SHORT
                    ).show()
                    binding.clientSendButton.isEnabled = true
                }
            }

            override fun onUnavailable() {
                isConnecting.set(false)
                runOnUiThread {
                    updateStatus(getString(R.string.client_status_unavailable))
                    showMessage(getString(R.string.client_status_unavailable))
                }
            }

            override fun onLost(network: Network) {
                if (hotspotNetwork == network) {
                    // 热点断开时同步清理绑定状态
                    isConnecting.set(false)
                    hotspotNetwork = null
                    connectivityManager?.bindProcessToNetwork(null)
                    runOnUiThread {
                        updateStatus(getString(R.string.client_status_lost))
                        binding.clientSendButton.isEnabled = false
                    }
                }
            }
        }
        hotspotCallback = callback
        connectivityManager?.requestNetwork(request, callback)
    }

    private fun sendProvisioningPayload() {
        val network = hotspotNetwork
        if (network == null) {
            showMessage(getString(R.string.client_status_not_connected))
            return
        }
        val targetSsid = binding.targetSsidInput.text?.toString().orEmpty()
        if (targetSsid.isBlank()) {
            showMessage(getString(R.string.client_input_target_ssid))
            return
        }
        // 组装要提交给服务端的配网 JSON
        val payload = JSONObject().apply {
            put("targetSsid", targetSsid)
            put("targetPassphrase", binding.targetPasswordInput.text?.toString().orEmpty())
            put("timestamp", System.currentTimeMillis())
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 在 IO 线程发起 HTTP 请求，避免阻塞主线程
                postProvisioning(network, payload)
            }
            if (result) {
                showToast(getString(R.string.client_send_success))
            } else {
                showMessage(getString(R.string.client_send_failure))
            }
        }
    }

    private fun postProvisioning(network: Network, payload: JSONObject): Boolean {
        return runCatching {
            val ip = findIp()
            val url = URL("http://192.168.93.225:8989/provision")
            val connection = network.openConnection(url) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
        }.getOrDefault(false)
    }

    private fun findIp(): String {
        // 获取WifiManager实例
        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager

        // 获取当前Wi-Fi连接信息
        val wifiInfo = wifiManager.getConnectionInfo()

        // 获取当前设备的IP地址
        val ipAddress = wifiInfo.getIpAddress()

        // 将IP地址转换为字符串格式
        val ipAddressString = String.format(
            "%d.%d.%d.%d",
            (ipAddress and 0xff),
            (ipAddress shr 8 and 0xff),
            (ipAddress shr 16 and 0xff),
            (ipAddress shr 24 and 0xff)
        )
        Log.e(TAG, "findIp: $ipAddressString")
        return ipAddressString

    }

    private fun releaseNetwork() {
        hotspotCallback?.let { callback ->
            // 取消注册网络回调，避免内存泄漏
            connectivityManager?.unregisterNetworkCallback(callback)
        }
        hotspotCallback = null
        hotspotNetwork = null
        connectivityManager?.bindProcessToNetwork(null)
        runOnUiThread {
            binding.clientSendButton.isEnabled = false
        }
    }

    private fun updateStatus(status: String) {
        binding.clientStatus.text = status
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

