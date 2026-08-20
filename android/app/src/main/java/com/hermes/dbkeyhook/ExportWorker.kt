package com.hermes.dbkeyhook

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.2 分块上传版 — 流式分片导出 + 少量多次上传
 *
 * 相比 v3.1 的关键改进(用户建议"少量多次上传"):
 *   - 不再把首次全量(哪怕近90天)攒成单个大文件一次上传
 *   - 而是每导满 CHUNK_ROWS(2万)行就立即作为一个分块 POST 上传, 然后清空/续下一块
 *   - 优点: 单次上传包小(几MB内)、服务端入库逐块、上传失败只重试该块、手机内存恒定
 *   - 服务端已按「===TABLE: 分节」+ 行哈希幂等, 分块间的表/数据可跨块拼接不重复
 *
 * 保留: 单一导出互斥(AtomicBoolean) / 初始水位90天建基线 / 每表流式分批查询
 */
class ExportWorker(
    private val ctx: Context,
    private val cl: ClassLoader,
    private val dbKey: String,
    private val lspLog: (String) -> Unit = { Log.i("DBKeyHook-Export", it) },
    private val toast: (String) -> Unit = {},
) {
    companion object {
        private val RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private val dbPath = "/data/data/com.heytap.health/databases/database.db"
    private val BATCH = 2000          // 单表查询分批大小
    private val CHUNK_ROWS = 20000L   // 每满这些行就上传一个分块
    private val SMALL_LIMIT = 20000   // 无时间戳小表上限
    private val INITIAL_DAYS = 90L    // 首次(无游标)初始水位: 近90天建基线
    private val excludedTables = setOf("room_master_table", "sqlite_sequence", "android_metadata")
    private val timeColKeywords = listOf(
        "data_created_timestamp", "start_timestamp", "start_time",
        "measurement_timestamp", "created_timestamp", "create_time",
        "modified_timestamp", "modified_time", "updated", "update_timestamp"
    )

    fun run(): Boolean {
        if (RUNNING.get()) { lspLog("export already running, skip"); return false }
        RUNNING.set(true)
        return try { doExport() } finally { RUNNING.set(false) }
    }

    private fun doExport(): Boolean {
        val prefs = readConfigViaRoot()
        val url = prefs.url
        val urlExternal = prefs.urlExternal
        val token = prefs.token
        if (url.isEmpty() && urlExternal.isEmpty()) { toast("⚠️ 未配置上传地址"); return false }
        val urls = listOfNotNull(url, urlExternal)

        val cursor = fetchCursor(urls)
        lspLog("server watermark: ${cursor.size} tables")

        val sqliteCls = openSqLite() ?: run { toast("❌ SQLCipher 加载失败"); return false }
        val db = openDatabase(sqliteCls, dbPath, dbKey)
        val tables = listTables(db)
        lspLog("total tables: ${tables.size}, chunk=$CHUNK_ROWS 行/次上传")

        // ---- 分块导出 + 多次上传 ----
        var chunkFile: File? = null
        var chunkW: BufferedWriter? = null
        var chunkRows = 0L
        var uploadedChunks = 0
        var uploadedTables = 0L
        // 批次标识: 一次「手动点导出」= 一个批次; 服务端据此只对最后一个分块触发一次分析
        val batchId = System.currentTimeMillis().toString()
        var chunkIndex = 0   // 当前(正在上传的)分块号, 1-based
        val initialTs = System.currentTimeMillis() - INITIAL_DAYS * 24 * 3600 * 1000

        // 分块辅助(闭包)。isFinal=true 表示这是本批次最后一个分块(所有表已处理完)
        fun ensureChunk(): BufferedWriter {
            if (chunkW == null) {
                val f = File(ctx.cacheDir, "health_part_" + System.currentTimeMillis() + ".csv")
                chunkFile = f
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8))
                w.write("# OPPO Health 增量 (stream part)\n")
                chunkW = w
                chunkRows = 0L
            }
            return chunkW!!
        }
        fun flushChunk(isFinal: Boolean): Boolean {
            if (chunkW == null) return false
            try { chunkW!!.close() } catch (_: Throwable) {}
            val f = chunkFile!!
            chunkIndex++
            // final 块: total_chunks 在最后一块上传时才能确定 = 当前块号
            val ok = upload(f, urls, token, "incremental", batchId, chunkIndex, isFinal, chunkIndex)
            try { f.delete() } catch (_: Throwable) {}
            if (ok) uploadedChunks++
            chunkW = null; chunkFile = null; chunkRows = 0L
            return ok
        }

        try {
            for (t in tables) {
                val (cols, timeCol) = analyzeTable(db, t)
                if (cols.isEmpty()) continue
                try {
                    val lastTs = cursor[t] ?: initialTs
                    var n = 0
                    if (timeCol != null && timeCol in cols) {
                        val w = ensureChunk()
                        n = streamTableByTime(db, t, cols, timeCol, lastTs, w)
                        if (n > 0) {
                            uploadedTables += n
                            lspLog("  $t: +$n (watermark=$lastTs)")
                        }
                    } else {
                        val w = ensureChunk()
                        n = streamSmallTable(db, t, cols, w)
                        if (n > 0) { uploadedTables += n; lspLog("  $t: +$n (small)") }
                    }
                    chunkRows += n
                    if (chunkRows >= CHUNK_ROWS) flushChunk(false)
                } catch (e: Throwable) { lspLog("  $t fail: $e") }
            }
        } finally {
            // 处理完所有表后, 上传剩余分块 —— 这就是本批次最后一个分块
            try { flushChunk(true) } catch (_: Throwable) {}
        }

        lspLog("done: ${uploadedTables} rows, ${uploadedChunks} chunks uploaded")
        if (uploadedChunks == 0) { toast("✅ 已是最新，无新增数据"); return true }
        toast(if (uploadedTables > 0) "🎉 上传完成：$uploadedTables 行（${uploadedChunks} 个分块）" else "⚠️ 上传失败")
        return true
    }

    /** 有时间戳表: 按水位分批流式写(键集分页), 返回写入行数 */
    private fun streamTableByTime(db: Any, table: String, cols: List<String>, timeCol: String,
                                  lastTs: Long, w: BufferedWriter): Int {
        val ti = cols.indexOf(timeCol)
        var cursorTs = lastTs
        var n = 0
        var wroteHeader = false
        while (true) {
            val where = if (cursorTs > 0) "\"$timeCol\" > $cursorTs" else null
            val sql = "SELECT ${cols.joinToString(",")} FROM \"$table\"" +
                    (where?.let { " WHERE $it" } ?: "") + " ORDER BY \"$timeCol\" ASC LIMIT $BATCH"
            val batch = query(db, sql)
            if (batch.isEmpty()) break
            if (!wroteHeader) {
                w.write("===TABLE:$table|TIMECOL:$timeCol===\n")
                w.write(cols.joinToString(",")); w.write("\n")
                wroteHeader = true
            }
            var maxT = cursorTs
            for (r in batch) {
                w.write(r.joinToString(",")); w.write("\n")
                n++
                val v = r[ti].toLongOrNull()
                if (v != null && v > maxT) maxT = v
            }
            if (batch.size < BATCH) break
            cursorTs = maxT
        }
        return n
    }

    private fun streamSmallTable(db: Any, table: String, cols: List<String>, w: BufferedWriter): Int {
        val sql = "SELECT ${cols.joinToString(",")} FROM \"$table\" LIMIT $SMALL_LIMIT"
        val rows = query(db, sql)
        if (rows.isEmpty()) return 0
        w.write("===TABLE:$table|TIMECOL:===\n")
        w.write(cols.joinToString(",")); w.write("\n")
        for (r in rows) { w.write(r.joinToString(",")); w.write("\n") }
        return rows.size
    }

    private fun fetchCursor(urls: List<String>): Map<String, Long> {
        for (u in urls) {
            if (u.isBlank()) continue
            try {
                val base = u.trimEnd('/')
                val conn = (URL(base + "/api/cursor").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 6000; readTimeout = 10000
                }
                val code = conn.responseCode
                if (code == 200) {
                    val body = String(conn.inputStream.readBytes(), Charsets.UTF_8)
                    conn.disconnect()
                    val m = mutableMapOf<String, Long>()
                    Regex("\"([^\"]+)\":(-?\\d+)").findAll(body).forEach { m[it.groupValues[1]] = it.groupValues[2].toLong() }
                    if (m.isNotEmpty()) return m
                } else conn.disconnect()
            } catch (t: Throwable) { lspLog("cursor fail: $u $t") }
        }
        return emptyMap()
    }

    private fun listTables(db: Any): List<String> {
        val rows = query(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        return rows.map { it[0] }.filter { it !in excludedTables }
    }

    private fun analyzeTable(db: Any, table: String): Pair<List<String>, String?> {
        val rows = query(db, "PRAGMA table_info(\"$table\")")
        val cols = rows.map { it[1] }
        var timeCol: String? = null
        for (c in cols) { if (c in timeColKeywords) { timeCol = c; break } }
        if (timeCol == null) timeCol = cols.firstOrNull { it.contains("timestamp", ignoreCase = true) }
        return Pair(cols, timeCol)
    }

    private fun readConfigViaRoot(): ConfigVals {
        return try {
            val f = java.io.File("/data/local/tmp/dbkey_config.xml")
            if (!f.exists()) return ConfigVals(1, "", "", "")
            val text = f.readText()
            var range = 1; var url = ""; var urlExternal = ""; var token = ""
            Regex("name=\"range\" value=\"(\\d+)\"").find(text)?.let { range = it.groupValues[1].toInt() }
            Regex("name=\"url\">([^<]*)<").find(text)?.let { url = it.groupValues[1] }
            Regex("name=\"url_external\">([^<]*)<").find(text)?.let { urlExternal = it.groupValues[1] }
            Regex("name=\"token\">([^<]*)<").find(text)?.let { token = it.groupValues[1] }
            ConfigVals(range, url, urlExternal, token)
        } catch (t: Throwable) { ConfigVals(1, "", "", "") }
    }

    data class ConfigVals(val range: Int, val url: String, val urlExternal: String, val token: String)

    private fun openSqLite(): Class<*>? {
        val candidates = listOf(
            "net.sqlcipher.database.SQLiteDatabase",
            "net.zetetic.database.sqlcipher.SQLiteDatabase",
        )
        val loaders = mutableListOf<ClassLoader>(cl, ctx.classLoader)
        try { System.loadLibrary("sqlcipher"); lspLog("libsqlcipher loaded") } catch (e: Throwable) {}
        for (cn in candidates) { try { loaders.add(Class.forName(cn).classLoader) } catch (e: Throwable) {} }
        for (cn in candidates) for (l in loaders) { try { return Class.forName(cn, false, l) } catch (e: Throwable) {} }
        return null
    }

    private fun openDatabase(sqliteCls: Class<*>, path: String, key: String): Any {
        val factoryCls = Class.forName("${sqliteCls.name}\$CursorFactory", false, sqliteCls.classLoader)
        val hookCls = Class.forName("net.zetetic.database.sqlcipher.SQLiteDatabaseHook", false, sqliteCls.classLoader)
        val m = sqliteCls.getDeclaredMethod("openDatabase",
            String::class.java, String::class.java, factoryCls, Int::class.javaPrimitiveType, hookCls)
        m.isAccessible = true
        return m.invoke(null, path, key, null, 268435456, null)
    }

    private fun query(db: Any, sql: String): List<Array<String>> {
        val rawQuery = db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
        val cursor = rawQuery.invoke(db, sql, null)
        val rows = mutableListOf<Array<String>>()
        try {
            val moveToNext: Method = cursor.javaClass.getMethod("moveToNext")
            val getColumnCount: Method = cursor.javaClass.getMethod("getColumnCount")
            val getString: Method = cursor.javaClass.getMethod("getString", Int::class.java)
            val cols = getColumnCount.invoke(cursor) as Int
            while (moveToNext.invoke(cursor) as Boolean) {
                val row = arrayOfNulls<String>(cols)
                for (i in 0 until cols) { val s = getString.invoke(cursor, i); row[i] = s?.toString() ?: "" }
                rows.add(row.map { it ?: "" }.toTypedArray())
            }
        } finally {
            try { cursor.javaClass.getMethod("close").invoke(cursor) } catch (ignored: Throwable) {}
        }
        return rows
    }

    private fun upload(f: File, urls: List<String>, token: String, mode: String,
                       batchId: String, chunkIndex: Int, isFinal: Boolean, totalChunks: Int): Boolean {
        for (u in urls) {
            if (u.isBlank()) continue
            if (tryUpload(f, u, token, mode, batchId, chunkIndex, isFinal, totalChunks)) return true
        }
        return false
    }

    private fun tryUpload(f: File, url: String, token: String, mode: String,
                          batchId: String, chunkIndex: Int, isFinal: Boolean, totalChunks: Int): Boolean {
        return try {
            val boundary = "----DBKeyHook" + System.currentTimeMillis()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 6000
                readTimeout = 300000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            val meta = "{\"source\":\"oppo-health\",\"mode\":\"$mode\",\"batch_id\":\"$batchId\"," +
                    "\"chunk\":$chunkIndex,\"final\":${if (isFinal) 1 else 0},\"total_chunks\":$totalChunks}"
            val os = conn.outputStream
            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n$meta\r\n".toByteArray(Charsets.UTF_8))
            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${f.name}\"\r\nContent-Type: text/csv\r\n\r\n".toByteArray(Charsets.UTF_8))
            f.inputStream().use { is_ ->
                val buf = ByteArray(8192)
                var n: Int
                while (is_.read(buf).also { n = it } > 0) os.write(buf, 0, n)
            }
            os.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            os.close()
            val code = conn.responseCode
            lspLog("upload http $code (${f.length()}B)")
            if (code == 200) return true
            false
        } catch (t: Throwable) { lspLog("upload fail: $t"); false }
    }
}
