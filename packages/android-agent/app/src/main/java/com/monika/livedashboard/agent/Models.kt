package com.monika.livedashboard.agent

data class AgentSettings(
    val serverUrl: String = "",
    val token: String = "",
    val heartbeatSeconds: Int = 30,
    val consentGiven: Boolean = false,
    val reportActivity: Boolean = true,
    val reportBattery: Boolean = true,
    val reportHealth: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val isRunningEnabled: Boolean = false,
    val customRules: List<AppCustomRule> = emptyList(),
)

data class AppCustomRule(
    val packageName: String,
    val customAppName: String,
    val customDescription: String? = null,
)
