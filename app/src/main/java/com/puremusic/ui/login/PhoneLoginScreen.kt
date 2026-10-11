package com.puremusic.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PhoneLoginScreen(onBack: () -> Unit, onSuccess: () -> Unit) {
    val vm: PhoneLoginViewModel = hiltViewModel()
    val context = LocalContext.current

    LaunchedEffect(vm.loggedIn) {
        if (vm.loggedIn) onSuccess()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("手机号登录", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "用手机号登录，走原生账号体系（可获取 SVIP 权限）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = vm.phone,
                onValueChange = { vm.phone = it },
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::sendCode,
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.loading) "发送中…" else "发送验证码")
            }

            // 滑块验证
            vm.securityUrl?.let { url ->
                Spacer(Modifier.height(16.dp))
                Text("请完成下方滑块验证", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0 Mobile Safari/537.36"
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    return !(u.startsWith("http://") || u.startsWith("https://"))
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val cm = CookieManager.getInstance()
                        val cookie = listOf(
                            cm.getCookie("https://c.y.qq.com/"),
                            cm.getCookie("https://qq.com/"),
                        ).filter { !it.isNullOrBlank() }.joinToString("; ")
                        vm.resendCode(cookie)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("完成验证后，点这里重新发送")
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = vm.code,
                onValueChange = { vm.code = it },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.login { onSuccess() } },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.loading) "登录中…" else "登录")
            }

            if (vm.status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(vm.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
