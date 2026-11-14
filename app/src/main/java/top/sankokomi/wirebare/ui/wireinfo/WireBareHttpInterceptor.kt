package top.sankokomi.wirebare.ui.wireinfo

import top.sankokomi.wirebare.kernel.interceptor.http.HttpSession
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpIndexedInterceptor
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptChain
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptor
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptorFactory
import top.sankokomi.wirebare.kernel.util.uncompressGzip
import top.sankokomi.wirebare.ui.record.HttpRecorder
import top.sankokomi.wirebare.ui.record.getHttpRecordFileById
import top.sankokomi.wirebare.ui.record.httpBody
import top.sankokomi.wirebare.ui.record.id
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** 需要捕获 uid 的接口路径关键字（放宽匹配，不写死域名） */
private const val CHECK_LOGIN_PATH_KEYWORD = "checkLogin"

class WireBareHttpInterceptor(
    private val onRequest: (HttpSession) -> Unit,
    private val onResponse: (HttpSession) -> Unit
) : AsyncHttpIndexedInterceptor() {

    /** 命中目标接口的 response.id 集合，等响应结束后从记录文件里解析 body */
    private val targetIds = ConcurrentHashMap.newKeySet<String>()

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
            // 命中目标接口，登记 id，等响应结束后解析 body
            val url = session.response.url.orEmpty()
            if (url.contains(CHECK_LOGIN_PATH_KEYWORD, ignoreCase = true)) {
                targetIds.add(session.response.id)
                UidCaptureState.onMatched(url)
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
        // 先让 HttpRecorder 把响应字节落盘并关闭 writer
        HttpRecorder.saveResponseBytes(session.response, null)
        val id = session.response.id
        if (targetIds.remove(id)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // 文件里是"响应头+body"原始字节，先去掉响应头
                    val raw = getHttpRecordFileById(id).readBytes()
                    var body = raw.httpBody()
                    // Transfer-Encoding: chunked 时需要解分块
                    val isChunked = session.response.formatHead.orEmpty().any {
                        it.contains("chunked", ignoreCase = true)
                    }
                    if (isChunked) {
                        body = runCatching { dechunk(body) }.getOrDefault(body)
                    }
                    // Content-Encoding: gzip 时解压
                    val encoding = session.response.contentEncoding.orEmpty().lowercase()
                    if (encoding.contains("gzip")) {
                        body = runCatching { body.uncompressGzip() }.getOrDefault(body)
                    }
                    val rawText = body.toString(Charsets.UTF_8)
                    val uid = extractUid(rawText)
                    UidCaptureState.update(uid, rawText)
                }
            }
        }
        super.onResponseFinished(chain, session, index)
    }

    /**
     * 解 HTTP chunked 分块传输编码。
     * 格式: <hexSize>\r\n<data>\r\n ... 0\r\n\r\n
     * 若数据不是合法 chunked 格式，原样返回。
     */
    private fun dechunk(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            var lineEnd = -1
            var i = pos
            while (i < data.size - 1) {
                if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte()) {
                    lineEnd = i
                    break
                }
                i++
            }
            if (lineEnd < 0) break
            val sizeLine = String(data, pos, lineEnd - pos, Charsets.US_ASCII)
            val sizeStr = sizeLine.substringBefore(';').trim()
            val chunkSize = sizeStr.toIntOrNull(16) ?: return data
            if (chunkSize == 0) break
            val dataStart = lineEnd + 2
            val dataEnd = dataStart + chunkSize
            if (dataEnd > data.size) break
            out.write(data, dataStart, chunkSize)
            pos = dataEnd + 2
            if (pos > data.size) break
        }
        val result = out.toByteArray()
        return if (result.isEmpty()) data else result
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
