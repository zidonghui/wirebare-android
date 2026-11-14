package top.sankokomi.wirebare.ui.wireinfo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局保存从 logs.quickapi.net/v2/users/checkLogin 响应中解析出的 uid。
 * UI 层通过 [uidFlow] 观察并显示。
 */
object UidCaptureState {

    private val _uidFlow = MutableStateFlow<String?>(null)

    val uidFlow: StateFlow<String?> = _uidFlow.asStateFlow()

    /** 最近一次捕获到的完整响应体（调试用） */
    private val _rawFlow = MutableStateFlow<String?>(null)
    val rawFlow: StateFlow<String?> = _rawFlow.asStateFlow()

    fun update(uid: String?, raw: String?) {
        _uidFlow.value = uid
        _rawFlow.value = raw
    }

    fun clear() {
        _uidFlow.value = null
        _rawFlow.value = null
    }
}
