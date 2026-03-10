package com.example.adb_terminal_android

import dadb.AdbKeyPair
import dadb.Dadb
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "adb_terminal"
    }

    private var dadb: Dadb? = null
    private var keyPair: AdbKeyPair? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "connect" -> {
                        val host = call.argument<String>("host") ?: run {
                            result.error("INVALID_ARGS", "host required", null)
                            return@setMethodCallHandler
                        }
                        val port = call.argument<Int>("port") ?: 5555
                        Thread {
                            try {
                                dadb?.close()
                                dadb = null
                                val kp = getOrCreateKeyPair()
                                dadb = Dadb.create(host, port, kp)
                                runOnUiThread { result.success("Подключено к $host:$port") }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    result.error("CONNECT_ERROR", e.message ?: "Connection failed", null)
                                }
                            }
                        }.start()
                    }

                    "execute" -> {
                        val command = call.argument<String>("command") ?: run {
                            result.error("INVALID_ARGS", "command required", null)
                            return@setMethodCallHandler
                        }
                        val d = dadb ?: run {
                            result.error("NOT_CONNECTED", "Нет подключения к устройству", null)
                            return@setMethodCallHandler
                        }
                        Thread {
                            try {
                                val response = d.shell(command)
                                val out = buildString {
                                    if (response.output.isNotEmpty()) append(response.output)
                                    if (response.errorOutput.isNotEmpty()) append(response.errorOutput)
                                }
                                runOnUiThread { result.success(out.trimEnd().ifEmpty { "(нет вывода)" }) }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    result.error("EXEC_ERROR", e.message ?: "Execution failed", null)
                                }
                            }
                        }.start()
                    }

                    "disconnect" -> {
                        try {
                            dadb?.close()
                            dadb = null
                            result.success("Отключено")
                        } catch (e: Exception) {
                            result.error("DISCONNECT_ERROR", e.message, null)
                        }
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun getOrCreateKeyPair(): AdbKeyPair {
        keyPair?.let { return it }
        val kp = AdbKeyPair.generate()
        keyPair = kp
        return kp
    }

    override fun onDestroy() {
        super.onDestroy()
        try { dadb?.close() } catch (_: Exception) {}
    }
}
