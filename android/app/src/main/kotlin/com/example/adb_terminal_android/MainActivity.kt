package com.example.adb_terminal_android

import io.flutter.embedding.android.FlutterActivity
import java.io.File
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "adb_terminal"
    }

    private val adb by lazy { AdbClient(keyFile = File(filesDir, "adb_key.pk8")) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "connect" -> {
                        val host = call.argument<String>("host")
                        val port = call.argument<Int>("port") ?: 5555
                        if (host.isNullOrBlank()) {
                            result.error("INVALID_ARGS", "host required", null)
                            return@setMethodCallHandler
                        }
                        Thread {
                            try {
                                adb.close()
                                adb.connect(host, port)
                                runOnUiThread { result.success("Подключено к $host:$port") }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    result.error("CONNECT_ERROR", e.message ?: "Ошибка подключения", null)
                                }
                            }
                        }.start()
                    }

                    "execute" -> {
                        val command = call.argument<String>("command")
                        if (command.isNullOrBlank()) {
                            result.error("INVALID_ARGS", "command required", null)
                            return@setMethodCallHandler
                        }
                        if (!adb.isConnected()) {
                            result.error("NOT_CONNECTED", "Нет подключения к устройству", null)
                            return@setMethodCallHandler
                        }
                        Thread {
                            try {
                                val output = adb.shell(command)
                                runOnUiThread { result.success(output) }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    result.error("EXEC_ERROR", e.message ?: "Ошибка выполнения", null)
                                }
                            }
                        }.start()
                    }

                    "disconnect" -> {
                        adb.close()
                        result.success("Отключено")
                    }

                    else -> result.notImplemented()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        adb.close()
    }
}