package com.monika.livedashboard.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = SettingsStore(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(settingsStore = settingsStore)
                }
            }
        }
    }
}

@Composable
private fun AgentScreen(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val initial = remember { settingsStore.load() }

    var serverUrl by rememberSaveable { mutableStateOf(initial.serverUrl) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var heartbeatText by rememberSaveable { mutableStateOf(initial.heartbeatSeconds.toString()) }

    var consentGiven by rememberSaveable { mutableStateOf(initial.consentGiven) }
    var reportActivity by rememberSaveable { mutableStateOf(initial.reportActivity) }
    var reportBattery by rememberSaveable { mutableStateOf(initial.reportBattery) }
    var reportHealth by rememberSaveable { mutableStateOf(initial.reportHealth) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var runningEnabled by rememberSaveable { mutableStateOf(initial.isRunningEnabled) }
    var customRules by remember { mutableStateOf(initial.customRules) }
    var customRulePackage by rememberSaveable { mutableStateOf("") }
    var customRuleName by rememberSaveable { mutableStateOf("") }
    var customRuleDescription by rememberSaveable { mutableStateOf("") }
    var statusText by rememberSaveable { mutableStateOf("空闲") }
    var logs by remember { mutableStateOf(settingsStore.loadLogs(80)) }

    fun refreshLogs() {
        logs = settingsStore.loadLogs(80)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("实时看板 Root 模块", style = MaterialTheme.typography.headlineSmall)
        Text(
            "配置 LSPosed 系统钩子与 KernelSU 轻量守护进程；关闭此界面后仍可持续上报。",
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("运行状态", style = MaterialTheme.typography.titleMedium)
                Text(if (runningEnabled) "Root 上报：已启用" else "Root 上报：未启用")
                Text("LSPosed 作用域：系统框架（android）+ 小米运动健康")
                Text("无需使用情况权限、通知读取权限或电池白名单")
                Text("状态：$statusText")
            }
        }

        HorizontalDivider()
        Text("基础配置", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            singleLine = true,
            placeholder = { Text("http://192.168.1.10:3000 或 https://example.com") }
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Token 密钥") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("显示密钥")
            Switch(checked = tokenVisible, onCheckedChange = { tokenVisible = it })
        }

        OutlinedTextField(
            value = heartbeatText,
            onValueChange = { heartbeatText = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("心跳间隔（秒，10-50）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("上报前台应用活动")
            Switch(checked = reportActivity, onCheckedChange = { reportActivity = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("附带电量状态")
            Switch(checked = reportBattery, onCheckedChange = { reportBattery = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                Text("上报小米运动健康数据")
                Text(
                    "睡眠/实时睡眠状态、步数、距离、卡路里和最近心率",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = reportHealth, onCheckedChange = { reportHealth = it })
        }

        if (reportHealth) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("小米健康桥接", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isXiaomiHealthInstalled(context)) {
                            "已检测到小米运动健康。当前 APK 已内置采集钩子，请在 LSPosed 中勾选“小米运动健康”和“系统框架”两个作用域。"
                        } else {
                            "未检测到小米运动健康（com.mi.health）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("KernelSU 守护进程随系统启动；是否上报由上方的 Root 上报开关控制。")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Checkbox(checked = consentGiven, onCheckedChange = { consentGiven = it })
            Text(
                "我已了解并同意上传所选设备活动及健康数据。",
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        HorizontalDivider()
        Text("应用识别与自定义文案", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = customRulePackage,
            onValueChange = { customRulePackage = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("应用包名") },
            singleLine = true,
            placeholder = { Text("如: com.example.app") }
        )

        OutlinedTextField(
            value = customRuleName,
            onValueChange = { customRuleName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义应用名") },
            singleLine = true,
            placeholder = { Text("如: 我的学习应用") }
        )

        OutlinedTextField(
            value = customRuleDescription,
            onValueChange = { customRuleDescription = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义文案（可选）") },
            singleLine = true,
            placeholder = { Text("如: 正在专注刷题喵~") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val packageName = customRulePackage.trim()
                val customName = customRuleName.trim()
                if (packageName.isBlank() || customName.isBlank()) {
                    statusText = "包名和自定义应用名不能为空。"
                    return@Button
                }

                val normalized = AppCustomRule(
                    packageName = packageName,
                    customAppName = customName,
                    customDescription = customRuleDescription.trim().ifBlank { null },
                )

                customRules = customRules
                    .filterNot { it.packageName.equals(packageName, ignoreCase = true) }
                    .plus(normalized)
                customRulePackage = ""
                customRuleName = ""
                customRuleDescription = ""
                statusText = "自定义规则已添加（记得点保存设置）。"
            }) {
                Text("添加 / 更新规则")
            }
        }

        if (customRules.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                customRules.forEach { rule ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("包名: ${rule.packageName}", style = MaterialTheme.typography.bodySmall)
                            Text("应用名: ${rule.customAppName}", style = MaterialTheme.typography.bodyMedium)
                            rule.customDescription?.let {
                                Text("文案: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    customRules = customRules.filterNot {
                                        it.packageName.equals(rule.packageName, ignoreCase = true)
                                    }
                                    statusText = "已删除规则（记得点保存设置）。"
                                },
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text("删除规则")
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("模块状态", style = MaterialTheme.typography.titleMedium)

        Button(onClick = {
            statusText = RootDaemonConfig.readStatus()
        }) {
            Text("读取 KernelSU 守护进程状态")
        }

        HorizontalDivider()
        Text("操作", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val heartbeat = heartbeatText.toIntOrNull()?.coerceIn(10, 50) ?: 30
                    val normalizedServer = serverUrl.trim().trimEnd('/')
                    if (!isServerUrlAllowed(normalizedServer)) {
                        statusText = "服务器地址必须是有效的 http:// 或 https:// 地址。"
                        settingsStore.appendLog("保存设置失败：服务器地址不符合要求")
                        refreshLogs()
                        return@Button
                    }
                    if (token.trim().isBlank()) {
                        statusText = "必须填写 Token 密钥。"
                        settingsStore.appendLog("保存设置失败：未填写 Token")
                        refreshLogs()
                        return@Button
                    }

                    val saved = AgentSettings(
                            serverUrl = normalizedServer,
                            token = token.trim(),
                            heartbeatSeconds = heartbeat,
                            consentGiven = consentGiven,
                            reportActivity = reportActivity,
                            reportBattery = reportBattery,
                            reportHealth = reportHealth,
                            autoStartOnBoot = true,
                            isRunningEnabled = runningEnabled,
                            customRules = customRules,
                        )
                    settingsStore.save(saved)
                    val result = RootDaemonConfig.sync(saved)
                    statusText = result.fold(
                        onSuccess = { "设置已同步到 KernelSU 守护进程。" },
                        onFailure = { "本地设置已保存，但 Root 同步失败：${it.message}" },
                    )
                    settingsStore.appendLog(statusText)
                    refreshLogs()
                }
            ) {
                Text("保存设置")
            }

            Button(
                onClick = {
                    if (!consentGiven) {
                        statusText = "启动前必须先同意授权。"
                        settingsStore.appendLog("启动失败：未勾选同意")
                        refreshLogs()
                        return@Button
                    }
                    if (!reportActivity && !reportHealth) {
                        statusText = "请至少开启活动上报或健康数据上报。"
                        settingsStore.appendLog("启动失败：未选择任何上报项目")
                        refreshLogs()
                        return@Button
                    }
                    val heartbeat = heartbeatText.toIntOrNull()?.coerceIn(10, 50) ?: 30
                    val normalizedServer = serverUrl.trim().trimEnd('/')
                    if (!isServerUrlAllowed(normalizedServer) || token.trim().isBlank()) {
                        statusText = "请填写有效的服务器地址和 Token 密钥。"
                        settingsStore.appendLog("启动失败：服务器地址或 Token 无效")
                        refreshLogs()
                        return@Button
                    }

                    val started = AgentSettings(
                            serverUrl = normalizedServer,
                            token = token.trim(),
                            heartbeatSeconds = heartbeat,
                            consentGiven = consentGiven,
                            reportActivity = reportActivity,
                            reportBattery = reportBattery,
                            reportHealth = reportHealth,
                            autoStartOnBoot = true,
                            isRunningEnabled = true,
                            customRules = customRules,
                        )
                    settingsStore.save(started)
                    val result = RootDaemonConfig.sync(started)
                    if (result.isSuccess) runningEnabled = true
                    statusText = result.fold(
                        onSuccess = { "Root 上报已启用；助手界面可以直接关闭。" },
                        onFailure = { "启用失败：${it.message}" },
                    )
                    settingsStore.appendLog(statusText)
                    refreshLogs()
                }
            ) {
                Text("开始监听")
            }

            Button(
                onClick = {
                    val stopped = settingsStore.load().copy(isRunningEnabled = false)
                    settingsStore.save(stopped)
                    val result = RootDaemonConfig.sync(stopped)
                    if (result.isSuccess) runningEnabled = false
                    statusText = result.fold(
                        onSuccess = { "Root 上报已停止。" },
                        onFailure = { "停止失败：${it.message}" },
                    )
                    settingsStore.appendLog(statusText)
                    refreshLogs()
                }
            ) {
                Text("停止监听")
            }
        }

        HorizontalDivider()
        Text("运行日志", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                refreshLogs()
                statusText = "日志已刷新。"
            }) {
                Text("刷新日志")
            }
            Button(onClick = {
                settingsStore.clearLogs()
                refreshLogs()
                statusText = "日志已清空。"
            }) {
                Text("清空日志")
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(12.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("暂无日志")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        logs.asReversed().take(60).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isServerUrlAllowed(value: String): Boolean {
    if (value.isBlank()) return false

    return try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        (scheme == "http" || scheme == "https") && host.isNotBlank()
    } catch (_: Exception) {
        false
    }
}

private fun isXiaomiHealthInstalled(context: android.content.Context): Boolean =
    context.packageManager.resolveContentProvider("com.mi.health.provider.main", 0) != null
