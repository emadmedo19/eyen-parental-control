package app.eyen.parent

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class RemoteSessionActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var sessionId = ""
    private var token = ""
    private var cursor = 0L
    private var closed = false
    private val egl by lazy { EglBase.create() }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            executor.execute {
                try {
                    val data = ApiClient.pollRemoteSignals(sessionId, token, cursor)
                    val signals = data.optJSONArray("signals")
                    if (signals != null) for (i in 0 until signals.length()) {
                        val item = signals.optJSONObject(i) ?: continue
                        cursor = maxOf(cursor, item.optLong("id", cursor))
                        when (item.optString("signal_type")) {
                            "answer" -> acceptAnswer(item.optJSONObject("payload") ?: JSONObject())
                            "ice" -> addIce(item.optJSONObject("payload") ?: JSONObject())
                            "hangup" -> runOnUiThread { finishSession("انتهت الجلسة من جهاز الطفل.") }
                        }
                    }
                } catch (e: Exception) { android.util.Log.w("EyenRTC", "Signal polling failed", e) }
                if (!closed) handler.postDelayed(this, 900L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra("session_id").orEmpty()
        val mode = intent.getStringExtra("mode") ?: "camera"
        token = getSharedPreferences("eyen_parent", MODE_PRIVATE).getString("poll_token", "").orEmpty()
        if (sessionId.isBlank() || token.isBlank()) { finish(); return }
        if (mode == "screen") {
            setContentView(TextView(this).apply {
                text = "مشاركة الشاشة ستُفعّل بعد إضافة تدفق موافقة Android MediaProjection."
                gravity = Gravity.CENTER; textSize = 18f
            })
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24); setBackgroundColor(0xff0c182c.toInt())
        }
        status = TextView(this).apply {
            text = "جارٍ الاتصال بجهاز الطفل…"; setTextColor(-1); textSize = 17f; gravity = Gravity.CENTER
        }
        renderer = SurfaceViewRenderer(this)
        val stop = Button(this).apply { text = "إنهاء الجلسة" }
        root.addView(status)
        root.addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(stop)
        setContentView(root)
        stop.setOnClickListener { finishSession("أنهيت الجلسة.") }
        renderer.init(egl.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        startRtc(mode)
    }

    private fun startRtc(mode: String) {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            ))
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    runOnUiThread { status.text = "حالة الاتصال: $s" }
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: IceCandidate?) {
                    c ?: return
                    sendSignal("ice", JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp))
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(c: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) runOnUiThread { track.addSink(renderer) }
                    if (track is AudioTrack) track.setEnabled(true)
                    runOnUiThread { status.text = "تم استقبال تدفق الوسائط من جهاز الطفل." }
                }
            }) ?: error("تعذر إنشاء اتصال WebRTC")
            if (mode == "camera") {
                peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
            peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            peer?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(offer: SessionDescription?) {
                    if (offer == null) return
                    peer?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(d: SessionDescription?) {}
                        override fun onSetSuccess() {
                            sendSignal("offer", JSONObject().put("type", "offer").put("sdp", offer.description))
                        }
                        override fun onCreateFailure(e: String?) { fail(e) }
                        override fun onSetFailure(e: String?) { fail(e) }
                    }, offer)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { fail(e) }
                override fun onSetFailure(e: String?) { fail(e) }
            }, MediaConstraints())
            handler.post(poll)
        } catch (e: Exception) {
            runOnUiThread { status.text = "تعذر تهيئة الاتصال: ${e.message}" }
        }
    }

    private fun acceptAnswer(payload: JSONObject) {
        val sdp = payload.optString("sdp")
        if (sdp.isBlank()) return
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() { runOnUiThread { status.text = "تم تبادل بيانات الاتصال. في انتظار الوسائط…" } }
            override fun onCreateFailure(e: String?) { fail(e) }
            override fun onSetFailure(e: String?) { fail(e) }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun addIce(p: JSONObject) {
        val candidate = p.optString("candidate")
        if (candidate.isNotBlank()) peer?.addIceCandidate(
            IceCandidate(p.optString("sdpMid"), p.optInt("sdpMLineIndex"), candidate)
        )
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        executor.execute {
            try { ApiClient.sendRemoteSignal(sessionId, token, type, payload) }
            catch (e: Exception) { android.util.Log.e("EyenRTC", "Signal send failed", e) }
        }
    }

    private fun fail(error: String?) {
        runOnUiThread { status.text = "تعذر إكمال الاتصال: ${error ?: "خطأ غير معروف"}" }
    }

    private fun finishSession(message: String) {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        sendSignal("hangup", JSONObject())
        peer?.close(); peer?.dispose(); peer = null
        status.text = message
        handler.postDelayed({ finish() }, 700)
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        peer?.close(); peer?.dispose()
        if (::renderer.isInitialized) { renderer.release() }
        if (::factory.isInitialized) factory.dispose()
        egl.release()
        executor.shutdown()
        super.onDestroy()
    }
}
