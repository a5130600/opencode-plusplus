package com.opencode.mobile.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 按 "UTF-8 → GB18030 → ISO-8859-1" 的顺序探测文本编码。
 *
 * 为什么不能直接 `String(bytes)`：它硬按 UTF-8 解，中文 Windows 上导出的 CSV
 * 绝大多数是 GBK —— 解出来整屏乱码，看起来像文件坏了。
 *
 * 判据不是猜：先用**严格模式**解 UTF-8（遇到非法字节序列就报错），
 * 成功就一定是 UTF-8；失败再退到 GB18030（GBK 的超集，中文环境的兜底）。
 */
fun decodeTextSmart(bytes: ByteArray): String {
    runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
    runCatching { return String(bytes, Charset.forName("GB18030")) }
    return String(bytes, Charsets.ISO_8859_1)
}
