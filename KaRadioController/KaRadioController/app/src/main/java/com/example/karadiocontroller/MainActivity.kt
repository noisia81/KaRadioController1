package com.example.karadiocontroller

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import android.media.MediaPlayer
import androidx.core.content.edit
import androidx.core.view.isVisible

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var ipAddress = "192.168.1.100"
    private var currentActiveTab = R.id.nav_player

    private var webSocket: WebSocket? = null
    private val wsReconnectRunnable = Runnable { initWebSocket() }

    // Хранилище списка станций (используем Map для сохранения порядка при асинхронной загрузке)
    private val stationMap = java.util.TreeMap<Int, String>()
    private val stationList = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Переменные для хранения ссылок на текущие активные View плеера
    private var currentView: View? = null

    private var isMonitoring = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastMonitoredStationId: String? = null

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (ipAddress.isNotEmpty()) {
                fetchRadioStatus()
            }
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Полноэкранный режим
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(R.layout.activity_main)

        // Загружаем сохраненный IP-адрес устройства
        val prefs = getSharedPreferences("KaRadioPrefs", MODE_PRIVATE)
        ipAddress = prefs.getString("device_ip", "192.168.1.100") ?: "192.168.1.100"

        // Логика переключения нижнего меню
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            currentActiveTab = item.itemId
            switchScreen(item.itemId)
            true
        }

        // По умолчанию открываем плеер
        switchScreen(R.id.nav_player)
        handler.post(pollingRunnable)
        initWebSocket()

        // Автоматическая синхронизация плейлиста при запуске
        syncPlaylistFromEsp()
    }

    private fun initWebSocket() {
        if (ipAddress.isEmpty()) return
        
        webSocket?.close(1000, "Reconnecting")
        
        val request = Request.Builder()
            .url("ws://$ipAddress/")
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                addToDebugLog("WS: Connected. Sending ustart...")
                webSocket.send("ustart")
                // Небольшая задержка перед основной командой
                handler.postDelayed({
                    webSocket.send("infos")
                }, 500)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val trimmed = text.trim()
                addToDebugLog("WS IN: $trimmed")
                
                // 1. Обработка списка Wi-Fi сетей (текстовый формат)
                if (trimmed.startsWith("##CLI.WIFI#")) {
                    val info = trimmed.substringAfter("##CLI.WIFI#").trim()
                    runOnUiThread {
                        if (currentActiveTab == R.id.nav_settings) {
                            currentView?.let { view ->
                                val tv = view.findViewById<TextView>(R.id.tvWifiList)
                                val pb = view.findViewById<ProgressBar>(R.id.pbWifiList)
                                pb.isVisible = false
                                
                                val currentText = tv.text.toString()
                                if (currentText.contains("Scanning") || currentText.contains("No networks")) {
                                    tv.text = getString(R.string.wifi_list_item_format, info)
                                } else {
                                    tv.append(getString(R.string.wifi_list_item_format, info))
                                }
                            }
                        }
                    }
                    return
                }

                // 2. Обработка JSON-статусов (upgrade, volume, meta)
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        val json = JSONObject(trimmed)
                        
                        // Прогресс обновления
                        if (json.has("upgrade")) {
                            val msg = json.getString("upgrade")
                            runOnUiThread {
                                if (currentActiveTab == R.id.nav_settings) {
                                    currentView?.let { view ->
                                        view.findViewById<LinearLayout>(R.id.llUpdateProgress).isVisible = true
                                        view.findViewById<TextView>(R.id.tvUpdateStatus).text = msg
                                        view.findViewById<Button>(R.id.btnInstallUpdate).isEnabled = false
                                    }
                                }
                            }
                        }
                        
                        // Здесь можно добавить обновление громкости и метаданных в реальном времени
                    } catch (_: Exception) {
                        // Игнорируем ошибки парсинга не-JSON строк
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                addToDebugLog("WS: Closing ($code): $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                addToDebugLog("WS FAIL: ${t.message}")
                // Переподключение через 5 секунд
                handler.removeCallbacks(wsReconnectRunnable)
                handler.postDelayed(wsReconnectRunnable, 5000)
            }
        })
    }

    private fun switchScreen(tabId: Int) {
        val container = findViewById<FrameLayout>(R.id.fragment_container)
        container.removeAllViews()
        currentView = null // Сбрасываем старую ссылку

        when (tabId) {
            R.id.nav_player -> {
                val view = layoutInflater.inflate(R.layout.fragment_player, container, false)
                container.addView(view)
                currentView = view
                setupPlayerTab(view)
                fetchEqStatus(view)
            }
            R.id.nav_playlist -> {
                val view = layoutInflater.inflate(R.layout.fragment_playlist, container, false)
                container.addView(view)
                currentView = view
                setupPlaylistTab(view)
            }
            R.id.nav_settings -> {
                val view = layoutInflater.inflate(R.layout.fragment_settings, container, false)
                container.addView(view)
                currentView = view
                setupSettingsTab(view)
            }
        }
    }

    // ЛОГИКА ВКЛАДКИ ПЛЕЕРА
    private fun setupPlayerTab(v: View) {
        val prefs = getSharedPreferences("KaRadioPrefs", MODE_PRIVATE)

        v.findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { 
            // Каравин использует POST /play?id=...
            // Для старта часто используется id=-1 или просто команда через GET
            // Но попробуем универсальный GET старт сначала
            sendLegacyCommand("start") 
        }
        v.findViewById<ImageButton>(R.id.btnStop).setOnClickListener { sendLegacyCommand("stop") }
        v.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { sendLegacyCommand("next") }
        v.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { sendLegacyCommand("prev") }

        // Ползунок Громкости
        val sbVol = v.findViewById<SeekBar>(R.id.seekBarVolume)
        sbVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pct = (progress * 100) / 254
                    v.findViewById<TextView>(R.id.tvVolLabel).text = getString(R.string.vol_label, pct)
                    // Каравин: POST /soundvol с телом vol=X&
                    sendKarawinCommand("soundvol", "vol=$progress&")
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Treble Level (-8 to 7, step 1.5dB)
        val sbTreble = v.findViewById<SeekBar>(R.id.sbTreble)
        val savedTreble = prefs.getInt("eq_treble", 11) // Default 4.5dB
        sbTreble.progress = savedTreble
        updateTrebleLabel(v, savedTreble)
        
        sbTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrebleLabel(v, progress)
                if (fromUser) {
                    val realVal = progress - 8
                    sendCommand("treble=$realVal")
                    prefs.edit { putInt("eq_treble", progress) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Treble Frequency (1000Hz steps)
        val sbTrebleFreq = v.findViewById<SeekBar>(R.id.sbTrebleFreq)
        val savedTrebleFreq = prefs.getInt("eq_treble_freq", 8) // Default 8kHz
        sbTrebleFreq.progress = savedTrebleFreq
        v.findViewById<TextView>(R.id.tvTrebleFreqVal).text = getString(R.string.treble_freq_val, savedTrebleFreq)
        
        sbTrebleFreq.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                v.findViewById<TextView>(R.id.tvTrebleFreqVal).text = getString(R.string.treble_freq_val, progress)
                if (fromUser) {
                    sendCommand("treblefreq=$progress")
                    prefs.edit { putInt("eq_treble_freq", progress) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Bass Level (0-15 dB)
        val sbBass = v.findViewById<SeekBar>(R.id.sbBass)
        val savedBass = prefs.getInt("eq_bass", 15) // Default 15dB
        sbBass.progress = savedBass
        v.findViewById<TextView>(R.id.tvBassVal).text = getString(R.string.bass_val, savedBass)

        sbBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                v.findViewById<TextView>(R.id.tvBassVal).text = getString(R.string.bass_val, progress)
                if (fromUser) {
                    sendCommand("bass=$progress")
                    prefs.edit { putInt("eq_bass", progress) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Bass Frequency (20-150 Hz, step 10Hz)
        val sbBassFreq = v.findViewById<SeekBar>(R.id.sbBassFreq)
        val savedBassFreq = prefs.getInt("eq_bass_freq", 13) // Default 150Hz
        sbBassFreq.progress = savedBassFreq
        val freq = (savedBassFreq + 2) * 10
        v.findViewById<TextView>(R.id.tvBassFreqVal).text = getString(R.string.bass_freq_val, freq)

        sbBassFreq.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val f = (progress + 2) * 10
                v.findViewById<TextView>(R.id.tvBassFreqVal).text = getString(R.string.bass_freq_val, f)
                if (fromUser) {
                    sendCommand("bassfreq=${progress + 2}")
                    prefs.edit { putInt("eq_bass_freq", progress) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Spacialization
        val sbSpacial = v.findViewById<SeekBar>(R.id.sbSpacial)
        val savedSpacial = prefs.getInt("eq_spacial", 1) // Default Minimal
        sbSpacial.progress = savedSpacial
        updateSpacialLabel(v, savedSpacial)

        sbSpacial.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpacialLabel(v, progress)
                if (fromUser) {
                    sendCommand("spacial=$progress")
                    prefs.edit { putInt("eq_spacial", progress) }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun updateTrebleLabel(v: View, progress: Int) {
        val realVal = progress - 8
        val db = realVal * 1.5
        v.findViewById<TextView>(R.id.tvTrebleVal).text = getString(R.string.treble_val, db)
    }

    private fun updateSpacialLabel(v: View, progress: Int) {
        val txt = when(progress) {
            0 -> getString(R.string.spacial_off)
            1 -> getString(R.string.spacial_minimal)
            2 -> getString(R.string.spacial_normal)
            3 -> getString(R.string.spacial_max)
            else -> getString(R.string.spacial_off)
        }
        v.findViewById<TextView>(R.id.tvSpacialVal).text = txt
    }

    private fun fetchEqStatus(v: View) {
        val url = "http://$ipAddress/?sys.tone"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: ""
                // Expected format: Bass: 15, Treble: 11, BassFreq: 13, TrebleFreq: 8
                val parts = res.split(",")
                var bVal = -1
                var tVal = -1
                var bfVal = -1
                var tfVal = -1

                for (part in parts) {
                    val p = part.trim()
                    if (p.startsWith("Bass:")) bVal = p.substringAfter(":").trim().toIntOrNull() ?: -1
                    if (p.startsWith("Treble:")) tVal = p.substringAfter(":").trim().toIntOrNull() ?: -1
                    if (p.startsWith("BassFreq:")) bfVal = p.substringAfter(":").trim().toIntOrNull() ?: -1
                    if (p.startsWith("TrebleFreq:")) tfVal = p.substringAfter(":").trim().toIntOrNull() ?: -1
                }

                runOnUiThread {
                    val prefs = getSharedPreferences("KaRadioPrefs", MODE_PRIVATE)
                    if (bVal != -1) {
                        v.findViewById<SeekBar>(R.id.sbBass).progress = bVal
                        v.findViewById<TextView>(R.id.tvBassVal).text = getString(R.string.bass_val, bVal)
                        prefs.edit { putInt("eq_bass", bVal) }
                    }
                    if (tVal != -1) {
                        v.findViewById<SeekBar>(R.id.sbTreble).progress = tVal + 8 // internal is -8 to 7, UI is 0-15
                        updateTrebleLabel(v, tVal + 8)
                        prefs.edit { putInt("eq_treble", tVal + 8) }
                    }
                    if (bfVal != -1) {
                        v.findViewById<SeekBar>(R.id.sbBassFreq).progress = bfVal - 2 // internal 2-15, UI 0-13
                        val f = bfVal * 10
                        v.findViewById<TextView>(R.id.tvBassFreqVal).text = getString(R.string.bass_freq_val, f)
                        prefs.edit { putInt("eq_bass_freq", bfVal - 2) }
                    }
                    if (tfVal != -1) {
                        v.findViewById<SeekBar>(R.id.sbTrebleFreq).progress = tfVal
                        v.findViewById<TextView>(R.id.tvTrebleFreqVal).text = getString(R.string.treble_freq_val, tfVal)
                        prefs.edit { putInt("eq_treble_freq", tfVal) }
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
        
        // Also fetch spacialization
        val urlSp = "http://$ipAddress/?cli.spacial"
        client.newCall(Request.Builder().url(urlSp).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: ""
                // Expected format: ##CLI.SPACIAL#: 1
                if (res.contains("##CLI.SPACIAL#:")) {
                    val sVal = res.substringAfter(":").trim().toIntOrNull() ?: -1
                    if (sVal != -1) {
                        runOnUiThread {
                            v.findViewById<SeekBar>(R.id.sbSpacial).progress = sVal
                            updateSpacialLabel(v, sVal)
                            getSharedPreferences("KaRadioPrefs", MODE_PRIVATE).edit { putInt("eq_spacial", sVal) }
                        }
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun hideKeyboardAndClearFocus(rootView: View, vararg editTexts: EditText) {
        for (et in editTexts) {
            et.clearFocus()
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    // ЛОГИКА ВКЛАДКИ НАСТРОЕК
    private fun setupSettingsTab(v: View) {
        val prefs = getSharedPreferences("KaRadioPrefs", MODE_PRIVATE)
        val etIp = v.findViewById<EditText>(R.id.etSettingsIp)
        etIp.setText(ipAddress)
        etIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboardAndClearFocus(v, etIp)
                true
            } else false
        }

        v.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val newIp = etIp.text.toString().trim()
            if (newIp.isNotEmpty()) {
                ipAddress = newIp
                prefs.edit { putString("device_ip", ipAddress) }
                
                hideKeyboardAndClearFocus(v, etIp)
                
                Toast.makeText(this, getString(R.string.toast_ip_saved), Toast.LENGTH_SHORT).show()
                initWebSocket() // Переподключаемся к новому IP
                fetchFullNetworkInfo(v)
            }
        }

        v.findViewById<Button>(R.id.btnCheckConnection).setOnClickListener {
            checkConnectionStatus()
            fetchFullNetworkInfo(v)
        }

        // Fetch network info on load
        fetchFullNetworkInfo(v)

        // Wi-Fi Setup Apply
        val etSsid1 = v.findViewById<EditText>(R.id.etWifiSsid)
        val etPass1 = v.findViewById<EditText>(R.id.etWifiPass)
        val etSsid2 = v.findViewById<EditText>(R.id.etWifiSsid2)
        val etPass2 = v.findViewById<EditText>(R.id.etWifiPass2)
        val etHost = v.findViewById<EditText>(R.id.etHostname)

        val wifiFields = arrayOf(etSsid1, etPass1, etSsid2, etPass2, etHost)
        for (et in wifiFields) {
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                    hideKeyboardAndClearFocus(v, *wifiFields)
                    true
                } else false
            }
        }

        v.findViewById<Button>(R.id.btnApplyWifi).setOnClickListener {
            val ssid1 = etSsid1.text.toString().trim()
            val pass1 = etPass1.text.toString().trim()
            val ssid2 = etSsid2.text.toString().trim()
            val pass2 = etPass2.text.toString().trim()
            val host = etHost.text.toString().trim()

            hideKeyboardAndClearFocus(v, *wifiFields)

            // Каравин: POST /wifi с полным набором данных
            val body = "valid=1&ssid=$ssid1&pasw=$pass1&ssid2=$ssid2&pasw2=$pass2&host=$host&dhcp=True&ua=KaRadio32&tzo=0:00&"
            sendKarawinCommand("wifi", body)
            
            Toast.makeText(this, getString(R.string.toast_wifi_applying), Toast.LENGTH_SHORT).show()
        }

        v.findViewById<Button>(R.id.btnRefreshWifiList).setOnClickListener {
            refreshWifiList(v)
        }

        // Output Device Setup
        val outputDevices = arrayOf("I2S", "MERUS", "DAC", "PDM", "VS1053", "SPDIF")
        val spinnerOutput = v.findViewById<Spinner>(R.id.spinnerOutputDevice)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, outputDevices)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        spinnerOutput.adapter = adapter

        // Load last known output device from prefs immediately
        val savedOutput = prefs.getInt("output_device", 4) // Default VS1053
        spinnerOutput.setSelection(savedOutput)

        v.findViewById<Button>(R.id.btnApplyOutput).setOnClickListener {
            val selectedIndex = spinnerOutput.selectedItemPosition
            prefs.edit { putInt("output_device", selectedIndex) }
            
            // Каравин: POST /hardware с телом valid=1&coutput=X&
            Toast.makeText(this, getString(R.string.toast_output_applying), Toast.LENGTH_SHORT).show()
            sendKarawinCommand("hardware", "valid=1&coutput=$selectedIndex&")
        }

        fetchCurrentOutputDevice(v)
        fetchCurrentVersion(v)

        // Monitoring Setup
        val switchMon = v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMonitoring)
        switchMon.isChecked = isMonitoring
        switchMon.setOnCheckedChangeListener { _, isChecked ->
            isMonitoring = isChecked
            if (isChecked) {
                Toast.makeText(this, getString(R.string.toast_monitoring_on), Toast.LENGTH_SHORT).show()
                lastMonitoredStationId = null // Trigger update
            } else {
                Toast.makeText(this, getString(R.string.toast_monitoring_off), Toast.LENGTH_SHORT).show()
                stopLocalStream()
            }
        }

        // Updates
        v.findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            fetchLatestVersion(v)
        }

        v.findViewById<Button>(R.id.btnInstallUpdate).setOnClickListener {
            // Каравин: запуск обновления через POST /upgrade
            sendKarawinCommand("upgrade", "")
            v.findViewById<LinearLayout>(R.id.llUpdateProgress).isVisible = true
            v.findViewById<TextView>(R.id.tvUpdateStatus).text = getString(R.string.ota_status_starting)
            v.findViewById<Button>(R.id.btnInstallUpdate).isEnabled = false
            Toast.makeText(this, getString(R.string.toast_ota_request_sent), Toast.LENGTH_SHORT).show()
        }
    }

    // ЛОГИКА ВКЛАДКИ ПЛЕЙЛИСТА
    private fun setupPlaylistTab(v: View) {
        val listView = v.findViewById<ListView>(R.id.listViewStations)
        listAdapter = StationAdapter(this, R.layout.list_item_station, stationList)
        listView.adapter = listAdapter
    }

    inner class StationAdapter(context: android.content.Context, resource: Int, objects: List<String>) :
        ArrayAdapter<String>(context, resource, objects) {

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.list_item_station, parent, false)
            val tvName = view.findViewById<TextView>(R.id.tvStationNameItem)
            val btnEdit = view.findViewById<ImageButton>(R.id.btnEditStation)

            val itemText = getItem(position) ?: ""
            tvName.text = itemText

            val slotId = try {
                itemText.substringBefore(".").trim().toInt()
            } catch (_: Exception) {
                position
            }

            btnEdit.setOnClickListener {
                showEditStationDialog(slotId)
            }

            view.setOnClickListener {
                sendKarawinCommand("play", "id=$slotId&")
                Toast.makeText(this@MainActivity, getString(R.string.station_switch_toast, slotId), Toast.LENGTH_SHORT).show()
            }

            return view
        }
    }

    private fun showEditStationDialog(slotId: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_station, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etName = dialogView.findViewById<EditText>(R.id.etEditName)
        val etFullUrl = dialogView.findViewById<EditText>(R.id.etEditFullUrl)
        val etUrl = dialogView.findViewById<EditText>(R.id.etEditUrlOnly)
        val etPort = dialogView.findViewById<EditText>(R.id.etEditPort)
        val etPath = dialogView.findViewById<EditText>(R.id.etEditPath)
        val sbOvol = dialogView.findViewById<SeekBar>(R.id.sbEditOvol)
        val tvOvolVal = dialogView.findViewById<TextView>(R.id.tvEditOvolVal)

        dialogView.findViewById<EditText>(R.id.etEditSlot).setText(slotId.toString())

        // Fetch current data
        fetchStationDetails(slotId) { json ->
            json?.let {
                runOnUiThread {
                    etName.setText(it.optString("Name"))
                    etUrl.setText(it.optString("URL"))
                    etPort.setText(it.optString("Port"))
                    etPath.setText(it.optString("File"))
                    val ovol = it.optInt("ovol", 0)
                    sbOvol.progress = ovol + 10
                    tvOvolVal.text = ovol.toString()
                }
            }
        }

        // Full URL Parser
        etFullUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val input = s.toString().trim()
                if (input.startsWith("http")) {
                    try {
                        val uri = URI(input)
                        etUrl.setText(uri.host)
                        etPort.setText(if (uri.port != -1) uri.port.toString() else "80")
                        etPath.setText(if (uri.path.isNullOrEmpty()) "/" else uri.path)
                    } catch (_: Exception) {}
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        sbOvol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOvolVal.text = (progress - 10).toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        dialogView.findViewById<Button>(R.id.btnEditSave).setOnClickListener {
            saveStationData(
                slotId,
                etName.text.toString(),
                etUrl.text.toString(),
                etPort.text.toString(),
                etPath.text.toString(),
                sbOvol.progress - 10
            )
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnEditErase).setOnClickListener {
            saveStationData(slotId, "", "", "80", "", 0)
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnEditAbort).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun fetchStationDetails(id: Int, callback: (JSONObject?) -> Unit) {
        sendKarawinCommand("getStation", "idgp=$id&") { success, resp ->
            if (success) {
                try { callback(JSONObject(resp)) } catch (_: Exception) { callback(null) }
            } else { callback(null) }
        }
    }

    private fun saveStationData(id: Int, name: String, url: String, port: String, path: String, ovol: Int) {
        try {
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val body = "nb=1&id=$id&name=$encodedName&url=$url&port=$port&file=$encodedPath&ovol=$ovol&"
            sendKarawinCommand("setStation", body) { success, _ ->
                if (success) {
                    runOnUiThread {
                        Toast.makeText(this, "Station saved", Toast.LENGTH_SHORT).show()
                        syncPlaylistFromEsp()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ФУНКЦИЯ ОПРОСА СТАТУСА ПЛЕЕРА (?infos)
    private fun fetchRadioStatus() {
        // Используем ?infos, так как он легче для ESP32 и предотвращает заикание звука
        val url = "http://$ipAddress/?infos&_ts=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updatePlayerUi(online = false, statusTxt = getString(R.string.status_offline), ch = "---", st = getString(R.string.no_connection), tr = getString(R.string.no_metadata), vol = "0", bit = "---")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val lines = responseText.split("\n")
                    var stName = getString(R.string.status_stopped)
                    var trName = getString(R.string.no_metadata)
                    var chNum = "---"
                    var volVal = "0"
                    var bitRate = "---"
                    var isPlaying = false

                    for (line in lines) {
                        val tr = line.trim()
                        if (tr.startsWith("stn:")) stName = tr.substringAfter("stn:").trim()
                        if (tr.startsWith("tit:")) trName = tr.substringAfter("tit:").trim()
                        if (tr.startsWith("num:")) chNum = tr.substringAfter("num:").trim()
                        if (tr.startsWith("vol:")) volVal = tr.substringAfter("vol:").trim()
                        if (tr.startsWith("bit:")) bitRate = tr.substringAfter("bit:").trim()
                        if (tr.startsWith("sts:")) isPlaying = tr.substringAfter("sts:").trim() == "1"
                    }
                    updatePlayerUi(online = true, statusTxt = if (isPlaying) getString(R.string.status_playing) else getString(R.string.status_stopped), ch = chNum, st = stName, tr = trName, vol = volVal, bit = bitRate)
                } else {
                    updatePlayerUi(online = false, statusTxt = getString(R.string.status_error), ch = "---", st = getString(R.string.server_error), tr = "---", vol = "0", bit = "---")
                }
            }
        })
    }

    private fun updatePlayerUi(online: Boolean, statusTxt: String, ch: String, st: String, tr: String, vol: String = "0", bit: String = "---") {
        runOnUiThread {
            if (currentActiveTab == R.id.nav_player) {
                // Ищем элементы строго внутри активного контейнера currentView
                currentView?.let { view ->
                    val dot = view.findViewById<View>(R.id.viewStatusDot)
                    val tvStatus = view.findViewById<TextView>(R.id.tvStatusText)
                    val tvCh = view.findViewById<TextView>(R.id.tvStationNum)
                    val tvSt = view.findViewById<TextView>(R.id.tvStationName)
                    val tvTr = view.findViewById<TextView>(R.id.tvTrackName)
                    val tvBit = view.findViewById<TextView>(R.id.tvBitrate)

                    dot?.setBackgroundColor(if (online) Color.GREEN else Color.RED)
                    tvStatus?.text = statusTxt
                    tvCh?.text = getString(R.string.station_ch_label, ch)
                    tvSt?.text = st
                    tvTr?.text = getString(R.string.track_name_prefix, tr)
                    tvBit?.text = if (bit == "---") getString(R.string.bitrate_unknown) else getString(R.string.bitrate_val, bit)

                    // Обновляем громкость
                    val vInt = vol.toIntOrNull() ?: 0
                    view.findViewById<SeekBar>(R.id.seekBarVolume)?.progress = vInt
                    val pct = (vInt * 100) / 254
                    view.findViewById<TextView>(R.id.tvVolLabel)?.text = getString(R.string.vol_label, pct)
                }
            }
        }

        // Monitoring Logic
        if (isMonitoring && online && ch != "---" && statusTxt == getString(R.string.status_playing)) {
            if (ch != lastMonitoredStationId) {
                lastMonitoredStationId = ch
                val idInt = ch.toIntOrNull() ?: -1
                if (idInt != -1) {
                    fetchStationDetails(idInt) { json ->
                        json?.let {
                            val host = it.optString("URL")
                            val port = it.optString("Port", "80")
                            val file = it.optString("File", "/")
                            val streamUrl = if (host.isNotEmpty()) "http://$host:$port$file" else ""
                            if (streamUrl.isNotEmpty()) {
                                runOnUiThread { playLocalStream(streamUrl) }
                            }
                        }
                    }
                }
            }
        } else if (isMonitoring && (!online || statusTxt != getString(R.string.status_playing))) {
            stopLocalStream()
            lastMonitoredStationId = null
        }
    }

    private fun playLocalStream(url: String) {
        try {
            stopLocalStream()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                prepareAsync()
            }
        } catch (_: Exception) {}
    }

    private fun stopLocalStream() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    // СИНХРОНИЗАЦИЯ СПИСКА СТАНЦИЙ (?list=)
    private fun syncPlaylistFromEsp() {
        stationMap.clear()
        
        // Заполняем список заглушками (0-254)
        val emptyLabel = getString(R.string.station_empty)
        for (i in 0..254) {
            stationMap[i] = "$i. $emptyLabel"
        }
        
        updateStationList()

        // Запрашиваем станции последовательно, чтобы не перегружать ESP32
        fetchNextStation(0)
    }

    private fun fetchNextStation(index: Int) {
        if (index > 254) return

        val url = "http://$ipAddress/?list=$index"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { name ->
                    val trimmedName = name.trim()
                    if (trimmedName.isNotEmpty() && !trimmedName.equals("null", ignoreCase = true) && !trimmedName.contains("empty", ignoreCase = true)) {
                        synchronized(stationMap) {
                            stationMap[index] = "$index. $trimmedName"
                        }
                        updateStationList()
                    }
                }
                // Запрашиваем следующую через небольшую паузу
                handler.postDelayed({ fetchNextStation(index + 1) }, 50)
            }
            override fun onFailure(call: Call, e: IOException) {
                // В случае ошибки пробуем следующую
                handler.postDelayed({ fetchNextStation(index + 1) }, 100)
            }
        })
    }

    private fun updateStationList() {
        runOnUiThread {
            stationList.clear()
            stationList.addAll(stationMap.values)
            if (currentActiveTab == R.id.nav_playlist && ::listAdapter.isInitialized) {
                listAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun checkConnectionStatus() {
        val url = "http://$ipAddress/?version"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.toast_conn_success), Toast.LENGTH_SHORT).show() }
            }
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.toast_conn_error), Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun fetchFullNetworkInfo(v: View) {
        // Каравин: SSID и настройки сети получаются через POST /wifi с valid=0
        // Это возвращает JSON объект
        sendKarawinCommand("wifi", "valid=0&") { success, resp ->
            if (success && resp.isNotEmpty()) {
                try {
                    val json = JSONObject(resp)
                    val ap1 = json.optString("ssid", "")
                    val ap2 = json.optString("ssid2", "")
                    val host = json.optString("host", "")
                    
                    runOnUiThread {
                        if (ap1.isNotEmpty()) {
                            v.findViewById<TextView>(R.id.tvCurrentWifiSsid).text = getString(R.string.connected_to_ssid, ap1)
                            v.findViewById<EditText>(R.id.etWifiSsid).setText(ap1)
                        }
                        if (ap2.isNotEmpty()) {
                            v.findViewById<EditText>(R.id.etWifiSsid2).setText(ap2)
                        }
                        if (host.isNotEmpty()) {
                            v.findViewById<EditText>(R.id.etHostname).setText(host)
                        }
                    }
                } catch (e: Exception) {
                    addToDebugLog("JSON Error (wifi): ${e.message}")
                }
            }
        }
    }

    private fun refreshWifiList(v: View) {
        val pb = v.findViewById<ProgressBar>(R.id.pbWifiList)
        val tvList = v.findViewById<TextView>(R.id.tvWifiList)
        
        runOnUiThread {
            pb.isVisible = true
            tvList.text = getString(R.string.wifi_scanning)
        }

        // В KaRadio32 список сетей приходит через WebSocket после команды wifi.list
        // HTTP ответ на эту команду обычно пустой.
        sendCommand("wifi.list", false)
        
        // Тайм-аут: если через 10 секунд ничего не пришло, гасим прогресс-бар
        handler.postDelayed({
            runOnUiThread {
                if (pb.isVisible) {
                    pb.isVisible = false
                    if (tvList.text == getString(R.string.wifi_scanning)) {
                        tvList.text = getString(R.string.wifi_scan_finished)
                    }
                }
            }
        }, 10000)
    }

    private fun fetchCurrentOutputDevice(v: View) {
        val url = "http://$ipAddress/?sys.output"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string() ?: ""
                // Expected format: ##SYS.OUTPUT#: 4
                if (res.contains("##SYS.OUTPUT#:")) {
                    val outStr = res.substringAfter(":").trim()
                    val match = Regex("\\d+").find(outStr)
                    val outVal = match?.value?.toIntOrNull() ?: -1

                    if (outVal in 0..5) {
                        runOnUiThread {
                            v.findViewById<Spinner>(R.id.spinnerOutputDevice).setSelection(outVal)
                            // Update local prefs to keep sync
                            getSharedPreferences("KaRadioPrefs", MODE_PRIVATE).edit { putInt("output_device", outVal) }
                        }
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }

    private fun fetchCurrentVersion(v: View) {
        if (ipAddress.isEmpty()) return
        val url = "http://$ipAddress/?version"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val ver = response.body?.string()?.trim() ?: getString(R.string.unknown)
                runOnUiThread {
                    v.findViewById<TextView>(R.id.tvVersionInfo).text = getString(R.string.current_version_label, ver)
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { v.findViewById<TextView>(R.id.tvVersionInfo).text = getString(R.string.version_unknown_label) }
            }
        })
    }

    private fun fetchLatestVersion(v: View) {
        val tvLatest = v.findViewById<TextView>(R.id.tvLatestVersion)
        runOnUiThread {
            tvLatest.isVisible = true
            tvLatest.text = getString(R.string.checking_updates)
        }

        val request = Request.Builder().url("http://karadio.karawin.fr/version32.php").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                val regex = Regex("<span id=\"firmware_last\">([^<]+)</span>")
                val match = regex.find(html)
                val latest = match?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ") ?: "---"
                
                runOnUiThread {
                    tvLatest.text = getString(R.string.latest_version_label, latest)
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvLatest.text = getString(R.string.error_checking_updates)
                }
            }
        })
    }

    private fun addToDebugLog(msg: String) {
        android.util.Log.d("KaRadioDebug", msg)
    }

    private fun sendKarawinCommand(path: String, bodyStr: String, onResult: ((Boolean, String) -> Unit)? = null) {
        try {
            addToDebugLog("POST OUT: /$path | $bodyStr")
            
            val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
            val body = bodyStr.toRequestBody(mediaType)
            val url = "http://$ipAddress/$path"
            
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    addToDebugLog("POST FAIL: $path | ${e.localizedMessage}")
                    runOnUiThread { onResult?.invoke(false, "") }
                }
                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    val respBody = response.body?.string()?.trim() ?: ""
                    response.close()
                    addToDebugLog("POST IN: $path | Code: $code")
                    runOnUiThread { onResult?.invoke(response.isSuccessful, respBody) }
                }
            })
        } catch (e: Exception) {
            addToDebugLog("POST ERR: ${e.localizedMessage}")
            onResult?.invoke(false, "")
        }
    }

    private fun sendLegacyCommand(cmd: String, useCacheBuster: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
        // Оставляем для старых GET команд и совместимости
        try {
            val safeCmd = cmd.replace(" ", "%20")
            val url = if (useCacheBuster) "http://$ipAddress/?$safeCmd&_ts=${System.currentTimeMillis()}" else "http://$ipAddress/?$safeCmd"
            
            addToDebugLog("GET OUT: $cmd")
            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    addToDebugLog("GET FAIL: $cmd | ${e.localizedMessage}")
                    onResult?.invoke(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    response.close()
                    addToDebugLog("GET IN: $cmd | Code: $code")
                    onResult?.invoke(response.isSuccessful)
                }
            })
        } catch (e: Exception) {
            addToDebugLog("GET ERR: ${e.localizedMessage}")
            onResult?.invoke(false)
        }
    }

    private fun sendCommand(cmd: String, useCacheBuster: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
        webSocket?.send(cmd)
        sendLegacyCommand(cmd, useCacheBuster, onResult)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalStream()
        handler.removeCallbacks(pollingRunnable)
        handler.removeCallbacks(wsReconnectRunnable)
        webSocket?.close(1000, "App destroyed")
    }
}