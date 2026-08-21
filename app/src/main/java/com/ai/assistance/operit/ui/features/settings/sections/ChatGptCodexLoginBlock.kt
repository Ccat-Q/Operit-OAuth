package com.ai.assistance.operit.ui.features.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.llmprovider.ChatGptCodexAuth
import com.ai.assistance.operit.api.chat.llmprovider.CodexDeviceLoginChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Device-code login UI keeps OAuth secrets in [ChatGptCodexAuth], never in Compose state. */
@Composable
internal fun ChatGptCodexLoginBlock() {
    val context = LocalContext.current
    val auth = remember { ChatGptCodexAuth.getInstance(context.applicationContext) }
    val credentials by auth.credentials.collectAsState()
    val scope = rememberCoroutineScope()
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var challenge by remember { mutableStateOf<CodexDeviceLoginChallenge?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "ChatGPT Codex",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (credentials == null) "状态：未登录" else "状态：已登录",
            style = MaterialTheme.typography.bodyMedium
        )
        credentials?.accountId?.let {
            Text(
                text = "账户：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (challenge != null) {
            Text(
                text = "请在已打开的 ChatGPT 页面输入一次性验证码：${challenge!!.userCode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "正在等待授权，最多 15 分钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        loginError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
            if (credentials == null) {
                Button(
                    enabled = loginJob == null,
                    onClick = {
                        loginError = null
                        loginJob = scope.launch {
                            try {
                                val startedChallenge = auth.beginLogin()
                                challenge = startedChallenge
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(startedChallenge.verificationUrl))
                                )
                                auth.completeLogin(startedChallenge)
                            } catch (_: CancellationException) {
                                loginError = "已取消授权"
                            } catch (error: Exception) {
                                loginError = error.message ?: "ChatGPT 登录失败"
                            } finally {
                                challenge = null
                                loginJob = null
                            }
                        }
                    }
                ) {
                    Text(if (loginJob == null) "使用 ChatGPT 登录" else "正在等待授权")
                }
                if (loginJob != null) {
                    Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
                    OutlinedButton(onClick = { loginJob?.cancel() }) {
                        Text("取消")
                    }
                }
            } else {
                OutlinedButton(onClick = { auth.logout() }) {
                    Text("退出登录")
                }
            }
        }
        Text(
            text = "实验性功能：使用非公开的 ChatGPT Codex 后端；账户权限、额度和速率限制由 OpenAI 服务端决定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
