package app.eyen.child

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.content.Intent
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
    private var cameraHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenPermissionData: Intent? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var sessionId = ""
    private var token = ""
    private var mode = "camera"
    private var cursor = 0L
    @Volatile private var closed = false
    private var rtcStarted = false
    private var mediaReleased = false
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
                                "offer" -> acceptOffer(item.optJSONObject("payload") ?: JSONObject())
                                "ice" -> addIce(item.optJSONObject("payload") ?: JSONObject())
                                "hangup" -> finishSession("أنهى ولي الأمر الجلسة.")
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
        mode = intent.getStringExtra("mode") ?: "camera"
        token = getSharedPreferences("eyen_child", MODE_PRIVATE).getString("child_token", "").orEmpty()
        if (sessionId.isBlank() || token.isBlank()) { finish(); return }
        // Prepare status UI before requesting Android screen-capture consent.
        // Screen mode callbacks also update this view after permission is granted.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 30, 24, 30)
            setBackgroundColor(0xff0c182c.toInt())
        }
        status = TextView(this).apply {
            text = if (mode == "screen") "في انتظار موافقة Android على مشاركة الشاشة…"
                else "الجلسة بموافقتك قيد الاتصال…\\nستظل الكاميرا والميكروفون ظاهرين أثناء البث."
            setTextColor(-1); textSize = 18f; gravity = Gravity.CENTER
        }
        val stop = Button(this).apply { text = "إنهاء المشاركة الآن" }
        root.addView(status); root.addView(stop)
        setContentView(root)
        stop.setOnClickListener { finishSession("أوقفت المشاركة من جهاز الطفل.") }

        if (mode == "screen") {
            requestScreenCapturePermission()
            return
        }
        if ((mode == "camera" && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ||
            ((mode == "camera" || mode == "audio") && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (mode == "camera") permissions.add(Manifest.permission.CAMERA)
            requestPermissions(permissions.toTypedArray(), MEDIA_PERMISSION_REQUEST)
            return
        }
        startMediaService()
    }

    private fun startMediaService() {
        try {
            val mediaService = Intent(this, RemoteMediaForegroundService::class.java)
                .putExtra(RemoteMediaForegroundService.EXTRA_MODE, mode)
                .putExtra(RemoteMediaForegroundService.EXTRA_READY_RECEIVER, readyReceiver())
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(mediaService) else startService(mediaService)
        } catch (error: Exception) {
            finishSession("تعذر بدء إشعار حماية الجلسة: ${error.message}")
        }
    }

    private fun readyReceiver() = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (!closed && !isFinishing && !rtcStarted) startRtc()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MEDIA_PERMISSION_REQUEST || closed) return
        val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = mode != "camera" || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && cameraGranted) startMediaService()
        else finishSession("لم تبدأ المشاركة لعدم منح أذونات الكاميرا أو الميكروفون.")
    }

    private fun startRtc() {
        if (closed || rtcStarted) return
        rtcStarted = true
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
            when (mode) {
                "camera" -> addCameraTrack()
                "screen" -> addScreenTrack()
            }
            if (mode == "camera" || mode == "audio") addAudioTrack()
            handler.post(poll)
        } catch (e: Exception) {
            finishSession("تعذر تهيئة البث: ${e.message}")
        }
    }

    private fun addCameraTrack() {
        val enumerator = Camera2Enumerator(this)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("لا توجد كاميرا متاحة")
        capturer = enumerator.createCapturer(name, null) ?: error("تعذر فتح الكاميرا")
        val source = factory.createVideoSource(false)
        videoSource = source
        val helper = SurfaceTextureHelper.create("EyenCamera", egl.eglBaseContext)
        cameraHelper = helper
        capturer?.initialize(helper, this, source.capturerObserver)
        capturer?.startCapture(640, 480, 20)
        val track = factory.createVideoTrack("eyen_video", source)
        peer?.addTrack(track, listOf("eyen_stream"))
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_CAPTURE_REQUEST || closed) return
        if (resultCode != RESULT_OK || data == null) {
            finishSession("رفضت موافقة مشاركة الشاشة.")
            return
        }
        screenPermissionData = data
        try {
            val service = Intent(this, MediaProjectionForegroundService::class.java)
                .putExtra(MediaProjectionForegroundService.EXTRA_READY_RECEIVER, readyReceiver())
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } catch (error: Exception) {
            finishSession("تعذر بدء خدمة مشاركة الشاشة: ${error.message}")
        }
    }

    private fun addScreenTrack() {
        val data = screenPermissionData ?: error("موافقة مشاركة الشاشة غير متاحة")
        val source = factory.createVideoSource(true)
        videoSource = source
        screenHelper = SurfaceTextureHelper.create("EyenScreenCapture", egl.eglBaseContext)
        screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { runOnUiThread { finishSession("أوقف Android مشاركة الشاشة.") } }
        })
        screenCapturer?.initialize(screenHelper!!, applicationContext, source.capturerObserver)
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / 2).coerceAtLeast(360)
        val height = (metrics.heightPixels / 2).coerceAtLeast(640)
        screenCapturer?.startCapture(width, height, 15)
        peer?.addTrack(factory.createVideoTrack("eyen_screen", source), listOf("eyen_stream"))
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
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    remoteDescriptionSet = true
                    pendingIce.forEach { peer?.addIceCandidate(it) }
                    pendingIce.clear()
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
            }
            override fun onCreateFailure(e: String?) { fail(e) }
            override fun onSetFailure(e: String?) { fail(e) }
        }, description)
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
        releaseMedia()
        status.text = message
        handler.postDelayed({ finish() }, 1500)
    }

    private fun releaseMedia() {
        if (mediaReleased) return
        mediaReleased = true
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        screenCapturer?.dispose(); screenCapturer = null
        screenHelper?.dispose(); screenHelper = null
        cameraHelper?.dispose(); cameraHelper = null
        stopService(Intent(this, MediaProjectionForegroundService::class.java))
        stopService(Intent(this, RemoteMediaForegroundService::class.java))
        peer?.close(); peer?.dispose(); peer = null
        videoSource?.dispose(); audioSource?.dispose()
        videoSource = null; audioSource = null
    }

    override fun onDestroy() {
        if (!closed && sessionId.isNotBlank() && token.isNotBlank()) sendSignal("hangup", JSONObject())
        closed = true
        handler.removeCallbacksAndMessages(null)
        releaseMedia()
        if (::factory.isInitialized) factory.dispose()
        egl.release()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 7412
        private const val MEDIA_PERMISSION_REQUEST = 7413
    }
}
