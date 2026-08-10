package com.longdev.xiaoling.ui.conversation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberSystemVoiceInputRequest(
    currentDraft: () -> String,
    onDraftChanged: (String) -> Unit,
    onFailure: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentDraftState = rememberUpdatedState(currentDraft)
    val onDraftChangedState = rememberUpdatedState(onDraftChanged)
    val onFailureState = rememberUpdatedState(onFailure)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mergedDraft = VoiceInputDraftPolicy.merge(
                currentDraft = currentDraftState.value(),
                candidates = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
            )
            if (mergedDraft == null) {
                onFailureState.value("没有识别到可用文字")
            } else {
                // long: 系统识别结果只回填当前编辑器；发送、Agent 前缀和个人任务模式仍必须由用户显式决定。
                onDraftChangedState.value(mergedDraft)
            }
        }
    }

    return remember(context, launcher) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要写入输入框的内容")
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                onFailureState.value("当前设备没有可用的系统语音识别服务")
            } else {
                try {
                    launcher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    onFailureState.value("系统语音识别服务暂时无法启动")
                } catch (_: SecurityException) {
                    onFailureState.value("系统语音识别服务暂时无法启动")
                }
            }
        }
    }
}
