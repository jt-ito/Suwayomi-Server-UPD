package suwayomi.tachidesk.global.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsCollectorCallback
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Sends the rendered WebView page to one client as a WebRTC video track (h264/vp8, hardware-friendly, adaptive),
 * instead of a stream of separate JPEG pictures. Input stays on the websocket, which also carries the signaling.
 *
 * The client creates the offer (receive only), this answers it. Frames are pushed by the renderer via [pushFrame].
 * If the connection cannot be established (e.g. no route between server and client), [onConnectionChange] reports
 * `false` and the websocket's JPEG frames keep being used.
 */
class WebRtcStreamer(
    private val sendSignal: (String) -> Unit,
    private val onConnectionChange: (connected: Boolean) -> Unit,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        private const val MAX_BITRATE_BPS = 20_000_000
        private const val MIN_BITRATE_BPS = 4_000_000
        private const val MAX_FRAMERATE = 60.0

        private val statsExecutor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "webrtc-stats").apply { isDaemon = true }
            }

        // loading the native libraries is expensive and they are shared by every session
        private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

        /**
         * STUN/TURN servers help clients that are not on the same network as the server. Without them only direct
         * (same network) connections work, anything else falls back to the JPEG stream.
         * `TSUNDOKU_WEBRTC_ICE_SERVERS`: comma separated urls, e.g. `stun:stun.l.google.com:19302`.
         */
        private val iceServerUrls: List<String> =
            System
                .getenv("TSUNDOKU_WEBRTC_ICE_SERVERS")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }

    // Touching the factory loads the native library. It has to come first: creating the video source before that fails
    // with an UnsatisfiedLinkError (the library is loaded by the factory's static initializer).
    private val peerConnectionFactory = factory
    private val source = CustomVideoSource()
    private val track: VideoTrack = peerConnectionFactory.createVideoTrack("webview", source)
    private val pendingRemoteCandidates = CopyOnWriteArrayList<RTCIceCandidate>()

    @Volatile
    private var peer: RTCPeerConnection? = null

    @Volatile
    private var hasRemoteDescription = false

    @Volatile
    private var closed = false

    @Volatile
    var isConnected = false
        private set

    private var statsTask: ScheduledFuture<*>? = null

    fun handleOffer(sdp: String) {
        close(keepSource = true)
        closed = false
        hasRemoteDescription = false

        val config =
            RTCConfiguration().apply {
                iceServers =
                    iceServerUrls.map { url ->
                        RTCIceServer().apply { urls = listOf(url) }
                    }
            }

        val observer =
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    sendSignal(
                        buildJsonObject {
                            put("type", "rtcIce")
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        }.toString(),
                    )
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    logger.debug { "WebRTC connection state: $state" }
                    val connected = state == RTCPeerConnectionState.CONNECTED
                    if (connected != isConnected) {
                        isConnected = connected
                        if (connected) startStatsLogging() else stopStatsLogging()
                        onConnectionChange(connected)
                    }
                }
            }

        val connection = factory.createPeerConnection(config, observer) ?: error("Could not create a peer connection")
        peer = connection

        connection.setRemoteDescription(
            RTCSessionDescription(RTCSdpType.OFFER, sdp),
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    hasRemoteDescription = true
                    // the offer has a receive-only video section: attaching the track turns it into ours to send on
                    connection.addTrack(track, listOf("webview"))
                    pendingRemoteCandidates.forEach { connection.addIceCandidate(it) }
                    pendingRemoteCandidates.clear()
                    createAnswer(connection)
                }

                override fun onFailure(error: String) {
                    logger.warn { "WebRTC: failed to apply the offer: $error" }
                    fail()
                }
            },
        )
    }

    private fun createAnswer(connection: RTCPeerConnection) {
        connection.createAnswer(
            RTCAnswerOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    connection.setLocalDescription(
                        description,
                        object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                tuneEncoder(connection)
                                sendSignal(
                                    buildJsonObject {
                                        put("type", "rtcAnswer")
                                        put("sdp", description.sdp)
                                    }.toString(),
                                )
                            }

                            override fun onFailure(error: String) {
                                logger.warn { "WebRTC: failed to apply the answer: $error" }
                                fail()
                            }
                        },
                    )
                }

                override fun onFailure(error: String) {
                    logger.warn { "WebRTC: failed to create the answer: $error" }
                    fail()
                }
            },
        )
    }

    fun addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
        val iceCandidate = RTCIceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        val connection = peer
        if (connection != null && hasRemoteDescription) {
            connection.addIceCandidate(iceCandidate)
        } else {
            // candidates can arrive before the offer is applied
            pendingRemoteCandidates.add(iceCandidate)
        }
    }

    /**
     * Converts one rendered frame (BGRA, as handed over by CEF, tightly packed) and sends it.
     * [argb] must be a direct buffer, it is only read during the call.
     */
    fun pushFrame(
        argb: ByteBuffer,
        width: Int,
        height: Int,
    ) {
        if (closed || width <= 0 || height <= 0) {
            return
        }

        val buffer = NativeI420Buffer.allocate(width, height)
        val frame = VideoFrame(buffer, System.nanoTime())
        try {
            // libyuv's "ARGB" is byte order B, G, R, A, which is what CEF paints
            VideoBufferConverter.convertToI420(argb, buffer, FourCC.ARGB)
            source.pushFrame(frame)
        } catch (e: Exception) {
            logger.debug(e) { "WebRTC: dropped a frame" }
        } finally {
            frame.release()
        }
    }

    /**
     * The defaults are made for camera video and start low: a page full of text looks blocky until the bandwidth
     * estimate ramps up. Raise the ceiling and the starting point, the link is usually a LAN or at least fast.
     */
    private fun tuneEncoder(connection: RTCPeerConnection) {
        try {
            connection.senders.forEach { sender ->
                val parameters = sender.parameters
                parameters.encodings.forEach {
                    it.maxBitrate = MAX_BITRATE_BPS
                    it.minBitrate = MIN_BITRATE_BPS
                    it.maxFramerate = MAX_FRAMERATE
                }
                sender.parameters = parameters
            }
        } catch (e: Throwable) {
            logger.debug(e) { "WebRTC: could not tune the encoder" }
        }
    }

    /** What the encoder and the link actually do, to tell apart "encoder too slow" from "link too slow". */
    private fun startStatsLogging() {
        stopStatsLogging()
        statsTask =
            statsExecutor.scheduleWithFixedDelay({
                val connection = peer ?: return@scheduleWithFixedDelay
                try {
                    connection.getStats(
                        RTCStatsCollectorCallback { report ->
                            val interesting =
                                listOf(
                                    "frameWidth", "frameHeight", "framesPerSecond", "framesSent", "framesEncoded",
                                    "totalEncodeTime", "qualityLimitationReason", "qualityLimitationResolutionChanges",
                                    "encoderImplementation", "targetBitrate", "bytesSent", "nackCount", "pliCount",
                                    "keyFramesEncoded", "currentRoundTripTime", "availableOutgoingBitrate",
                                )
                            report.stats.values
                                .filter { it.type == RTCStatsType.OUTBOUND_RTP || it.type == RTCStatsType.CANDIDATE_PAIR }
                                .forEach { stat ->
                                    val values = interesting.mapNotNull { key -> stat.attributes[key]?.let { "$key=$it" } }
                                    if (values.isNotEmpty()) {
                                        logger.info { "WebRTC stats ${stat.type}: ${values.joinToString(" ")}" }
                                    }
                                }
                        },
                    )
                } catch (e: Throwable) {
                    logger.debug(e) { "WebRTC: could not read stats" }
                }
            }, 2, 2, TimeUnit.SECONDS)
    }

    private fun stopStatsLogging() {
        statsTask?.cancel(false)
        statsTask = null
    }

    private fun fail() {
        if (isConnected) {
            isConnected = false
            onConnectionChange(false)
        }
    }

    fun close() = close(keepSource = false)

    private fun close(keepSource: Boolean) {
        closed = true
        stopStatsLogging()
        val wasConnected = isConnected
        isConnected = false

        peer?.let {
            try {
                // the connection keeps a reference to the track through its sender, which blocks disposing the track
                it.senders.forEach { sender -> runCatching { it.removeTrack(sender) } }
                it.close()
            } catch (e: Throwable) {
                logger.debug(e) { "WebRTC: error while closing the connection" }
            }
        }
        peer = null
        pendingRemoteCandidates.clear()

        if (wasConnected) {
            onConnectionChange(false)
        }

        if (!keepSource) {
            try {
                track.dispose()
                source.dispose()
            } catch (e: Throwable) {
                // the native library throws an Error if something still references the object; the little memory it
                // holds is then released together with the connection
                logger.debug(e) { "WebRTC: error while disposing the video source" }
            }
        }
    }
}
