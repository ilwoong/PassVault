package io.github.ilwoong.passvault.ui.common

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * UX-06: 아래의 모든 입력란에 `IME_FLAG_NO_PERSONALIZED_LEARNING` 을 건다.
 *
 * Compose 의 KeyboardOptions 로는 이 플래그를 설정할 수 없다. Compose 가 EditorInfo 를 채운 **뒤에**
 * 플래그를 더한다 (Compose 는 imeOptions 를 대입하므로 앞에서 더하면 덮어쓰인다).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = NoLearningInterceptor, content = content)
}

@OptIn(ExperimentalComposeUiApi::class)
private val NoLearningInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(withNoPersonalizedLearning(request))
}

internal fun withNoPersonalizedLearning(request: PlatformTextInputMethodRequest) =
    PlatformTextInputMethodRequest { outAttributes ->
        request.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
