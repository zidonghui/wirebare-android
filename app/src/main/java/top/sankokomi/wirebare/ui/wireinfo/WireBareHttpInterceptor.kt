package top.sankokomi.wirebare.ui.wireinfo

import top.sankokomi.wirebare.kernel.interceptor.http.HttpSession
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpIndexedInterceptor
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptChain
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptor
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptorFactory
import top.sankokomi.wirebare.ui.record.HttpRecorder
import top.sankokomi.wirebare.ui.record.id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** 需要捕获 uid 的接口路径关键字 */
private const val CHECK_LOGIN_PATH_KEYWORD = "logs.quickapi.net/v2/users/checkLogin"

class WireBareHttpInterceptor(
    private val onRequest: (HttpSession) -> Unit,
    private val onResponse: (HttpSession) -> Unit
) : AsyncHttpIndexedInterceptor() {

    /**
     * 仅当响应 URL 命中 checkLogin 时，按 response.id 累积响应体，
     * 等 onResponseFinished 后统一解析。
     */
    private val bodyAccumulators = ConcurrentHashMap<String, ByteArrayOutputStream>()

    class Factory(
        private val onRequest: (HttpSession) -> Unit,
        private val onResponse: (HttpSession) -> Unit
    ) : AsyncHttpInterceptorFactory {
        override fun create(): AsyncHttpInterceptor {
            return WireBareHttpInterceptor(onRequest, onResponse)
        }
    }

    override suspend fun onRequest(
        chain: AsyncHttpInterceptChain,
        buffer: ByteBuffer,
        session: HttpSession,
        index: Int
    ) {
        if (index == 0) {
            HttpRecorder.addRequestRecord(session)
            onRequest(session)
        }
        HttpRecorder.saveRequestBytes(session.request, buffer)
        super.onRequest(chain, buffer, session, index)
    }

    override suspend fun onRequestFinished(
        chain: AsyncHttpInterceptChain,
        session: HttpSession,
        index: Int
    ) {
        HttpRecorder.saveRequestBytes(session.request, null)
        super.onRequestFinished(chain, session, index)
    }

    override suspend fun onResponse(
        chain: AsyncHttpInterceptChain,
        buffer: ByteBuffer,
        session: HttpSession,
        index: Int
    ) {
        if (index == 0) {
            HttpRecorder.addResponseRecord(session)
            HttpRecorder.addReqRspPairRecord(session)
            onResponse(session)
            // 判断是否命中目标接口
            val url = session.response.url.orEmpty()
            if (url.contains(CHECK_LOGIN_PATH_KEYWORD)) {
                bodyAccumulators[session.response.id] = ByteArrayOutputStream()
            }
        }
        // 如果该 session 需要捕获 body，则复制一份 buffer 累积（不影响后续写出文件）
        val accumulator = bodyAccumulators[session.response.id]
        if (accumulator != null) {
            runCatching {
                val dup = buffer.duplicate()
                val arr = ByteArray(dup.remaining())
                dup.get(arr)
                accumulator.write(arr)
            }
        }
        HttpRecorder.saveResponseBytes(session.response, buffer)
        super.onResponse(chain, buffer, session, index)
    }

    override suspend fun onResponseFinished(
        chain: AsyncHttpInterceptChain,
        session: HttpSession,
        index: Int
    ) {
        HttpRecorder.saveResponseBytes(session.response, null)
        // 命中目标接口的响应已收完，解析 uid
        val accumulator = bodyAccumulators.remove(session.response.id)
        if (accumulator != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val raw = accumulator.toByteArray().toString(Charsets.UTF_8)
                    val uid = extractUid(raw)
                    UidCaptureState.update(uid, raw)
                }
            }
        }
        super.onResponseFinished(chain, session, index)
    }

    /**
     * 从 checkLogin 响应体中提取 data.uid。
     * 响应结构示例：
     * { "result": true, "data": { "uid": "2026110606240854", ... }, "error": {...} }
     */
    private fun extractUid(raw: String): String? {
        return runCatching {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return@runCatching null
            data.optString("uid").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
