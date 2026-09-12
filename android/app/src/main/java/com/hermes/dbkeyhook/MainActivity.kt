package com.hermes.dbkeyhook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dbkey_config", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HealthConfigScreen(
                    prefs = prefs,
                    toast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                    onSave = { range, url, urlExternal, token ->
                        prefs.edit()
                            .putInt("range", range)
                            .putString("url", url)
                            .putString("url_external", urlExternal)
                            .putString("token", token)
                            .putLong("updated_at", System.currentTimeMillis())
                            .commit()
                        writeSharedConfig(range, url, urlExternal, token)
                        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
                    },
                    onManualExport = {
                        // v5.3.8: 先 su 强杀健康 App 再拉起——6.7.19 主进程懒加载+保活，App 活着时 Activity/openDatabase hook 都不会再触发
                        Thread {
                            try {
                                Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c",
                                    "am force-stop com.heytap.health")).waitFor()
                                Thread.sleep(1500)
                            } catch (_: Throwable) {}
                            try {
                                val intent = packageManager.getLaunchIntentForPackage("com.heytap.health")
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(intent)
                                    runOnUiThread { Toast.makeText(this, "已强杀并重启健康 App，等 key 就绪后自动导出…", Toast.LENGTH_LONG).show() }
                                } else {
                                    runOnUiThread { Toast.makeText(this, "未找到健康 App", Toast.LENGTH_SHORT).show() }
                                }
                            } catch (t: Throwable) {
                                runOnUiThread { Toast.makeText(this, "打开失败: ${t.message}", Toast.LENGTH_SHORT).show() }
                            }
                        }.start()
                    }
                )
            }
        }
    }

    private fun writeSharedConfig(range: Int, url: String, urlExternal: String, token: String) {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n" +
            "<map>\n" +
            "    <int name=\"range\" value=\"$range\" />\n" +
            "    <string name=\"url\">$url</string>\n" +
            "    <string name=\"url_external\">$urlExternal</string>\n" +
            "    <string name=\"token\">$token</string>\n" +
            "</map>\n"
        try {
            // 防注入: 配置写入走 base64, 不拼接 shell 字符串
            // (URL/token 可能含单引号/特殊字符, echo '$xml' 会破壳)
            val b64 = android.util.Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c",
                "echo '" + b64 + "' | base64 -d > /data/local/tmp/dbkey_config.xml && chmod 644 /data/local/tmp/dbkey_config.xml"))
            p.waitFor()
            if (p.exitValue() != 0) {
                val err = p.errorStream.bufferedReader().readText()
                android.util.Log.e("DBKeyHook-UI", "shared config write fail: $err")
            }
        } catch (e: Throwable) {
            android.util.Log.e("DBKeyHook-UI", "su unavailable: ${e.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConfigScreen(
    prefs: android.content.SharedPreferences,
    toast: (String) -> Unit,
    onSave: (Int, String, String, String) -> Unit,
    onManualExport: () -> Unit,
) {
    var rangeIdx by remember { mutableStateOf(prefs.getInt("range", 1)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var urlExternal by remember { mutableStateOf(prefs.getString("url_external", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var status by remember { mutableStateOf("") }
    var lastKey by remember { mutableStateOf(readLastKey()) }

    val ranges = listOf("最近7天", "最近30天", "最近90天", "全部")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("📊 健康数据导出配置", fontSize = 24.sp, color = Color(0xFF90CAF9))
        Text("自动导出全部 54 张表（心率/睡眠/运动/血氧/血压/体重/鼾症/HRV/久坐/光照等），一个不留", fontSize = 13.sp, color = Color(0xFF90A4AE))

        HorizontalDivider(color = Color(0xFF2A3050))

        Surface(
            color = Color(0x1A66BB6A),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("✅ 全量导出模式", color = Color(0xFF66BB6A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("54 张表全部处理：按天聚合 + 统计（avg/min/max）", color = Color(0xFFA5D6A7), fontSize = 12.sp)
                Text("无需选择指标，直接导出全部", color = Color(0xFFA5D6A7), fontSize = 12.sp)
            }
        }

        HorizontalDivider(color = Color(0xFF2A3050))

        // ── 手动导出 ──
        Button(
            onClick = {
                status = "⏳ 正在打开健康 App，key 就绪后自动导出…"
                onManualExport()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📤 立即导出", color = Color.White, fontWeight = FontWeight.Bold)
        }

        if (lastKey != null) {
            Text("🔑 db_key: ${lastKey!!.take(12)}…（${lastKey!!.length} 字符）", color = Color(0xFF66BB6A), fontSize = 12.sp)
        } else {
            Text("🔑 db_key: 未获取（需先打开一次健康 App）", color = Color(0xFFEF5350), fontSize = 12.sp)
        }

        HorizontalDivider(color = Color(0xFF2A3050))

        Text("时间范围", style = MaterialTheme.typography.titleMedium, color = Color(0xFF90CAF9))
        ranges.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = rangeIdx == i, onClick = { rangeIdx = i })
                Text(r, color = Color(0xFFE8EAF6))
            }
        }

        HorizontalDivider(color = Color(0xFF2A3050))

        Text("Agent 上传地址", style = MaterialTheme.typography.titleMedium, color = Color(0xFF90CAF9))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://agent-ip:port/api/upload", color = Color(0xFF546E7A)) }
        )

        Text("外网地址（可选，Tailscale 等）", style = MaterialTheme.typography.titleMedium, color = Color(0xFF90CAF9))
        OutlinedTextField(
            value = urlExternal,
            onValueChange = { urlExternal = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://100.x.x.x:8766/api/upload", color = Color(0xFF546E7A)) }
        )
        Text("内网优先，失败自动切外网", color = Color(0xFF90A4AE), fontSize = 12.sp)

        Text("Token（可选）", style = MaterialTheme.typography.titleMedium, color = Color(0xFF90CAF9))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("", color = Color(0xFF546E7A)) }
        )

        HorizontalDivider(color = Color(0xFF2A3050))

        Button(
            onClick = {
                onSave(rangeIdx, url.trim(), urlExternal.trim(), token.trim())
                status = "✅ 配置已保存（重启欢太健康后生效）"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("💾 保存配置")
        }

        if (status.isNotEmpty()) {
            Text(status, color = Color(0xFF66BB6A), fontSize = 13.sp)
        }
    }
}

private fun readLastKey(): String? {
    // 1) 直读 /data/local/tmp（模块若 root 写入成功）
    try {
        val f = File("/data/local/tmp/dbkey_result.txt")
        if (f.exists()) f.readText().trim().ifEmpty { null }?.let { return it }
    } catch (_: Throwable) {}
    // 2) su 读健康 App 私有 cacheDir（v5.3.8: 健康App UID 无权写 /data/local/tmp，key 落在那里）
    return try {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c",
            "cat /data/data/com.heytap.health/cache/dbkey_result.txt"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifEmpty { null }
    } catch (_: Throwable) { null }
}
