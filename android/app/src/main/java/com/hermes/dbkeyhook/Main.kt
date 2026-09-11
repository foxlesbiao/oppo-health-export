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
 * v5.2.3 (2026-09-11 logcat 分析):
 *   1. appendToFile 改写 app 私有 cacheDir —— 健康 App UID 无权在 /data/local/tmp
 *      建新文件，72 次 "append fail: Permission denied" 全是这条路径产生的噪音
 *   2. SQLCipher openDatabase hook 增加 ClassLoader.loadClass 延迟挂载（onPackageLoaded
 *      时 zetetic 类若未加载则 watch，首次加载即挂）+ 覆盖 char[]/byte[] 密码重载
 *   3. dbkey_result.txt 读取兜底 try/catch（同目录权限场景）
 */
class Main : XposedModule() {

    @Volatile
    private var cachedDbKey: String? = null

    companion object {
        @Volatile private var exportScheduled = false
        @Volatile private var sqlWatchDone = java.util.concurrent.atomic.AtomicBoolean(false)
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
                        // v5.2.3: 内容已脱敏(alias+len)，失败静默 — 健康 App UID 无权在
                        // /data/local/tmp 建新文件，72 次 Permission denied 是纯噪音
                        appendToFile("dbkey_all.txt", "$alias(len=${value.length})")
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
        // v5.2.3: 分两类 hook ——
        //   a) 已加载的类：直接匹配所有密码重载 (String/char[]/byte[])
        //   b) 尚未加载的类：Class.forName 失败则注册 ClassLoader.loadClass hook，
        //      等首次加载 zetetic 类时再挂 openDatabase hook（修复 OMS split APK 延迟加载
        //      导致 onPackageLoaded 时 zetetic 类不存在 → openDatabase hook 从未挂上）
        try {
            var hookedAny = false
            val hookSqlCipherClass: (Class<*>) -> Int = { sqlCls ->
                var n = 0
                for (m in sqlCls.declaredMethods) {
                    if (m.name != "openDatabase" && m.name != "openOrCreateDatabase") continue
                    val ps = m.parameterTypes
                    // 密码参数 = 第2个参数, 三种形态: String / char[] / byte[]
                    val isStr = ps.size >= 2 && ps[0] == String::class.java && ps[1] == String::class.java
                    val isChar = ps.size >= 2 && ps[0] == String::class.java && ps[1] == CharArray::class.java
                    val isByte = ps.size >= 2 && ps[0] == String::class.java && ps[1] == ByteArray::class.java
                    if (!isStr && !isChar && !isByte) continue
                    m.isAccessible = true
                    log("hooking ${sqlCls.name}.${m.name}(path, ${ps[1].simpleName}, ...)")
                    hook(m).intercept { chain ->
                        val pw = when (val a = chain.getArg(1)) {
                            is String -> a
                            is CharArray -> String(a)
                            is ByteArray -> String(a, Charsets.UTF_8)
                            else -> null
                        }
                        if (!pw.isNullOrEmpty() && pw != cachedDbKey) {
                            cachedDbKey = pw
                            saveAndShareKey(pw)
                            log("DBKey captured from ${sqlCls.name}.${m.name}(), length=${pw.length}")
                        }
                        chain.proceed()
                    }
                    n++
                }
                n
            }
            for (cn in listOf(
                "net.zetetic.database.sqlcipher.SQLiteDatabase",
                "net.sqlcipher.database.SQLiteDatabase",
            )) {
                val sqlCls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null }
                if (sqlCls != null && hookSqlCipherClass(sqlCls) > 0) hookedAny = true
            }
            if (!hookedAny) {
                log("sqlcipher class not loaded yet, watching ClassLoader.loadClass...")
                val loaderCls = Class.forName("java.lang.ClassLoader", false, cl)
                val loadClass = loaderCls.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType)
                loadClass.isAccessible = true
                hook(loadClass).intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val res = chain.proceed()
                    if (name == "net.zetetic.database.sqlcipher.SQLiteDatabase" ||
                        name == "net.sqlcipher.database.SQLiteDatabase") {
                        val c = res as? Class<*>
                        if (c != null && !sqlWatchDone.getAndSet(true)) {
                            try { hookSqlCipherClass(c) } catch (t: Throwable) { log("late sqlcipher hook fail: $t") }
                        }
                    }
                    res
                }
                log("sqlcipher loadClass watcher armed")
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
                    savedCtx.set(appCtx)
                    log("Activity created, scheduling auto-export (once)")
                    Thread {
                        try {
                            // 轮询等待 db_key 就绪（最长 30s，每 2s 一次）
                            var key: String? = null
                            for (i in 0 until 15) {
                                key = cachedDbKey ?: run {
                                    val f = File("/data/local/tmp/dbkey_result.txt")
                                    if (f.exists()) try { f.readText().trim().ifEmpty { null } } catch (_: Throwable) { null } else null
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

    /** 模块进程内缓存 ctx（appendToFile 用）；XposedModule 无 Context，取 hook 到的 Activity 的 */
    private val savedCtx = java.util.concurrent.atomic.AtomicReference<android.content.Context?>(null)

    private fun appendToFile(path: String, line: String) {
        try {
            val c = savedCtx.get() ?: return   // 尚未拿到 ctx（进程早期），跳过 — 这是调试辅助
            val f = File(c.cacheDir, path)   // v5.2.3: 写 app 私有 cache，/data/local/tmp App UID 建不了文件
            if (!f.exists()) f.createNewFile()
            f.appendText(line + "\n")
        } catch (_: Throwable) {
            // v5.2.3: 静默 — 这是调试辅助，失败不值得刷日志
        }
    }

    private fun log(msg: String) {
        android.util.Log.i("DBKeyHook", msg)
    }
}
