# RCS Gateway (_MYSMSGATE)

Captures incoming SMS/RCS on this phone by reading Google Messages'
notifications and forwards each message to your CRM backend over HTTP POST,
with a local-first / public-fallback endpoint pair. For use on your own
gateway handset to feed customer replies into the sales agent.

## Why a notification listener (not a SMS BroadcastReceiver)
Plain SMS can be read with `RECEIVE_SMS`, but **RCS** messages never reach the
SMS API — they only surface as a Google Messages notification. Reading the
notification is the only way to capture both SMS and RCS with one path.

## Build
1. Open the `project/` folder in Android Studio (Giraffe+).
2. Let Gradle sync. AGP 8.2.2 / compileSdk 34 / minSdk 26.
3. **Launcher icon:** this hand-assembled tree has no `ic_launcher`. Either
   right-click `app/res` → New → Image Asset (creates `@mipmap/ic_launcher`),
   or delete `android:icon="@mipmap/ic_launcher"` from the manifest to use the
   platform default.
4. Run on the handset, tap **Grant Notification Permission**, enable
   "RCS Gateway" in the system list, return to the app.

## Endpoint config
- **Local URL** — tried first, 3 s timeout, e.g. `http://192.168.1.50:5000/rcs`.
  Cleartext HTTP to a LAN host is blocked by default on targetSdk 34, so the
  exact host must be listed in `res/xml/network_security_config.xml`
  (currently `192.168.1.50` — change it to your box).
- **Public URL** — fallback, 5 s timeout, HTTPS. e.g. `https://api.domain.com/rcs`.
- If both fail the payload is appended to `failed_payloads.log` in the app's
  private files dir for later replay.

## Payload your backend receives
```json
{ "timestamp": 1700000000, "sender": "Name or +3725xxxxxxx", "message": "body" }
```
`timestamp` is seconds since epoch. `sender` is the notification title
(contact name if known, otherwise the number). Long messages come through
BigText, so they are not truncated.

## Notes
- The listener skips notifications with an empty body (Messages posts summary/
  "typing" notifications with no text).
- All network work runs off the main thread; the in-app log view shows
  ok/fail/spooled status per message while the app is open.
