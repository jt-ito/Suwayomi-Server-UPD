package suwayomi.tachidesk.global.impl

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connects [WebRtcStreamer] to a WebRTC peer inside the same process (no browser needed) and checks that pushed frames
 * arrive decoded and in the right size.
 */
class WebRtcStreamerTest {
    private val width = 320
    private val height = 240

    @Test
    @Timeout(60)
    fun `pushed frames reach a webrtc client`() {
        // the streamer is created before anything else touches the native library, like in the server
        val connected = CountDownLatch(1)
        val receivedFrames = AtomicInteger(0)
        val receivedWidth = AtomicInteger(0)
        val receivedHeight = AtomicInteger(0)

        lateinit var client: RTCPeerConnection

        val streamer =
            WebRtcStreamer(
                sendSignal = { message ->
                    val json = Json.parseToJsonElement(message).jsonObject
                    when (json["type"]?.jsonPrimitive?.content) {
                        "rtcAnswer" ->
                            client.setRemoteDescription(
                                RTCSessionDescription(RTCSdpType.ANSWER, json["sdp"]!!.jsonPrimitive.content),
                                object : SetSessionDescriptionObserver {
                                    override fun onSuccess() {}

                                    override fun onFailure(error: String) = throw AssertionError(error)
                                },
                            )

                        "rtcIce" ->
                            client.addIceCandidate(
                                RTCIceCandidate(
                                    json["sdpMid"]!!.jsonPrimitive.content,
                                    json["sdpMLineIndex"]!!.jsonPrimitive.int,
                                    json["candidate"]!!.jsonPrimitive.content,
                                ),
                            )
                    }
                },
                onConnectionChange = { isConnected -> if (isConnected) connected.countDown() },
            )

        val clientFactory = PeerConnectionFactory()

        // a browser only receives; this peer also sends a dummy track, which is the simplest way to get a video section
        val dummySource = CustomVideoSource()
        val dummyTrack = clientFactory.createVideoTrack("client", dummySource)
        client =
            clientFactory.createPeerConnection(
                RTCConfiguration(),
                object : PeerConnectionObserver {
                    override fun onIceCandidate(candidate: RTCIceCandidate) {
                        streamer.addRemoteCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    }

                    override fun onTrack(transceiver: RTCRtpTransceiver) {
                        (transceiver.receiver.track as? VideoTrack)?.addSink { frame ->
                            receivedFrames.incrementAndGet()
                            receivedWidth.set(frame.buffer.width)
                            receivedHeight.set(frame.buffer.height)
                        }
                    }
                },
            )!!
        client.addTrack(dummyTrack, listOf("client"))

        client.createOffer(
            RTCOfferOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    client.setLocalDescription(
                        description,
                        object : SetSessionDescriptionObserver {
                            override fun onSuccess() = streamer.handleOffer(description.sdp)

                            override fun onFailure(error: String) = throw AssertionError(error)
                        },
                    )
                }

                override fun onFailure(error: String) = throw AssertionError(error)
            },
        )

        try {
            assertTrue(connected.await(30, TimeUnit.SECONDS), "the peers never connected")

            // like CEF, the page only repaints now and then: keep pushing changing frames for a few seconds
            val frame = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (n in 0 until 90) {
                for (i in 0 until width * height) {
                    frame.putInt(i * 4, (0xFF shl 24) or (((i + n * 3) and 0xFF) shl 16) or (((i shr 8) and 0xFF) shl 8) or (n and 0xFF))
                }
                streamer.pushFrame(frame, width, height)
                Thread.sleep(33)
                if (receivedFrames.get() >= 10) break
            }

            val deadline = System.currentTimeMillis() + 10_000
            while (receivedFrames.get() < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }

            assertTrue(receivedFrames.get() >= 5, "only ${receivedFrames.get()} frames were received")
            assertEquals(width, receivedWidth.get())
            assertEquals(height, receivedHeight.get())
        } finally {
            streamer.close()
            client.close()
            // the native library throws an Error while the closed connection still references these; they are
            // released together with it
            runCatching { dummyTrack.dispose() }
            runCatching { dummySource.dispose() }
        }
    }
}
