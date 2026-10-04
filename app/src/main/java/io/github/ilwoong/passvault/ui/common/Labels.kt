package io.github.ilwoong.passvault.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.ilwoong.passvault.R
import io.github.ilwoong.passvault.data.model.EntryType

@Composable
fun typeLabel(type: EntryType): String = stringResource(
    when (type) {
        EntryType.LOGIN -> R.string.type_login
        EntryType.NOTE -> R.string.type_note
        EntryType.CARD -> R.string.type_card
        EntryType.IDENTITY -> R.string.type_identity
    },
)

/** 타입을 나타내는 그림. 색은 쓰는 쪽이 입힌다. */
@Composable
fun typeIcon(type: EntryType): Painter = when (type) {
    EntryType.LOGIN -> painterResource(R.drawable.ic_type_login)
    EntryType.NOTE -> painterResource(R.drawable.ic_type_note)
    EntryType.CARD -> painterResource(R.drawable.ic_type_card)
    EntryType.IDENTITY -> rememberVectorPainter(Icons.Filled.AccountBox)
}
