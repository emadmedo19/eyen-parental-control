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
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    @Volatile private var closed = false
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
                        runOnUiThread {
                            if (closed) return@runOnUiThread
                            when (item.optString("signal_type")) {
                                "answer" -> acceptAnswer(item.optJSONObject("payload") ?: JSONObject())
                                "ice" -> addIce(item.optJSONObject("payload") ?: JSONObject())
                                "hangup" -> finishSession("انتهت الجلسة من جهاز الطفل.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("EyenRTC", "Signal polling failed", e)
                    runOnUiThread {
                        if (!closed) {
                            if (e is ApiException && e.statusCode in listOf(401, 404, 409, 410)) {
                                finishSession("انتهت الجلسة أو انتهت صلاحيتها. اطلب جلسة جديدة.")
                            } else {
                                status.text = "تعذر استقبال إشارات الجلسة: ${e.message ?: "خطأ شبكة"}"
                            }
                        }
                    }
                }
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
                    val message = when (s) {
                        PeerConnection.IceConnectionState.NEW -> "تم إنشاء الاتصال…"
                        PeerConnection.IceConnectionState.CHECKING -> "جارٍ البحث عن مسار اتصال مباشر…"
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "اتصل الجهازان. جارٍ استقبال الوسائط…"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "انقطع الاتصال مؤقتًا. جارٍ إعادة المحاولة…"
                        PeerConnection.IceConnectionState.FAILED -> "فشل الاتصال بين الجهازين. قد يحتاج الاتصال إلى خادم TURN أو شبكة تسمح باتصال WebRTC."
                        PeerConnection.IceConnectionState.CLOSED -> "أُغلقت جلسة الاتصال."
                        else -> "حالة الاتصال: $s"
                    }
                    runOnUiThread { status.text = message }
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
            if (mode == "camera" || mode == "screen") {
                peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
            if (mode == "camera" || mode == "audio") {
                peer?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
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
            override fun onSetSuccess() {
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    remoteDescriptionSet = true
                    pendingIce.forEach { peer?.addIceCandidate(it) }
                    pendingIce.clear()
                    status.text = "تم تبادل بيانات الاتصال. في انتظار الوسائط…"
                }
            }
            override fun onCreateFailure(e: String?) { fail(e) }
            override fun onSetFailure(e: String?) { fail(e) }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun addIce(p: JSONObject) {
        val candidate = p.optString("candidate")
        if (candidate.isNotBlank()) {
            val ice = IceCandidate(p.optString("sdpMid"), p.optInt("sdpMLineIndex"), candidate)
            if (remoteDescriptionSet) peer?.addIceCandidate(ice) else pendingIce.add(ice)
        }
    }

    private fun sendSignal(type: String, payload: JSONObject) {
        if (executor.isShutdown || (closed && type != "hangup")) return
        executor.execute {
            try { ApiClient.sendRemoteSignal(sessionId, token, type, payload) }
            catch (e: Exception) {
                android.util.Log.e("EyenRTC", "Signal send failed", e)
                runOnUiThread { if (!closed) status.text = "تعذر إرسال بيانات الاتصال: ${e.message ?: "خطأ شبكة"}" }
            }
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
        if (!closed && sessionId.isNotBlank() && token.isNotBlank()) sendSignal("hangup", JSONObject())
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
