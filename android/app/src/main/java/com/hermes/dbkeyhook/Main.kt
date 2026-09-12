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
 * v5.3 (2026-09-11 第二份 logcat, 6.7.19):
 *   App 6.7.19 冷启动根本不解密 alias=db_key（主进程只解 unique_key=MMKV key），
 *   且 DB 延迟打开；Activity.onCreate hook 在该机型上不触发。
 *   → 导出触发改为 openDatabase 捕获密码后立即执行（ctx 取 ActivityThread.currentApplication()），
 *     Activity.onCreate 保留为后备。App 版本名 6.7.19 确认。
 * v5.3.1 (2026-09-11 第三份 logcat + 多模型分析):
 *   6.7.19 主进程冷启动不碰 db_key/主库（纯 UI 20s 无任何密码事件）→ 等 DB 打开不可行。
 *   1. Application.onCreate watchdog（120s 轮询）：cachedDbKey/落盘文件有 key 即单飞导出，失败 60s 重试
 *   2. openDatabase hook 每次调用打日志（path+pwLen），诊断不再靠猜
 *   3. 心跳日志：注入后 60s 每 5s 一条，直接确认 hook 存活
 *   4. Activity hook 降级为仅记日志（触发职责移交 watchdog）
 *   密钥链佐证：hw_key=38(32hex+6)、goal_key=40(32hex+8) → db_key=32hex+"db_key"(38)，恒定可落盘复用。
 */
class Main : XposedModule() {

    @Volatile
    private var cachedDbKey: String? = null

    companion object {
        @Volatile private var exportScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var sqlWatchDone = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log("package loaded: ${param.packageName}")
        if (param.packageName != "com.heytap.health") return
        log("target app found, hooking...")
        val cl = param.defaultClassLoader

        // ── 0. 心跳：进程启动后 60s 内每 5s 打一条，用于从 logcat 直接确认 hook 注入成功 ──
        try {
            log("injected pid=${android.os.Process.myPid()}, uid=${android.os.Process.myUid()}")
            val hb = Thread {
                for (i in 0 until 12) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                    log("heartbeat#$i dbKey=${if (cachedDbKey != null) "captured" else "none"}")
                }
            }
            hb.isDaemon = true
            hb.start()
        } catch (_: Throwable) {}

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

        // ── 1.5 加密锚点：hook enCryptData(alias, plaintext, ssoid)。
        // 6.7.19 App 升级后 hasKey("db_key")=false → initDbKey 走「new key」enCryptData 分支，
        // deCryptData 永不执行 —— 这是 v5.3.x 真机抓不到 key 的根因（smali 反编译确认）。
        try {
            val encCls = Class.forName("com.heytap.health.base.encrypt.AesGcmAndroidKeyStore", false, cl)
            val encM = encCls.getDeclaredMethod("enCryptData",
                String::class.java, String::class.java, String::class.java)
            encM.isAccessible = true
            log("hooking enCryptData(alias, plain, ssoid)")
            hook(encM).intercept { chain ->
                val alias = chain.getArg(0) as? String
                val plain = chain.getArg(1) as? String
                if (alias == "db_key" && !plain.isNullOrEmpty() && plain != cachedDbKey) {
                    cachedDbKey = plain
                    saveAndShareKey(plain)
                    log("DBKey captured from enCryptData, length=${plain.length}")
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            log("encrypt hook fail: $t")
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
                        // v5.3.1: 每次调用都打日志（path 末段 + 密码长度），诊断不再靠猜
                        val pathTail = ((chain.getArg(0) as? String) ?: "").substringAfterLast('/')
                        log("openDB path=$pathTail pwLen=${pw?.length ?: 0}")
                        // v5.3.8: openDatabase 抓到的密码就是实际加密密码（无论 alias），落盘供 UI/导出用
                        // 旧行为 pw != cachedDbKey 才落盘 → 6.7.19 上 hw_key 已在 DECRYPTED 缓存，跳过 → dbkey_result.txt 永不存在 → UI 永远未获取
                        if (!pw.isNullOrEmpty() && pw != cachedDbKey) {
                            cachedDbKey = pw
                        }
                        if (!pw.isNullOrEmpty()) {
                            saveAndShareKey(pw)
                            log("DBKey captured from ${sqlCls.name}.${m.name}(), length=${pw.length}")
                        }
                        // 捕获或已有 key → 单飞导出（失败允许重试：RUNNING 复位后再次触发）
                        val k = pw ?: cachedDbKey
                        if (!k.isNullOrEmpty()) {
                            currentApp()?.let { app ->
                                savedCtx.compareAndSet(null, app)
                                if (!exportScheduled.getAndSet(true)) {
                                    Thread {
                                        val ok = runExport(app, cl, k)
                                        if (!ok) exportScheduled.set(false)   // 失败重试
                                    }.start()
                                }
                            } ?: log("no app ctx yet for export")
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

        // ── 2.5 Application.onCreate watchdog：app 就绪后轮询 key（缓存/落盘文件），有 key 即单飞导出。
        // 覆盖「DB 延迟打开」和「Activity hook 不触发」两种场景；失败 60s 后重试一次。
        try {
            val appCls = Class.forName("android.app.Application", false, cl)
            val appOnCreate = appCls.getDeclaredMethod("onCreate")
            hook(appOnCreate).intercept { chain ->
                val result = chain.proceed()
                val app = chain.getThisObject() as? android.app.Application ?: return@intercept result
                if (app.packageName != "com.heytap.health") return@intercept result
                Thread {
                    for (i in 0 until 60) {   // 2s x 60 = 120s
                        val k = cachedDbKey ?: readPersistedKey()
                        if (!k.isNullOrEmpty() && exportScheduled.compareAndSet(false, true)) {
                            savedCtx.compareAndSet(null, app)
                            log("watchdog export attempt (key len=${k.length})")
                            val ok = runExport(app, cl, k)
                            if (!ok) { exportScheduled.set(false); Thread.sleep(60_000) }
                        }
                        try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    }
                    log("watchdog timeout: no db_key in 120s")
                }.start()
                result
            }
            log("application watchdog armed")
        } catch (t: Throwable) {
            log("application hook fail: $t")
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
                    savedCtx.compareAndSet(null, act.applicationContext)
                    log("Activity created: ${act.javaClass.simpleName}")
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
        Thread { runExport(appCtx, cl, key) }.start()
    }

    /** 同步导出（供重试路径复用）；返回 ExportWorker 结果 */
    private fun runExport(appCtx: android.content.Context, cl: ClassLoader, key: String): Boolean {
        return try {
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
            ok
        } catch (t: Throwable) {
            log("export crash: ${t.javaClass.name}: ${t.message}")
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            log("export stack: " + sw.toString().substring(0, Math.min(400, sw.toString().length)))
            false
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

    /** 读已持久化的 key（v5.3.1 watchdog 用）；app UID 读自己写的 644 文件没问题 */
    private fun readPersistedKey(): String? {
        return try {
            val f = File("/data/local/tmp/dbkey_result.txt")
            if (f.exists()) f.readText().trim().ifEmpty { null } else null
        } catch (_: Throwable) { null }
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
            // v5.3.8: /data/local/tmp 目录属主 shell，健康 App UID 无权创建文件 → 兜底写私有 cacheDir（UI 用 su 读）
            try {
                val f2 = File(currentApp()?.cacheDir ?: java.io.File("/data/local/tmp"), "dbkey_result.txt")
                f2.writeText(key)
                log("db_key saved to cacheDir (len=${key.length})")
            } catch (e2: Throwable) {
                log("saveAndShareKey fail: $e / $e2")
            }
        }
    }

    /** 模块进程内缓存 ctx（appendToFile 用）；XposedModule 无 Context，取 hook 到的 Activity 的 */
    private val savedCtx = java.util.concurrent.atomic.AtomicReference<android.content.Context?>(null)

    /** 取目标 app 的 Application（v5.3：不依赖 Activity hook 也能拿 ctx） */
    private fun currentApp(): android.content.Context? {
        savedCtx.get()?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? android.content.Context
        } catch (_: Throwable) { null }
    }

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
