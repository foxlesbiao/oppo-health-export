package com.hermes.dbkeyhook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

class Main : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log("package loaded: ${param.packageName}")
        if (param.packageName != "com.heytap.health") return
        log("target app found, hooking...")
        val cl = param.defaultClassLoader

        try {
            // 1. Hook AesGcmAndroidKeyStore.b() 解密方法
            val ksCls = Class.forName("com.heytap.health.base.encrypt.AesGcmAndroidKeyStore", false, cl)
            val b = ksCls.getDeclaredMethod("b", String::class.java, String::class.java)
            b.isAccessible = true
            log("hooking AesGcmAndroidKeyStore.b()")
            hook(b).intercept { chain ->
                val result = chain.proceed()
                val alias = chain.getArg(0) as? String
                if (result != null) {
                    log("DECRYPTED alias=$alias value=$result")
                    if (alias == "db_key") saveToFile("/data/local/tmp/dbkey_result.txt", result.toString())
                }
                result
            }

            // 2. Hook bj4.a(Context)
            try {
                val bj4 = Class.forName("com.oplus.aiunit.vision.bj4", false, cl)
                val a = bj4.getDeclaredMethod("a", android.content.Context::class.java)
                a.isAccessible = true
                log("hooking bj4.a()")
                hook(a).intercept { chain ->
                    val result = chain.proceed()
                    if (result != null) {
                        log("bj4.a() = $result")
                        saveToFile("/data/local/tmp/dbkey_result.txt", result.toString())
                        // 在此进程触发导出（bj4.a 的参数就是 Context）
                        val ctx = chain.getArg(0) as? android.content.Context
                        if (ctx != null) {
                            triggerExport(ctx, cl, result.toString())
                        }
                    }
                    result
                }
            } catch (t: Throwable) {
                log("bj4 hook fail: $t")
            }

            // 3. Activity.onCreate → 后台虚拟打开数据库（触发完整链路 + 导出）
            try {
                val activityCls = Class.forName("android.app.Activity", false, cl)
                val onCreate = activityCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
                log("setting up virtual db open trigger...")
                hook(onCreate).intercept { chain ->
                    val result = chain.proceed()
                    val act = chain.thisObject
                    if (act is android.app.Activity) {
                        val appCtx = act.applicationContext
                        log("Activity created, scheduling virtual db open")
                        Thread {
                            try {
                                Thread.sleep(3000)
                                log("VIRTUAL: calling bj4.INSTANCE.a(context)")
                                val bj4Cls = Class.forName("com.oplus.aiunit.vision.bj4", false, cl)
                                val instField = bj4Cls.getDeclaredField("INSTANCE")
                                instField.isAccessible = true
                                val bj4Inst = instField.get(null)
                                val aMethod = bj4Cls.getDeclaredMethod("a", android.content.Context::class.java)
                                aMethod.isAccessible = true
                                val key = aMethod.invoke(bj4Inst, appCtx)
                                log("VIRTUAL: bj4.a() = $key")
                                if (key != null) {
                                    saveToFile("/data/local/tmp/dbkey_result.txt", key.toString())
                                    // 触发导出：按 UI 配置处理并上传
                                    triggerExport(appCtx, cl, key.toString())
                                }
                            } catch (ite: java.lang.reflect.InvocationTargetException) {
                                log("VIRTUAL: ITE cause=${ite.cause}")
                            } catch (t: Throwable) {
                                log("VIRTUAL: fail $t")
                            }
                        }.start()
                    }
                    result
                }
            } catch (t: Throwable) {
                log("activity hook fail: $t")
            }

        } catch (t: Throwable) {
            log("init fail: $t")
        }
    }

    private fun triggerExport(appCtx: android.content.Context, cl: ClassLoader, key: String) {
        try {
            Thread {
                try {
                    log("triggering export...")
                    // 小弹窗提示：在主线程弹 Toast（后台线程弹 Toast 在部分版本会崩/被吞）
                    val toast: (String) -> Unit = { msg ->
                        try {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        } catch (t: Throwable) {
                            log("toast fail: $t")
                        }
                    }
                    val ok = ExportWorker(appCtx, cl, key, lspLog = { msg -> log(msg) }, toast = toast).run()
                    log("export done: $ok")
                } catch (t: Throwable) {
                    log("export thread crash: ${t.javaClass.name}: ${t.message}")
                    val sw = java.io.StringWriter()
                    t.printStackTrace(java.io.PrintWriter(sw))
                    log("export stack: " + sw.toString().substring(0, Math.min(400, sw.toString().length)))
                }
            }.start()
        } catch (t: Throwable) {
            log("export trigger fail: $t")
        }
    }

    private fun saveToFile(path: String, content: String) {
        try {
            val f = File(path)
            FileOutputStream(f).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            log("saved to $path")
        } catch (e: Throwable) {
            log("save fail: $e")
        }
    }

    private fun log(msg: String) {
        log(android.util.Log.INFO, "DBKeyHook", msg)
    }
}
