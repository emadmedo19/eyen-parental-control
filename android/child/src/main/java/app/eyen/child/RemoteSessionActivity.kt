package app.eyen.child

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.pm.PackageManager
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class RemoteSessionActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var sessionId = ""
    private var token = ""
    private var mode = "camera"
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
                            "offer" -> acceptOffer(item.optJSONObject("payload") ?: JSONObject())
                            "ice" -> addIce(item.optJSONObject("payload") ?: JSONObject())
                            "hangup" -> runOnUiThread { finishSession("أنهى ولي الأمر الجلسة.") }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("EyenRTC", "Signal polling failed", e)
                }
                if (!closed) handler.postDelayed(this, 900L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra("session_id").orEmpty()
        mode = intent.getStringExtra("mode") ?: "camera"
        token = getSharedPreferences("eyen_child", MODE_PRIVATE).getString("child_token", "").orEmpty()
        if (sessionId.isBlank() || token.isBlank()) { finish(); return }
        if (mode == "screen") {
            showMessage("مشاركة الشاشة تحتاج موافقة Android منفصلة، وستُفعّل في المرحلة التالية.")
            return
        }
        if (mode == "camera" && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showMessage("لم تُمنح أذونات الكاميرا والميكروفون. لم يبدأ أي بث.")
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 30, 24, 30)
            setBackgroundColor(0xff0c182c.toInt())
        }
        status = TextView(this).apply {
            text = "الجلسة بموافقتك قيد الاتصال…\nستظل الكاميرا والميكروفون ظاهرين أثناء البث."
            setTextColor(-1); textSize = 18f; gravity = Gravity.CENTER
        }
        val stop = Button(this).apply { text = "إنهاء المشاركة الآن" }
        root.addView(status); root.addView(stop)
        setContentView(root)
        stop.setOnClickListener { finishSession("أوقفت المشاركة من جهاز الطفل.") }
        startRtc()
    }

    private fun showMessage(message: String) {
        setContentView(TextView(this).apply {
            text = "$message\n\nيمكنك الرجوع للتطبيق بأمان."
            textSize = 18f; gravity = Gravity.CENTER; setPadding(24,24,24,24)
        })
    }

    private fun startRtc() {
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
                override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }) ?: error("تعذر إنشاء اتصال WebRTC")
            if (mode == "camera") addCameraTrack()
            addAudioTrack()
            handler.post(poll)
        } catch (e: Exception) {
            runOnUiThread { status.text = "تعذر تهيئة البث: ${e.message}" }
        }
    }

    private fun addCameraTrack() {
        val enumerator = Camera2Enumerator(this)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        capturer = enumerator.createCapturer(name, null)
        val source = factory.createVideoSource(false)
        videoSource = source
        val helper = SurfaceTextureHelper.create("EyenCamera", egl.eglBaseContext)
        capturer?.initialize(helper, this, source.capturerObserver)
        capturer?.startCapture(640, 480, 20)
        val track = factory.createVideoTrack("eyen_video", source)
        peer?.addTrack(track, listOf("eyen_stream"))
    }

    private fun addAudioTrack() {
        val constraints = MediaConstraints()
        audioSource = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("eyen_audio", audioSource)
        peer?.addTrack(track, listOf("eyen_stream"))
    }

    private fun acceptOffer(payload: JSONObject) {
        val sdp = payload.optString("sdp")
        if (sdp.isBlank()) return
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() {
                peer?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        peer?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(d: SessionDescription?) {}
                            override fun onSetSuccess() {
                                sendSignal("answer", JSONObject().put("type", "answer").put("sdp", answer.description))
                            }
                            override fun onCreateFailure(e: String?) { fail(e) }
                            override fun onSetFailure(e: String?) { fail(e) }
                        }, answer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(e: String?) { fail(e) }
                    override fun onSetFailure(e: String?) { fail(e) }
                }, MediaConstraints())
            }
            override fun onCreateFailure(e: String?) { fail(e) }
            override fun onSetFailure(e: String?) { fail(e) }
        }, description)
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
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        peer?.close(); peer?.dispose(); peer = null
        videoSource?.dispose(); audioSource?.dispose()
        status.text = message
        handler.postDelayed({ finish() }, 700)
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); peer?.dispose(); videoSource?.dispose(); audioSource?.dispose()
        if (::factory.isInitialized) factory.dispose()
        egl.release()
        executor.shutdown()
        super.onDestroy()
    }
}
