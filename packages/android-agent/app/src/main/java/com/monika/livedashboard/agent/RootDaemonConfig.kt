package com.monika.livedashboard.agent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object RootDaemonConfig {
    private const val CONFIG_PATH = "/data/adb/live_dashboard/config.json"

    fun sync(settings: AgentSettings): Result<Unit> = runCatching {
        val rules = JSONArray().apply {
            settings.customRules.forEach { rule ->
                put(
                    JSONObject()
                        .put("package_name", rule.packageName.trim())
                        .put("custom_app_name", rule.customAppName.trim())
                        .put("custom_description", rule.customDescription.orEmpty().trim())
                )
            }
        }
        val config = JSONObject()
            .put("enabled", settings.isRunningEnabled)
            .put("server_url", settings.serverUrl.trim().trimEnd('/'))
            .put("token", settings.token.trim())
            .put("heartbeat_seconds", settings.heartbeatSeconds.coerceIn(10, 300))
            .put("report_activity", settings.reportActivity)
            .put("report_battery", settings.reportBattery)
            .put("report_health", settings.reportHealth)
            .put("custom_rules", rules)

        val encoded = Base64.encodeToString(
            config.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val command = "mkdir -p /data/adb/live_dashboard && chmod 700 /data/adb/live_dashboard " +
            "&& base64 -d > $CONFIG_PATH.tmp && chmod 600 $CONFIG_PATH.tmp " +
            "&& mv $CONFIG_PATH.tmp $CONFIG_PATH"
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
            it.write(encoded)
            it.newLine()
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Root 配置写入超时")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.exitValue() != 0) {
            error(output.ifBlank { "Root 权限被拒绝或 KernelSU 模块未安装" })
        }
    }

    fun readStatus(): String = runCatching {
        val process = ProcessBuilder(
            "su", "-c", "cat /data/adb/live_dashboard/status.json 2>/dev/null"
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching "守护进程状态读取超时"
        }
        val raw = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (raw.isBlank()) return@runCatching "尚未检测到 KernelSU 守护进程"
        val status = JSONObject(raw)
        val enabled = status.optBoolean("enabled", false)
        val connected = status.optBoolean("connected", false)
        val pending = status.optInt("pending_health", 0)
        val error = status.optString("last_error")
        buildString {
            append(if (enabled) "守护进程已启用" else "守护进程未启用")
            append(if (connected) "，服务器连接正常" else "，等待数据或服务器连接")
            if (pending > 0) append("，待发送健康记录 $pending 条")
            if (error.isNotBlank()) append("；最近错误：$error")
        }
    }.getOrElse { "读取失败：${it.message}" }
}
