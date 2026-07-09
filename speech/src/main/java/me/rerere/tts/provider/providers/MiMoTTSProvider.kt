package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

// MiMo 流式音频按文档示例使用 24kHz PCM16LE
private const val MIMO_SAMPLE_RATE = 24000
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
// 只关心 delta.audio.data 其余字段忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MiMoChunk(
    val choices: List<MiMoChoice> = emptyList()
)

@Serializable
private data class MiMoChoice(
    val delta: MiMoDelta? = null
)

@Serializable
private data class MiMoDelta(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null
)

internal fun decodeMiMoAudioData(data: String): ByteArray? {
    val payload = data.trim()
    // [DONE] 表示流结束 不输出音频
    if (payload == "[DONE]") return null
    // 非 [DONE] 的 data 视为 JSON 片段 解析失败直接上抛
    val chunk = mimoJson.decodeFromString<MiMoChunk>(payload)
    val encoded = chunk.choices.firstOrNull()?.delta?.audio?.data ?: return null
    // 空字符串视为无音频片段
    if (encoded.isBlank()) return null
    return Base64.getDecoder().decode(encoded)
}

internal class MiMoSseProcessor(
    private val model: String,
    private val voice: String
) {
    private var hasAudio = false
    // metadata 只构造一次 贯穿整个流
    private val metadata = mapOf(
        "provider" to "mimo",
        "model" to model,
        "voice" to voice
    )

    fun process(event: SseEvent): AudioChunk? {
        return when (event) {
            is SseEvent.Open -> null
            is SseEvent.Event -> {
                // 只处理包含 audio.data 的增量事件 其他事件忽略
                val pcmData = decodeMiMoAudioData(event.data) ?: return null
                hasAudio = true
                AudioChunk(
                    data = pcmData,
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    metadata = metadata
                )
            }

            is SseEvent.Closed -> {
                // 如果整段流没有任何音频片段 直接报错
                if (!hasAudio) {
                    throw IllegalStateException("MiMo TTS returned no audio chunks")
                }
                // 流关闭时补一个终结 chunk 便于播放器收尾
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    isLast = true,
                    metadata = metadata
                )
            }

            is SseEvent.Failure -> throw event.throwable ?: Exception("MiMo TTS streaming failed")
        }
    }
}

/**
 * 导演模式解析结果
 */
internal data class DirectorModePayload(
    val userContent: String,
    val assistantContent: String
)

/**
 * 解析 MiMo 导演模式文本。
 * 期望格式：
 *   角色：...
 *   场景：...
 *   指导：...
 *   台词：...
 *
 * 返回 userContent（角色+场景+指导）和 assistantContent（台词）。
 * 如果格式不完整返回 null，走普通 TTS 流程。
 */
internal fun parseDirectorMode(text: String): DirectorModePayload? {
    val markers = listOf("角色：", "场景：", "指导：", "台词：")
    if (markers.any { !text.contains(it) }) return null

    fun extractAfter(marker: String, endMarkers: List<String>): String {
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val contentStart = start + marker.length
        val endPositions = endMarkers.mapNotNull { endMarker ->
            val idx = text.indexOf(endMarker, contentStart)
            if (idx >= 0) idx else null
        }
        val end = endPositions.minOrNull() ?: text.length
        return text.substring(contentStart, end).trim()
    }

    val role = extractAfter("角色：", listOf("场景：", "指导：", "台词："))
    val scene = extractAfter("场景：", listOf("指导：", "台词："))
    val guidance = extractAfter("指导：", listOf("台词："))
    val line = extractAfter("台词：", emptyList())

    if (role.isBlank() && scene.isBlank() && guidance.isBlank()) return null
    if (line.isBlank()) return null

    val userContent = buildString {
        if (role.isNotBlank()) appendLine("角色：$role")
        if (scene.isNotBlank()) appendLine()
        if (scene.isNotBlank()) appendLine("场景：$scene")
        if (guidance.isNotBlank()) appendLine()
        if (guidance.isNotBlank()) appendLine("指导：$guidance")
    }.trim()

    return DirectorModePayload(userContent = userContent, assistantContent = line)
}

class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // MiMo 支持两种表现力控制方式：标签模式 与 导演模式
    override val promptGuidance: String = """
        The active text-to-speech engine (MiMo v2.5) supports two expressive modes.
        When you call the text_to_speech tool, put the control instructions ONLY inside the "text" argument — never in your visible reply to the user.

        1. Tag mode (for simple emotional tuning):
           - Overall style tag at the beginning: (style). Combine styles with spaces, e.g. (开心 磁性).
             Common styles: 开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语 .
           - Inline tags anywhere: [吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊] .
           - Do NOT put punctuation inside brackets. Use spaces to separate styles.
           - Do not use markdown emphasis (*, _) — it will be stripped.

        2. Director mode (for rich character/scene performance, use this for roleplay, storytelling, or dramatic scenes):
           - Format the "text" argument EXACTLY like this:
             角色：〈一句话描述角色身份、性格、当前情绪〉
             场景：〈一句话描述环境、氛围、对话对象〉
             指导：〈语速、语气、停顿、咬字、气息等表演要求〉
             台词：〈要朗读的实际文本〉
           - Each section should be 1-3 sentences. Only the text after "台词：" will be spoken aloud.
           - Do NOT mix director mode with tag mode in the same text argument.

        Choose director mode when the user wants character acting, storytelling with voices, or emotional scenes. Otherwise use tag mode.
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val director = parseDirectorMode(request.text)
        val messages: JsonArray = if (director != null) {
            // 导演模式：user 消息放角色/场景/指导，assistant 消息放台词
            buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", director.userContent)
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", director.assistantContent)
                })
            }
        } else {
            // 普通模式：单条 assistant 消息
            buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", request.text)
                })
            }
        }

        // OpenAI 兼容的 chat/completions SSE 流式返回 音频增量在 delta.audio.data
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("messages", messages)
            put("audio", buildJsonObject {
                put("format", "pcm16")
                put("voice", providerSetting.voice)
            })
            put("stream", true)
        }

        // baseUrl 允许用户在设置页自定义 这里直接拼接路径
        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            // MiMo 使用 api-key 头传 token
            .addHeader("api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            // JsonObject 的 toString 会输出 JSON 字符串
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val processor = MiMoSseProcessor(
            model = providerSetting.model,
            voice = providerSetting.voice
        )

        httpClient.sseFlow(httpRequest).collect { event ->
            processor.process(event)?.let { emit(it) }
        }
    }
}
