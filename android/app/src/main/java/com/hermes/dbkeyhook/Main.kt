package com.hermes.dbkeyhook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * v5.2: 移除对混淆类 bj4 的硬编码依赖（bj4 类名/方法名/字段名跨构建漂移，
 * 实测 NoSuchMethodException a / NoSuchFieldException INSTANCE）。
 *
 * 改为稳定锚点策略：
 *  1. hook AesGcmAndroidKeyStore 解密方法（b() 优先，缺失则自动发现 (String,String)->String 实例方法）
 *  2. hook SQLCipher openDatabase(path, password, ...)：密码参数即 db_key，打开数据库必经，混淆免疫
 *  3. Activity.onCreate 轮询等待 db_key（hook 缓存 / 文件），就绪后自动导出
 *  4. 不再虚拟调用 bj4.INSTANCE.a()
 * v5.2.2: 修复 UI 读不到 dbkey_result.txt（saveToFile 0600 属主是健康 App，UI UID 不同）；
 *         新增 SQLCipher openDatabase 锚点（修复新版 App 启动不解密 db_key 导致真机拿不到 key）。
 */
class Main : XposedModule() {

    @Volatile
    private var cachedDbKey: String? = null

    companion object {
        @Volatile private var exportScheduled = false
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log("package loaded: ${param.packageName}")
        if (param.packageName != "com.heytap.health") return
        log("target app found, hooking...")
        val cl = param.defaultClassLoader

        // ── 1. 稳定锚点：hook AesGcmAndroidKeyStore 解密方法 ──
        try {
            val ksCls = Class.forName("com.heytap.health.base.encrypt.AesGcmAndroidKeyStore", false, cl)
            val decryptMethods = findLikelyDecryptMethods(ksCls)
            if (decryptMethods.isEmpty()) {
                log("WARN: no (String,String)->String method found in ${ksCls.name}")
            }
            for (m in decryptMethods) {
                m.isAccessible = true
                log("hooking ${ksCls.name}.${m.name}()")
                hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val alias = chain.getArg(0) as? String
                    val value = result?.toString()
                    if (value != null && alias != null) {
                        // 日志脱敏: 只打印长度, 不打印 key 内容(logcat 可被任何有 adb 权限的进程读)
                        log("DECRYPTED alias=$alias len=${value.length}")
                        appendToFile("/data/local/tmp/dbkey_all.txt", "$alias(len=${value.length})")
                        // db_key 单独存（导出用）；chmod 644 让模块 UI 进程也能读
                        if (alias == "db_key") {
                            cachedDbKey = value
                            saveAndShareKey(value)
                        }
                    }
                    result
                }
            }
        } catch (t: Throwable) {
            log("keystore hook setup fail: $t")
        }

        // ── 2. 稳定锚点 #2：hook SQLCipher openDatabase，密码参数即 db_key ──
        // 新版 App 启动未必立即解密 db_key（走 AesGcmAndroidKeyStore.b 的时机不定），
        // 但只要打开数据库必然调用 openDatabase(path, password, ...)，混淆免疫。
        try {
            for (cn in listOf(
                "net.zetetic.database.sqlcipher.SQLiteDatabase",
                "net.sqlcipher.database.SQLiteDatabase",
            )) {
                val sqlCls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { continue }
                for (m in sqlCls.declaredMethods) {
                    if (m.name != "openDatabase" && m.name != "openOrCreateDatabase") continue
                    val ps = m.parameterTypes
                    // openDatabase(path, password, ...)：前两个参数都是 String
                    if (ps.size < 2 || ps[0] != String::class.java || ps[1] != String::class.java) continue
                    m.isAccessible = true
                    log("hooking $cn.${m.name}(path, password, ...)")
                    hook(m).intercept { chain ->
                        val pw = chain.getArg(1) as? String
                        if (!pw.isNullOrEmpty() && pw != cachedDbKey) {
                            cachedDbKey = pw
                            saveAndShareKey(pw)
                            log("DBKey captured from $cn.${m.name}(), length=${pw.length}")
                        }
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            log("sqlcipher hook setup fail: $t")
        }

        // ── 3. Activity.onCreate → 自动导出（不再调用 bj4）──
        try {
            val activityCls = Class.forName("android.app.Activity", false, cl)
            val onCreate = activityCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            log("setting up auto-export trigger...")
            hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                val act = chain.getThisObject()
                if (act is android.app.Activity) {
                    if (exportScheduled) return@intercept result
                    exportScheduled = true
                    val appCtx = act.applicationContext
                    log("Activity created, scheduling auto-export (once)")
                    Thread {
                        try {
                            // 轮询等待 db_key 就绪（最长 30s，每 2s 一次）
                            var key: String? = null
                            for (i in 0 until 15) {
                                key = cachedDbKey ?: run {
                                    val f = File("/data/local/tmp/dbkey_result.txt")
                                    if (f.exists()) f.readText().trim().ifEmpty { null } else null
                                }
                                if (key != null) break
                                Thread.sleep(2000)
                            }
                            if (key == null) {
                                log("auto-export skipped: db_key not ready in 30s")
                                return@Thread
                            }
                            log("auto-export start, key length=${key.length}")
                            triggerExport(appCtx, cl, key)
                        } catch (t: Throwable) {
                            log("auto-export thread crash: ${t.javaClass.name}: ${t.message}")
                        }
                    }.start()
                }
                result
            }
        } catch (t: Throwable) {
            log("activity hook fail: $t")
        }
    }

    /** 找到解密候选：(String,String)->String 的实例方法。优先标准名 b()，缺失则自动发现（混淆免疫） */
    private fun findLikelyDecryptMethods(cls: Class<*>): List<Method> {
        val out = mutableListOf<Method>()
        try {
            val b = cls.getDeclaredMethod("b", String::class.java, String::class.java)
            if (b.returnType == String::class.java) out.add(b)
        } catch (_: Throwable) {}
        if (out.isNotEmpty()) return out
        for (m in cls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers)) continue
            if (m.returnType != String::class.java) continue
            val p = m.parameterTypes
            if (p.size == 2 && p[0] == String::class.java && p[1] == String::class.java) {
                out.add(m)
            }
        }
        return out
    }

    private fun triggerExport(appCtx: android.content.Context, cl: ClassLoader, key: String) {
        try {
            Thread {
                try {
                    log("triggering export...")
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
            f.writeText(content)
            // 限制权限: 仅 owner 可读写 (防止其他应用/进程读走主密钥)
            try { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) } catch (_: Throwable) {}
            log("saved to $path")
        } catch (e: Throwable) {
            log("save fail: $e")
        }
    }

    /**
     * db_key 落盘 + chmod 644。
     * v5.2.1 bug: saveToFile 把权限收紧到 0600，文件属主是健康 App UID，
     * 模块 UI（自己的 UID）读不到 → UI 永远显示"db_key 未获取"。
     * /data/local/tmp 下该文件仅 root/adb 可列目录，644 泄露面可控。
     */
    private fun saveAndShareKey(key: String) {
        try {
            val f = File("/data/local/tmp/dbkey_result.txt")
            f.writeText(key)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "644", f.absolutePath)).waitFor()
            } catch (_: Throwable) {}
            log("db_key saved+shared (len=${key.length})")
        } catch (e: Throwable) {
            log("saveAndShareKey fail: $e")
        }
    }

    private fun appendToFile(path: String, line: String) {
        try {
            val f = File(path)
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.createNewFile()
            }
            f.appendText(line + "\n")
        } catch (e: Throwable) {
            log("append fail: $e")
        }
    }

    private fun log(msg: String) {
        android.util.Log.i("DBKeyHook", msg)
    }
}
