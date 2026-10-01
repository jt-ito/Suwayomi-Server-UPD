package suwayomi.tachidesk.global.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsMessageContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jetty.websocket.core.CloseStatus
import suwayomi.tachidesk.manga.impl.update.Websocket
import java.nio.ByteBuffer

object WebView : Websocket<String>() {
    private val logger = KotlinLogging.logger {}
    private var driver: KcefWebView? = null

    val hasClients: Boolean
        get() = clients.isNotEmpty()

    override fun addClient(ctx: WsContext) {
        if (clients.isNotEmpty()) {
            // TODO: allow multiple concurrent accesses?
            clients.forEach { it.value.closeSession(CloseStatus(1001, "Other client connected")) }
            clients.clear()
        }
        if (driver == null) {
            driver = KcefWebView()
        }
        super.addClient(ctx)
        ctx.enableAutomaticPings()
    }

    override fun removeClient(ctx: WsContext) {
        super.removeClient(ctx)
        if (clients.isEmpty()) {
            val start = System.nanoTime()
            driver?.destroy()
            driver = null
            logger.info { "WebView closed in ${(System.nanoTime() - start) / 1_000_000} ms" }
        }
    }

    /** Sends a binary frame (e.g. a rendered page image), which avoids the size/parsing overhead of encoding it in json. */
    fun notifyAllClientsBinary(data: ByteArray) {
        clients.values.forEach { it.send(ByteBuffer.wrap(data)) }
    }

    override fun notifyClient(
        ctx: WsContext,
        value: String?,
    ) {
        if (value != null) {
            ctx.send(value)
        }
    }

    @Serializable
    sealed class TypeObject

    @Serializable
    @SerialName("loadUrl")
    private data class LoadUrlMessage(
        val url: String,
        val width: Int,
        val height: Int,
        val dpr: Double = 1.0,
    ) : TypeObject()

    @Serializable
    @SerialName("resize")
    private data class ResizeMessage(
        val width: Int,
        val height: Int,
        val dpr: Double = 1.0,
    ) : TypeObject()

    @Serializable
    @SerialName("event")
    data class JsEventMessage(
        val eventType: String,
        val clickX: Float,
        val clickY: Float,
        val button: Int? = null,
        val ctrlKey: Boolean? = null,
        val shiftKey: Boolean? = null,
        val altKey: Boolean? = null,
        val metaKey: Boolean? = null,
        val key: String? = null,
        val code: String? = null,
        val clientX: Float? = null,
        val clientY: Float? = null,
        val deltaY: Float? = null,
    ) : TypeObject()

    @Serializable
    @SerialName("paste")
    data class JsPasteMessage(
        val data: String,
    ) : TypeObject()

    @Serializable
    @SerialName("copy")
    class JsCopyMessage : TypeObject()

    @Serializable
    @SerialName("ping")
    class JsPingMessage : TypeObject()

    // WebRTC signaling: the client offers a receive-only video connection, the server answers (see WebRtcStreamer)
    @Serializable
    @SerialName("rtcOffer")
    class JsRtcOfferMessage(
        val sdp: String,
    ) : TypeObject()

    @Serializable
    @SerialName("rtcIce")
    class JsRtcIceMessage(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    ) : TypeObject()

    // the client gave up on the video stream (or left it): go back to jpeg frames
    @Serializable
    @SerialName("rtcStop")
    class JsRtcStopMessage : TypeObject()

    // sent by the client when its tab gets hidden/shown, so nothing is rendered while nobody can see it
    @Serializable
    @SerialName("visibility")
    class JsVisibilityMessage(
        val hidden: Boolean,
    ) : TypeObject()

    // sent by the client after displaying (or dropping) a rendered frame
    @Serializable
    @SerialName("frameAck")
    class JsFrameAckMessage : TypeObject()

    override fun handleRequest(ctx: WsMessageContext) {
        val dr = driver ?: return
        try {
            val event = Json.decodeFromString<TypeObject>(ctx.message())
            when (event) {
                is LoadUrlMessage -> {
                    val url = event.url
                    dr.loadUrl(url)
                    dr.resize(event.width, event.height, event.dpr)
                    logger.debug { "Loading URL $url" }
                }

                is ResizeMessage -> {
                    dr.resize(event.width, event.height, event.dpr)
                }

                is JsEventMessage -> {
                    dr.event(event)
                }

                is JsPasteMessage -> {
                    dr.paste(event.data)
                }

                is JsCopyMessage -> {
                    dr.copy()
                }

                is JsPingMessage -> {
                    notifyAllClients("{\"type\":\"pong\"}")
                }

                is JsRtcOfferMessage -> {
                    dr.handleRtcOffer(event.sdp)
                }

                is JsRtcIceMessage -> {
                    dr.handleRtcIce(event.candidate, event.sdpMid, event.sdpMLineIndex)
                }

                is JsRtcStopMessage -> {
                    dr.stopRtc()
                }

                is JsVisibilityMessage -> {
                    dr.setHidden(event.hidden)
                }

                is JsFrameAckMessage -> {
                    dr.frameAck()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to deserialize client request: ${ctx.message()}" }
        }
    }
}
