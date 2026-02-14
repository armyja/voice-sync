package com.voicesync.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import java.net.URISyntaxException

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var textInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    private lateinit var delaySeekBar: SeekBar
    private lateinit var delayValueText: TextView
    
    private var webSocketClient: MyWebSocketClient? = null
    private var isConnected = false
    private var lastSentText = ""
    private var debounceDelay = 600L

    // Debounce 机制
    private val handler = Handler(Looper.getMainLooper())
    private var sendRunnable: Runnable? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { !it.value }) {
            Toast.makeText(this, "需要权限才能使用语音", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.server_url)
        textInput = findViewById(R.id.text_input)
        connectBtn = findViewById(R.id.connect_btn)
        statusText = findViewById(R.id.status_text)
        delaySeekBar = findViewById(R.id.delay_seekbar)
        delayValueText = findViewById(R.id.delay_value)

        val prefs = getSharedPreferences("voice_sync", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "ws://192.168.1.1:8765")
        val savedDelay = prefs.getInt("debounce_delay", 600)
        serverUrlInput.setText(savedUrl)
        debounceDelay = savedDelay.toLong()
        delaySeekBar.progress = savedDelay
        delayValueText.text = savedDelay.toString()

        delaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actualDelay = maxOf(400, progress)
                debounceDelay = actualDelay.toLong()
                delayValueText.text = actualDelay.toString()
                prefs.edit().putInt("debounce_delay", actualDelay).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        connectBtn.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        // 文本变化自动发送（带 debounce）
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                // 有文字且已连接
                if (text.isNotEmpty() && isConnected) {
                    // 取消之前的延迟任务
                    sendRunnable?.let { handler.removeCallbacks(it) }
                    
                    // 设置新的延迟发送任务
                    sendRunnable = Runnable {
                        if (text.isNotEmpty() && text != lastSentText && isConnected) {
                            autoSend(text)
                        }
                    }
                    handler.postDelayed(sendRunnable!!, debounceDelay)
                }
            }
        })

        checkPermissions()
        updateStatus("未连接")
    }
    
    override fun onResume() {
        super.onResume()
        // 切回前台时自动重连
        if (!isConnected) {
            val serverUrl = serverUrlInput.text.toString()
            if (serverUrl.isNotEmpty()) {
                connect()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun connect() {
        val serverUrl = serverUrlInput.text.toString()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        getSharedPreferences("voice_sync", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", serverUrl)
            .apply()
        
        updateStatus("连接中...")
        
        try {
            webSocketClient = object : MyWebSocketClient(URI(serverUrl)) {
                override fun onOpen(handshakeServerHandshake: ServerHandshake?) {
                    runOnUiThread {
                        isConnected = true
                        connectBtn.text = "断开"
                        updateStatus("已连接")
                        send("{\"type\":\"register\",\"device\":\"mobile\",\"name\":\"手机\"}")
                    }
                }

                override fun onMessage(message: String?) {}

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    runOnUiThread {
                        isConnected = false
                        connectBtn.text = "连接"
                        updateStatus("连接断开")
                    }
                }

                override fun onError(e: Exception?) {
                    runOnUiThread {
                        updateStatus("连接失败")
                    }
                }
            }
            webSocketClient?.connect()
        } catch (e: URISyntaxException) {
            Toast.makeText(this, "地址格式错误", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnect() {
        webSocketClient?.close()
        isConnected = false
        connectBtn.text = "连接"
        updateStatus("已断开")
    }
    
    private fun autoSend(text: String) {
        if (!isConnected) return
        
        lastSentText = text
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val message = "{\"type\":\"text\",\"content\":\"$escapedText\"}"
        webSocketClient?.send(message)
        
        // 清空输入框
        textInput.setText("")
        lastSentText = ""
        
        Toast.makeText(this, "已发送: $text", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(status: String) {
        statusText.text = "状态: $status"
    }

    override fun onDestroy() {
        super.onDestroy()
        sendRunnable?.let { handler.removeCallbacks(it) }
        webSocketClient?.close()
    }

    abstract class MyWebSocketClient(serverUri: URI?) : WebSocketClient(serverUri)
}
