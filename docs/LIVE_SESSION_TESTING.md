# Live session startup fixes

Both roles in the unified application need `ACCESS_NETWORK_STATE` for WebRTC's
network monitor and `MODIFY_AUDIO_SETTINGS` for its audio device module. The
WebRTC AAR does not supply these permissions through manifest merging.

The child now requests missing camera/microphone runtime permissions when the
approved session opens. It waits for a foreground-service readiness callback
before starting capture. This is especially important for MediaProjection on
Android 14+, where starting the service is asynchronous and does not mean it
has already entered the foreground.

Signaling delivery and pending ICE updates run on the main thread. Child capture
resources are released once, including the camera texture helper. Terminal API
responses stop local capture, and leaving the live screen sends a hangup.

## Device verification

Install the same unified APK on both phones. Choose Parent on one phone and Child
on the other, generate the parent code, and enter it on the child. The parent
dashboard should enable its features automatically.

Use an APK signed with the same release key to update an existing child install.
Debug APKs have a different signing key; do not uninstall an existing release
solely to install a debug APK, since uninstalling removes the local pairing.

1. Confirm existing pairing, battery and location still work.
2. Start with both devices on the same Wi-Fi. Keep the child app visible.
3. Request microphone sharing. Approve on the child and grant Android's microphone
   permission if prompted. Confirm audible speech on the parent.
4. End the session from the child. Confirm recording stops and the parent closes
   the session. Start a fresh microphone session to check retry behavior.
5. Request camera sharing. Grant camera permission when prompted. Confirm both
   moving video and audio on the parent, then end from the parent.
6. Request screen sharing. Accept Android's capture dialog. Confirm changing
   screen content on the parent. Screen mode currently has no audio track.
7. Reject each relevant permission/dialog. Confirm no capture begins and another
   session can be requested. Repeat stop/back/reopen several times.
8. Repeat on Android 14+ and with the devices on different networks.

The backend currently expires signaling two minutes after request creation.
Complete each initial connectivity test within that window; on expiry, request
a fresh session. This change does not extend the server's session lifetime.

Both apps still use STUN only. A successful local-network test does not establish
that mobile networks or restrictive NATs work. Those networks may require TURN
with server-issued, short-lived credentials; no TURN service is configured here.

For failures, record the mode, Android versions, network arrangement, exact status
text on each device, and logs:

```sh
adb logcat -v time -s EyenRTC org.webrtc AndroidRuntime
```

A successful APK build or lint run does not verify actual media delivery.
