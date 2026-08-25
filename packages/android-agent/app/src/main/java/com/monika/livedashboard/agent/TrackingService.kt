package com.monika.livedashboard.agent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class TrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsStore: SettingsStore

    private var trackingJob: Job? = null
    private var consentUploaded = false
    private var lastConsentKey = ""
    private var lastSentKey = ""
    private var lastSuccessfulReportAt = 0L
    private var lastNotificationText = ""
    private val sentHistoricalHealthKeys = LinkedHashSet<String>()
    private val lastHealthValueByType = mutableMapOf<String, String>()
    private var lastHealthStateUploadAt = 0L
    private var lastHealthBridgeError = ""

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        createNotificationChannel()
        settingsStore.appendLog("服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settingsStore.setRunningEnabled(false)
                settingsStore.appendLog("收到停止指令")
                stopTracking()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                settingsStore.appendLog("收到启动指令")
                startTrackingIfNeeded()
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val shouldRecover = settingsStore.load().isRunningEnabled
        stopTracking(cancelWatchdog = !shouldRecover)
        if (shouldRecover) {
            scheduleWatchdog(20_000)
            settingsStore.appendLog("服务被销毁，已安排自动恢复")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (settingsStore.load().isRunningEnabled) {
            scheduleWatchdog(15_000)
            settingsStore.appendLog("任务被系统移除，已安排自动恢复")
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startTrackingIfNeeded() {
        if (trackingJob?.isActive == true) return

        startForeground(
            NOTIFICATION_ID,
            buildNotification("正在准备监听")
        )
        scheduleWatchdog()

        trackingJob = serviceScope.launch {
            while (isActive) {
                val settings = settingsStore.load()
                scheduleWatchdog(calculateWatchdogDelay(settings))

                if (!settings.isRunningEnabled) {
                    setServiceState("等待启动", "等待用户启动监听")
                    delay(2_000)
                    continue
                }

                if (!settings.consentGiven || (!settings.reportActivity && !settings.reportHealth)) {
                    setServiceState("需要先同意授权")
                    delay(5_000)
                    continue
                }

                val consentKey = "${settings.consentGiven}|${settings.reportActivity}|${settings.reportBattery}|${settings.reportHealth}"
                if (consentKey != lastConsentKey) {
                    consentUploaded = false
                }
                if (!consentUploaded) {
                    consentUploaded = ApiReporter.postConsent(settings)
                    if (consentUploaded) {
                        lastConsentKey = consentKey
                        settingsStore.appendLog("同意状态上传成功")
                    } else {
                        setServiceState("同意状态上传失败，重试中")
                        delay(5_000)
                        continue
                    }
                }

                if (settings.reportHealth) {
                    reportXiaomiHealth(settings)
                }

                if (!settings.reportActivity) {
                    setServiceState("正在上报小米健康数据")
                    delay(settings.heartbeatSeconds * 1_000L)
                    continue
                }

                if (!UsageTracker.hasUsageStatsPermission(this@TrackingService)) {
                    setServiceState("健康数据正常；未授予使用情况访问权限")
                    delay(5_000)
                    continue
                }

                val appInfo = UsageTracker.currentForegroundApp(this@TrackingService)
                if (appInfo == null) {
                    setServiceState("等待前台应用")
                    delay(settings.heartbeatSeconds * 1_000L)
                    continue
                }

                val customRule = settings.customRules.firstOrNull {
                    it.packageName.equals(appInfo.packageName, ignoreCase = true)
                }
                val effectiveAppInfo = if (customRule != null) {
                    appInfo.copy(appName = customRule.customAppName)
                } else {
                    appInfo
                }

                val extras = DeviceContextProvider.readExtras(
                    context = this@TrackingService,
                    customAppName = customRule?.customAppName,
                    customDescription = customRule?.customDescription,
                )
                val musicKey = extras.music
                    ?.let { "${it.app.orEmpty()}|${it.title}|${it.artist.orEmpty()}" }
                    ?: ""
                val timeBucket = effectiveAppInfo.timestampMs / 10_000L
                val dedupKey = "${effectiveAppInfo.packageName}:$timeBucket:$musicKey:${customRule?.customDescription.orEmpty()}"
                val now = System.currentTimeMillis()
                val forceHeartbeat = now - lastSuccessfulReportAt >= FORCE_REPORT_INTERVAL_MS

                if (dedupKey != lastSentKey || forceHeartbeat) {
                    val sent = ApiReporter.postReport(settings, effectiveAppInfo, extras)
                    if (sent) {
                        lastSentKey = dedupKey
                        lastSuccessfulReportAt = now
                        setServiceState(
                            text = buildReportStatus(effectiveAppInfo, extras),
                            logText = "上报成功：${effectiveAppInfo.appName}${formatMusicSuffix(extras.music)}"
                        )
                    } else {
                        setServiceState("上报失败，正在重试")
                    }
                }

                delay(settings.heartbeatSeconds * 1_000L)
            }
        }
    }

    private fun stopTracking(cancelWatchdog: Boolean = true) {
        trackingJob?.cancel()
        trackingJob = null
        consentUploaded = false
        lastConsentKey = ""
        lastSentKey = ""
        lastSuccessfulReportAt = 0L
        lastNotificationText = ""
        sentHistoricalHealthKeys.clear()
        lastHealthValueByType.clear()
        lastHealthStateUploadAt = 0L
        lastHealthBridgeError = ""
        if (cancelWatchdog) {
            cancelWatchdog()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setServiceState(text: String, logText: String = text) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        updateNotificationNow(text)
        settingsStore.appendLog(logText)
    }

    private fun reportXiaomiHealth(settings: AgentSettings) {
        val snapshot = XiaomiHealthCollector.collect(this)
        if (!snapshot.bridgeReady) {
            val error = snapshot.error.orEmpty()
            if (error != lastHealthBridgeError) {
                lastHealthBridgeError = error
                settingsStore.appendLog("小米健康读取不可用：$error")
            }
            return
        }

        val sourceWarning = snapshot.error.orEmpty()
        if (sourceWarning.isNotEmpty() && sourceWarning != lastHealthBridgeError) {
            lastHealthBridgeError = sourceWarning
            settingsStore.appendLog("小米健康部分数据暂不可用：$sourceWarning")
        } else if (sourceWarning.isEmpty() && lastHealthBridgeError.isNotEmpty()) {
            settingsStore.appendLog("小米健康 LSPosed 桥接已连接")
            lastHealthBridgeError = ""
        }

        val now = System.currentTimeMillis()
        val forceState = now - lastHealthStateUploadAt >= HEALTH_STATE_HEARTBEAT_MS
        val pending = snapshot.records.filter { record ->
            if (record.type == "sleep") {
                record.dedupKey() !in sentHistoricalHealthKeys
            } else {
                val valueKey = "${record.value}|${record.unit}" +
                    if (record.type == "heart_rate") "|${record.timestampMs}" else ""
                lastHealthValueByType[record.type] != valueKey ||
                    (record.type == "sleep_state" && forceState)
            }
        }
        if (pending.isEmpty()) return

        if (ApiReporter.postHealthRecords(settings, pending)) {
            pending.forEach { record ->
                if (record.type == "sleep") {
                    sentHistoricalHealthKeys += record.dedupKey()
                    while (sentHistoricalHealthKeys.size > MAX_HEALTH_HISTORY_KEYS) {
                        sentHistoricalHealthKeys.remove(sentHistoricalHealthKeys.first())
                    }
                } else {
                    lastHealthValueByType[record.type] = "${record.value}|${record.unit}" +
                        if (record.type == "heart_rate") "|${record.timestampMs}" else ""
                }
            }
            if (pending.any { it.type == "sleep_state" }) {
                lastHealthStateUploadAt = now
            }
            val sleepLabel = when (snapshot.maybeSleeping) {
                true -> "睡眠中"
                false -> "清醒"
                null -> "未知"
            }
            settingsStore.appendLog("健康数据上报成功：${pending.size} 条，当前$sleepLabel")
        } else {
            settingsStore.appendLog("健康数据上报失败，稍后重试")
        }
    }

    private fun buildReportStatus(appInfo: ForegroundAppInfo, extras: DeviceExtras): String {
        val music = extras.music ?: return "正在上报：${appInfo.appName}"
        return "正在上报：${appInfo.appName} · ♪ ${music.title}"
    }

    private fun formatMusicSuffix(music: MusicInfo?): String {
        if (music == null) return ""
        val artist = music.artist?.takeIf { it.isNotBlank() }
        return if (artist != null) {
            "（音乐：${artist} - ${music.title}）"
        } else {
            "（音乐：${music.title}）"
        }
    }

    private fun createWatchdogIntent(): PendingIntent {
        val intent = Intent(this, ServiceWatchdogReceiver::class.java).apply {
            action = ServiceWatchdogReceiver.ACTION_WATCHDOG
        }
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWatchdog(delayMs: Long = DEFAULT_WATCHDOG_DELAY_MS) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            createWatchdogIntent(),
        )
    }

    private fun cancelWatchdog() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.cancel(createWatchdogIntent())
    }

    private fun calculateWatchdogDelay(settings: AgentSettings): Long {
        return max(90_000L, settings.heartbeatSeconds * 3_000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "实时看板助手",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "持续上报手机前台应用状态到实时看板"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("实时看板助手")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotificationNow(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.monika.livedashboard.agent.action.START"
        const val ACTION_STOP = "com.monika.livedashboard.agent.action.STOP"

        private const val CHANNEL_ID = "live_dashboard_agent_channel"
        private const val NOTIFICATION_ID = 11031
        private const val DEFAULT_WATCHDOG_DELAY_MS = 120_000L
        private const val FORCE_REPORT_INTERVAL_MS = 50_000L
        private const val HEALTH_STATE_HEARTBEAT_MS = 5 * 60_000L
        private const val MAX_HEALTH_HISTORY_KEYS = 64
    }
}
