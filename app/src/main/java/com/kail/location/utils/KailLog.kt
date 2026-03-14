package com.kail.location.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import com.kail.location.viewmodels.SettingsViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * KailLog 专门用于日志输出与保存。
 * 支持高频与低频日志区分。
 * 高频日志仅在控制台输出；低频日志在控制台输出，并根据设置保存到公共目录文件。
 */
object KailLog {
    private const val TAG_PREFIX = "KailLog_"
    private val logExecutor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 输出日志。
     *
     * @param context 上下文，用于读取偏好设置。若为 null 则尝试自动获取或仅输出到控制台。
     * @param tag 日志标签。
     * @param message 日志内容。
     * @param isHighFrequency 是否为高频日志。如果是，则不保存到本地。
     * @param level 日志级别：'d', 'i', 'w', 'e'。
     */
    fun log(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false, level: Char = 'd') {
        // 尝试解析 Context，如果传入为 null
        val resolvedContext = context ?: resolveContext()
        
        // 安全读取偏好设置：在 system_server (package "android") 或某些环境下可能无法获取 SharedPreferences
        val logEnabled = kotlin.runCatching {
            if (resolvedContext != null && resolvedContext.packageName != "android") {
                PreferenceManager.getDefaultSharedPreferences(resolvedContext)
                    .getBoolean(SettingsViewModel.KEY_LOG_ENABLED, false)
            } else {
                true // 系统进程或拿不到 Context 时默认开启
            }
        }.getOrDefault(true)
        
        // 如果日志开关未开启，则不进行任何操作
        if (!logEnabled) return

        val callerFileName = getCallerFileName()
        val freqIndicator = if (isHighFrequency) "[H]" else "[L]"
        val fullMessage = "$freqIndicator [$callerFileName] $message"
        val fullTag = TAG_PREFIX + tag
        
        // 无论高低频，只要开关开启都在控制台输出
        when (level.lowercaseChar()) {
            'i' -> Log.i(fullTag, fullMessage)
            'w' -> Log.w(fullTag, fullMessage)
            'e' -> Log.e(fullTag, fullMessage)
            else -> Log.d(fullTag, fullMessage)
        }

        // 只有低频日志才保存到公共目录
        // 移除包名限制，以便 Xposed 注入的代码也能在公共目录留下痕迹
        if (!isHighFrequency) {
            saveLogToPublicFile(fullTag, fullMessage, level)
        }
    }

    fun d(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'd')
    fun i(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'i')
    fun w(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'w')
    fun e(context: Context?, tag: String, message: String, isHighFrequency: Boolean = false) = log(context, tag, message, isHighFrequency, 'e')

    /**
     * 在 Xposed 环境下尝试获取 Application Context
     */
    private fun resolveContext(): Context? {
        return kotlin.runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * 获取调用者的文件名。
     */
    private fun getCallerFileName(): String {
        val stackTrace = Thread.currentThread().stackTrace
        // 寻找第一个不是 KailLog 且不是 Thread 的堆栈帧
        for (i in 2 until stackTrace.size) {
            val frame = stackTrace[i]
            if (frame.className != KailLog::class.java.name && !frame.className.contains("java.lang.Thread")) {
                return frame.fileName ?: "Unknown"
            }
        }
        return "Unknown"
    }

    /**
     * 将日志保存到外部存储的公共目录（Documents/KailLocation/logs）
     * 仅使用硬编码路径以避开 Environment API 在系统进程中的限制。
     */
    private fun saveLogToPublicFile(tag: String, message: String, level: Char) {
        logExecutor.execute {
            try {
                val timeStamp = dateFormat.format(Date())
                val fileName = "kail_log_${fileNameFormat.format(Date())}.txt"
                val logEntry = "$timeStamp [${level.uppercaseChar()}] [$tag]: $message\n"
                
                // 仅使用硬编码公共路径
                val publicDir = File("/sdcard/Documents/KailLocation/logs")
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                
                val logFile = File(publicDir, fileName)
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(logEntry.toByteArray())
                }
            } catch (e: Exception) {
                // 如果常规写入失败（常见于权限问题），尝试使用 su 兜底
                val timeStamp = dateFormat.format(Date())
                val fileName = "kail_log_${fileNameFormat.format(Date())}.txt"
                val logEntry = "$timeStamp [${level.uppercaseChar()}] [$tag]: $message\n"
                suAppend("/sdcard/Documents/KailLocation/logs/$fileName", logEntry)
            }
        }
    }

    /**
     * 最后的兜底方案：尝试通过 su 命令写入日志
     */
    private fun suAppend(path: String, payload: String) {
        kotlin.runCatching {
            val file = File(path)
            val parent = file.parentFile?.absolutePath ?: "/sdcard"
            val escaped = payload
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`")
            val cmd = "mkdir -p \"$parent\" && printf \"%s\" \"$escaped\" >> \"$path\""
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        }
    }
}
