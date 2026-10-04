package io.github.ilwoong.passvault.ui.common

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * UX-06: 아래의 모든 입력란에 `IME_FLAG_NO_PERSONALIZED_LEARNING` 을 건다.
 *
 * Compose 의 KeyboardOptions 로는 이 플래그를 설정할 수 없다. Compose 가 EditorInfo 를 채운 **뒤에**
 * 플래그를 더한다 (Compose 는 imeOptions 를 대입하므로 앞에서 더하면 덮어쓰인다).
 *
 * LOCK-03: 소프트 키보드 입력은 `Activity.onUserInteraction` 을 지나지 않는다. 키보드가 보내는
 * 편집 호출마다 [onTextInput] 을 불러 유휴 타이머가 상호작용으로 세게 한다.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(onTextInput: () -> Unit = {}, content: @Composable () -> Unit) {
    val onInput = rememberUpdatedState(onTextInput)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(withNoPersonalizedLearning(request) { onInput.value() })
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}

internal fun withNoPersonalizedLearning(request: PlatformTextInputMethodRequest, onTextInput: () -> Unit = {}) =
    PlatformTextInputMethodRequest { outAttributes ->
        reportingEdits(request.createInputConnection(outAttributes), onTextInput).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }

/**
 * 키보드가 내용을 바꾸는 호출(글자 입력·조합·삭제·키·편집 동작)에만 [onEdit] 을 부른다.
 * 주변 글자를 읽는 호출은 사용자 입력이 아니므로 세지 않는다.
 *
 * 메서드를 하나씩 재정의하지 않고 이름으로 거른다 — 플랫폼이 버전마다 오버로드를 더한다
 * (API 33 의 TextAttribute, API 34 의 replaceText).
 */
internal fun reportingEdits(target: InputConnection, onEdit: () -> Unit): InputConnection =
    Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, args ->
        if (EDIT_CALLS.any { method.name.startsWith(it) }) onEdit()
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as InputConnection

private val EDIT_CALLS = listOf("commit", "setComposingText", "delete", "sendKeyEvent", "perform", "replaceText")
