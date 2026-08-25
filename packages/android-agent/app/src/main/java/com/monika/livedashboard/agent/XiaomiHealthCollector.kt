package com.monika.livedashboard.agent

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

data class XiaomiHealthSnapshot(
    val records: List<HealthRecordPayload>,
    val maybeSleeping: Boolean?,
    val bridgeReady: Boolean,
    val error: String? = null,
)

/**
 * Reads Xiaomi Fitness' own data provider. The companion LSPosed module grants
 * read access only to this agent package; no Xiaomi database files are copied.
 */
object XiaomiHealthCollector {
    private const val TAG = "XiaomiHealthCollector"
    private const val AUTHORITY = "com.mi.health.provider.main"
    private const val BASE_URI = "content://$AUTHORITY"
    private const val EXTRA_MAYBE_SLEEPING = "extra_maybe_sleeping"
    private const val HISTORY_DAYS = 7L

    fun isXiaomiHealthInstalled(context: Context): Boolean =
        context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    fun collect(context: Context): XiaomiHealthSnapshot {
        if (!isXiaomiHealthInstalled(context)) {
            return XiaomiHealthSnapshot(emptyList(), null, false, "未安装小米运动健康")
        }

        return try {
            val resolver = context.contentResolver
            val records = mutableListOf<HealthRecordPayload>()
            val sleeping = readSleepingState(resolver)
            records += HealthRecordPayload(
                type = "sleep_state",
                value = if (sleeping) 1.0 else 0.0,
                unit = "state",
                timestampMs = System.currentTimeMillis(),
            )
            val sourceErrors = mutableListOf<String>()
            runCatching { records += readSleepReports(resolver) }
                .onFailure {
                    Log.w(TAG, "Sleep report read failed", it)
                    sourceErrors += "睡眠记录"
                }
            runCatching { records += readActivitySummary(resolver) }
                .onFailure {
                    Log.w(TAG, "Activity summary read failed", it)
                    sourceErrors += "活动汇总"
                }
            runCatching { readRecentHeartRate(resolver)?.let(records::add) }
                .onFailure {
                    Log.w(TAG, "Heart rate read failed", it)
                    sourceErrors += "心率"
                }
            XiaomiHealthSnapshot(
                records = records,
                maybeSleeping = sleeping,
                bridgeReady = true,
                error = sourceErrors.takeIf { it.isNotEmpty() }?.joinToString("、"),
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "LSPosed permission bridge is not active", error)
            XiaomiHealthSnapshot(emptyList(), null, false, "LSPosed 桥接未生效")
        } catch (error: Exception) {
            Log.w(TAG, "Xiaomi health read failed", error)
            XiaomiHealthSnapshot(emptyList(), null, false, error.message ?: "读取失败")
        }
    }

    private fun readSleepingState(resolver: ContentResolver): Boolean {
        val result: Bundle = resolver.call(
            AUTHORITY,
            "$BASE_URI/sleep#is_maybe_sleeping",
            null,
            null,
        ) ?: Bundle.EMPTY
        return result.getBoolean(EXTRA_MAYBE_SLEEPING, false)
    }

    private fun readSleepReports(resolver: ContentResolver): List<HealthRecordPayload> {
        val result = mutableListOf<HealthRecordPayload>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        for (daysAgo in 0L..HISTORY_DAYS) {
            val dayMillis = today
                .minusDays(daysAgo)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            resolver.query(
                Uri.parse("$BASE_URI/sleep/report"),
                arrayOf("sleep_time", "wake_time", "duration"),
                "date_time = ?",
                arrayOf(dayMillis.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val start = cursor.longOrNull("sleep_time") ?: return@use
                val wake = cursor.longOrNull("wake_time")
                val durationMs = cursor.longOrNull("duration") ?: 0L
                if (start > 0L && durationMs > 0L) {
                    result += HealthRecordPayload(
                        type = "sleep",
                        value = durationMs / 60_000.0,
                        unit = "min",
                        timestampMs = start,
                        endTimeMs = wake?.takeIf { it > start },
                    )
                }
            }
        }
        return result
    }

    private fun readActivitySummary(resolver: ContentResolver): List<HealthRecordPayload> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<HealthRecordPayload>()
        resolver.query(
            Uri.parse("$BASE_URI/activity/steps/brief"),
            arrayOf("steps", "distance", "energy"),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            cursor.doubleOrNull("steps")?.let {
                result += HealthRecordPayload("steps", it, "steps", now)
            }
            cursor.doubleOrNull("distance")?.let {
                // Xiaomi's brief provider exposes kilometres; the dashboard stores metres.
                result += HealthRecordPayload("distance", it * 1_000.0, "m", now)
            }
            cursor.doubleOrNull("energy")?.let {
                result += HealthRecordPayload("active_calories", it, "kcal", now)
            }
        }
        return result
    }

    private fun readRecentHeartRate(resolver: ContentResolver): HealthRecordPayload? {
        resolver.query(
            Uri.parse("$BASE_URI/heartrate/recent"),
            arrayOf("hrm", "timestamp"),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bpm = cursor.doubleOrNull("hrm") ?: return null
            val timestamp = cursor.longOrNull("timestamp") ?: return null
            if (bpm > 0 && timestamp > 0) {
                return HealthRecordPayload("heart_rate", bpm, "bpm", timestamp)
            }
        }
        return null
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getDouble(index) else null
    }
}
