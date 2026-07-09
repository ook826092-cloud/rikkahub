package me.rerere.tts.controller

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 */
class TextChunker(
    private val maxChunkLength: Int = 150
) {
    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        // 导演模式文本需要作为整体发送给 TTS provider，不可切分
        // 否则 user/assistant 消息结构会被破坏
        if (isDirectorModeText(text)) {
            return listOf(TtsChunk(index = 0, text = text.trim()))
        }

        val paragraphs = text.split("\n\n")
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

        val chunks = paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                paragraph
                    .split(punctuationRegex)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String
)

/**
 * 检测文本是否为 MiMo TTS 导演模式格式。
 * 导演模式需要包含角色、场景、指导、台词四个部分，
 * 且必须作为一个完整请求发送，不能切分。
 */
private fun isDirectorModeText(text: String): Boolean {
    return text.contains("角色：") &&
            text.contains("场景：") &&
            text.contains("指导：") &&
            text.contains("台词：")
}

