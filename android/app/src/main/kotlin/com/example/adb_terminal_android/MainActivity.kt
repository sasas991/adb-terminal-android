package com.example.adb_terminal_android

import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "adb_terminal"
        const val ADB_PORT = "15037"
        const val TIMEOUT_SECONDS = 30L
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getAdbPath" -> {
                        val path = getAdbPath()
                        if (path != null) result.success(path)
                        else result.error("NOT_FOUND", "ADB binary not found in nativeLibraryDir", null)
                    }

                    "execute" -> {
                        val args = call.argument<List<String>>("args")
                        if (args == null) {
                            result.error("INVALID_ARGS", "args is required", null)
                            return@setMethodCallHandler
                        }
                        Thread {
                            try {
                                val output = executeAdb(args)
                                runOnUiThread { result.success(output) }
                            } catch (e: Exception) {
                                runOnUiThread { result.error("EXEC_ERROR", e.message, null) }
                            }
                        }.start()
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun getAdbPath(): String? {
        val path = "${applicationInfo.nativeLibraryDir}/libadb.so"
        return if (File(path).exists()) path else null
    }

    private fun executeAdb(args: List<String>): String {
        val adbPath = getAdbPath() ?: throw Exception("ADB binary not found")

        val cmd = listOf(adbPath) + args
        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment().apply {
                put("ANDROID_ADB_SERVER_PORT", ADB_PORT)
                put("HOME", filesDir.absolutePath)
                put("TMPDIR", cacheDir.absolutePath)
            }
        }

        val process = pb.start()
        // Close stdin so interactive commands don't hang
        process.outputStream.close()

        var output = ""
        val readerThread = Thread {
            output = process.inputStream.bufferedReader().readText()
        }
        readerThread.start()

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            readerThread.join(2000)
            return output.ifEmpty { "(timeout after ${TIMEOUT_SECONDS}s)" }
        }
        readerThread.join(2000)
        return output.ifEmpty { "(no output)" }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val path = getAdbPath() ?: return
            ProcessBuilder(path, "kill-server").apply {
                environment()["ANDROID_ADB_SERVER_PORT"] = ADB_PORT
                environment()["HOME"] = filesDir.absolutePath
            }.start().waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }
}
