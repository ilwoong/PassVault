package io.github.ilwoong.passvault.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ilwoong.passvault.R

/** 둥근 색 바탕 위의 그림. 장식이라 스크린리더에는 읽히지 않는다. */
@Composable
fun TonalIcon(painter: Painter, size: Dp = 40.dp, iconSize: Dp = 22.dp, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.requiredSize(iconSize))
        }
    }
}

/** 앱의 방패 표식. 런처 아이콘과 같은 그림이다 (그림은 108dp 판의 가운데에 있어 크게 그려 맞춘다). */
@Composable
fun BrandMark(size: Dp = 80.dp) {
    TonalIcon(painterResource(R.drawable.ic_launcher_foreground), size = size, iconSize = size * 1.25f)
}

/** 전체 화면 안내(온보딩·해제·환영)의 머리: 표식, 제목, 설명을 가운데에 쌓는다. */
@Composable
fun ScreenHeader(title: String, body: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark()
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 목록을 묶는 작은 제목 (설정, 정보). */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
