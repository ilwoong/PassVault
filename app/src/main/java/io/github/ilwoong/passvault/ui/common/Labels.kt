package io.github.ilwoong.passvault.ui.common

import androidx.compose.runtime.Composable
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
