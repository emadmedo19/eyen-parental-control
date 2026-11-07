# Firebase Cloud Messaging setup for Eyen

The child Android app uses Firebase Cloud Messaging (FCM) only as a wake-up/notification transport. Supabase remains the database and API.

## Current implementation

- Firebase Android config: `android/app/google-services.json`
- Child package: `app.eyen.child`
- FCM token is registered using the child's pairing bearer token.
- Supabase stores the token in `public.pairing_sessions.fcm_token`.
- A parent location request attempts to send a high-priority data message through the FCM HTTP v1 API.
- The child displays a visible notification. The child must open it and explicitly approve the one-time location share before Android location permission is used.
- Camera, microphone, and screen capture are not enabled by this flow.

## Required server secret (not in Git)

The Supabase Edge Function expects one secret:

- Name: `FCM_SERVICE_ACCOUNT_JSON`
- Value: the complete JSON contents of a Google Cloud service-account key belonging to Firebase project `eyen-3932a`.

Create a dedicated service account in Google Cloud IAM for this Firebase project and grant only the Firebase Cloud Messaging API permission needed to send messages (the Firebase Cloud Messaging API Admin role). Enable the Firebase Cloud Messaging API if Google Cloud prompts you.

Create a JSON key for that service account, then add its full JSON contents to:

Supabase Dashboard → project `wreilawyquhhbvkkgmfy` → Edge Functions → Secrets.

Never commit this key to GitHub, never put it in `google-services.json`, and never embed it in either APK. Do not send the private key in chat. If the key is ever exposed, revoke it and create a replacement.

After saving the secret, the Edge Function can read it immediately; no redeploy is required just for a secret change.

## Android notification permission

Android 13 and newer require the child to grant notification permission. Eyen requests this permission in the child app. If it is denied, the FCM notification may not be visible.

## Operational limitations

FCM delivery is best-effort. Android force-stop, network loss, battery restrictions, and OEM background policies can delay or prevent delivery. A received notification does not grant location permission. The child must approve the request and Android must already have, or grant, location permission.
