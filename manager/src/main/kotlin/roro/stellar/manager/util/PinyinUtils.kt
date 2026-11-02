package roro.stellar.manager.util

object PinyinUtils {

    fun matches(text: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        if (text.lowercase().contains(q)) return true
        // 拼音首字母匹配
        val initials = text.map { if (it in '\u4e00'..'\u9fff') getInitial(it) else it.lowercaseChar() }
            .joinToString("")
        return initials.contains(q)
    }

    private fun getInitial(c: Char): Char {
        val bytes = try {
            c.toString().toByteArray(charset("GB2312"))
        } catch (_: Exception) {
            return c
        }
        if (bytes.size < 2) return c
        val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)
        return when {
            code < 0xB0A1 -> c
            code < 0xB0C5 -> 'a'
            code < 0xB2C1 -> 'b'
            code < 0xB4EE -> 'c'
            code < 0xB6EA -> 'd'
            code < 0xB7A2 -> 'e'
            code < 0xB8C1 -> 'f'
            code < 0xB9FE -> 'g'
            code < 0xBBF7 -> 'h'
            code < 0xBFA6 -> 'j'
            code < 0xC0AC -> 'k'
            code < 0xC2E8 -> 'l'
            code < 0xC4C3 -> 'm'
            code < 0xC5B6 -> 'n'
            code < 0xC5BE -> 'o'
            code < 0xC6DA -> 'p'
            code < 0xC8BB -> 'q'
            code < 0xC8F6 -> 'r'
            code < 0xCBFA -> 's'
            code < 0xCDDA -> 't'
            code < 0xCEF4 -> 'w'
            code < 0xD1B9 -> 'x'
            code < 0xD4D1 -> 'y'
            code < 0xD7FA -> 'z'
            else -> c
        }
    }
}
