package com.hermes.dbkeyhook

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
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c",
                "echo '$xml' > /data/local/tmp/dbkey_config.xml && chmod 644 /data/local/tmp/dbkey_config.xml"))
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
) {
    var rangeIdx by remember { mutableStateOf(prefs.getInt("range", 1)) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var urlExternal by remember { mutableStateOf(prefs.getString("url_external", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var status by remember { mutableStateOf("") }

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
