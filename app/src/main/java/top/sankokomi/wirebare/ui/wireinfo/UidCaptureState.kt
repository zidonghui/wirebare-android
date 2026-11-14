package top.sankokomi.wirebare.ui.wireinfo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局保存从 checkLogin 响应中解析出的 uid，以及调试信息。
 * UI 层通过 [uidFlow] 等观察并显示。
 */
object UidCaptureState {

    private val _uidFlow = MutableStateFlow<String?>(null)
    val uidFlow: StateFlow<String?> = _uidFlow.asStateFlow()

    /** 最近一次命中 checkLogin 的请求 URL（调试用） */
    private val _matchedUrlFlow = MutableStateFlow<String?>(null)
    val matchedUrlFlow: StateFlow<String?> = _matchedUrlFlow.asStateFlow()

    /** 最近一次捕获到的完整响应体（调试用，可能很长） */
    private val _rawFlow = MutableStateFlow<String?>(null)
    val rawFlow: StateFlow<String?> = _rawFlow.asStateFlow()

    fun onMatched(url: String) {
        _matchedUrlFlow.value = url
    }

    fun update(uid: String?, raw: String?) {
        _uidFlow.value = uid
        _rawFlow.value = raw
    }

    fun clear() {
        _uidFlow.value = null
        _matchedUrlFlow.value = null
        _rawFlow.value = null
    }
}
