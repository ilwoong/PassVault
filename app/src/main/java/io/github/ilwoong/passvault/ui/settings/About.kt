package io.github.ilwoong.passvault.ui.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ilwoong.passvault.R

/** 앱에 실려 배포되는 구성요소의 고지. [text] 가 null 이면 고지할 라이선스 원문이 없다(퍼블릭 도메인). */
data class OssNotice(val name: String, val copyright: String, val license: String, @param:RawRes val text: Int?)

/** 런타임 클래스패스 기준. 의존성을 바꾸면 이 목록도 바꾼다 (UX-12). */
val OSS_NOTICES = listOf(
    OssNotice("SQLCipher for Android", "Copyright (c) 2008-2023, ZETETIC LLC", "BSD", R.raw.license_sqlcipher),
    OssNotice("Argon2Kt", "Copyright (c) Daniel Hugenroth", "MIT", R.raw.license_argon2kt),
    OssNotice(
        "Argon2 reference implementation",
        "Copyright 2015 Daniel Dinu, Dmitry Khovratovich, Jean-Philippe Aumasson, Samuel Neves",
        "CC0 1.0 / Apache License 2.0", R.raw.license_apache2,
    ),
    OssNotice("SQLite, LibTomCrypt", "SQLCipher 에 포함", "Public Domain", null),
    OssNotice("Android Jetpack (AndroidX)", "Copyright The Android Open Source Project", "Apache License 2.0", R.raw.license_apache2),
    OssNotice("Kotlin, kotlinx.coroutines", "Copyright JetBrains s.r.o. and contributors", "Apache License 2.0", R.raw.license_apache2),
    OssNotice("Dagger, Hilt", "Copyright The Dagger Authors", "Apache License 2.0", R.raw.license_apache2),
    OssNotice(
        "Guava ListenableFuture, JSR-305, JSpecify, Jakarta Inject",
        "Copyright The Guava Authors, JSpecify Authors, Eclipse Foundation and others",
        "Apache License 2.0", R.raw.license_apache2,
    ),
)

@Composable
fun AboutRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    AboutScreen(version, onBack)
}

/** UX-12 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(version: String, onBack: () -> Unit) {
    var open by remember { mutableStateOf<OssNotice?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, version)) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_local_only)) },
                supportingContent = { Text(stringResource(R.string.about_local_only_desc)) },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.about_licenses),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            for (notice in OSS_NOTICES) {
                ListItem(
                    modifier = if (notice.text != null) Modifier.clickable { open = notice } else Modifier,
                    headlineContent = { Text(notice.name) },
                    supportingContent = { Text("${notice.copyright}\n${notice.license}") },
                )
            }
        }
    }
    open?.let { notice -> LicenseDialog(notice) { open = null } }
}

@Composable
private fun LicenseDialog(notice: OssNotice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(notice) {
        notice.text?.let { id -> context.resources.openRawResource(id).bufferedReader().use { it.readText() } }.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(notice.name) },
        text = {
            Text(
                text,
                Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
