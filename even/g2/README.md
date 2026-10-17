# Vocab G2 0.2.0 — live Android review relay

> **Not an accepted solution (2026-09-09):** The user explicitly rejected requiring the G2 app to remain running. The installed trial was closed and the Android G2 connection service was stopped. Do not resume the persistent-plugin approach as the production solution. The requirement is notification-time interaction without keeping the study app open. No verified public API for launching a closed plugin or forwarding native notification reply actions has been found.


Android 0.4.0 adds Settings → G2 링 복습 연결. Start the connection, copy its one-use pairing code (valid for 10 minutes), and enter it in the installed Even plugin. Keep the plugin running. Only the Android app's active review prompt is displayed; the ordinary G2 notification reply UI is not used. Recommendations are not answered through this review adapter.

The plugin polls the scoped HTTPS relay every 3 seconds. A foreground Android service publishes the active prompt and consumes ring answers. The existing Android Scheduler and DueTimeJitter compute the next review date. Card revision checks and `g2_action_id` metadata are committed with the card and review event, so a retry after a crash does not create a second review. The plugin persists an outgoing answer before transmission and displays success only after Android acknowledges storage. A 20-second stale heartbeat hides the prompt. Phone suspension, network loss, or closing the plugin can interrupt automatic display; Android/Even battery restrictions and screen-lock behavior still need hardware validation.

The server exposes `/g2/create` and `/g2/host` to the existing sync credential, and `/g2/pair`, `/g2/state`, `/g2/answer` to a separate session credential. The plugin package contains only the configured HTTPS origin; it contains no sync credential. Pairing codes are single-use, expire after 10 minutes, and are globally rate-limited. Session tokens and pairing codes are hashed in the relay database. A session lasts up to 30 days and can be replaced using 새 연결 코드 발급. 연결 종료 stops the service and attempts to revoke the relay session; an unreachable relay stops offering its prompt after the heartbeat expires.

## Build and deployment

- `npm test --prefix server` and `npm test --prefix even/g2`.
- Android `:app:testDeviceQaUnitTest --tests '*G2ReviewTest'` checks real scheduling, persistent retry deduplication and stale revisions.
- `npm run pack --prefix even/g2` creates `dist/even-g2/VocabReminder-G2.ehpk`; pack online to resolve the correct minimum Even App version (2.2.9 for SDK 0.0.14).
- Deploy the server update on the machine actually serving the configured HTTPS origin, then install Android 0.4.0 and Even 0.2.0. Updating a different local server does not update the public endpoint.
- The Android release still uses the project's existing embedded sync credential architecture. The user explicitly approved reuse of the existing credential for their own phone upgrade; do not publish or share that APK.

## Validation — 2026-09-09 KST

Server suite: 70 tests passed, including HTTP auth/CORS and scoped-token rejection for sync data. G2 suite: 7 tests passed. Android G2 persistence/scheduler tests: 2 passed. Signed Android 0.4.0 built, signer verified against the existing certificate, and `adb install -r` succeeded. Even 0.2.0 uploaded as a private build with network permission and installed using the Even App Developer hub update button. Pairing and real ring response remain pending.

The local managed server was deployed successfully and `/g2/pair` rejects an invalid code as expected. The configured public origin instead returned HTTP 404 for that route. Its stored Worker admin credential also returned HTTP 403 on a read-only upstream lookup. No Worker route was changed. The actual public serving location must be resolved before end-to-end ring response and locked-phone testing can proceed. No real review answer has been submitted by this implementation test.

## Historical standalone trial and native-notification investigation

### Vocab Ring Trial 0.1.0

Even G2 / R1 gesture proof of concept. Five project-authored recommendation words are bundled. Responses are saved separately through the Even host's local storage; they do **not** update the Android/Mac study schedule or sync server. No network permission, production token, personal vocabulary or backend deployment is used.

## Run on the connected Android

1. `npm ci --prefix even/g2` (Node 22).
2. `npm run build --prefix even/g2` then `npm run preview --prefix even/g2`.
3. `adb reverse tcp:5173 tcp:5173` while USB debugging is authorized.
4. In Even Realities → Even Hub → developer Scan QR, scan the QR in `dist/even-g2/open-on-g2.png`, which encodes `http://127.0.0.1:5173`. Keep the USB cable connected and preview server running.
5. If Scan QR is absent, sign in at https://hub.evenrealities.com/login using the same Even account, then restart the phone app. See https://hub.evenrealities.com/docs/get-started/quickstart/hardware.

SDK 0.0.14 is pinned for the connected phone's Even App 2.2.9. `npm run pack --prefix even/g2` writes `dist/even-g2/VocabReminder-G2.ehpk` for private portal installation. Publishing is not performed.

## Controls

- Tap to reveal the meaning.
- Swipe up/down to select 외움 / 잊음 / 나중에, tap to save.
- The saved screen requires another tap to advance (avoids accidental duplicate scoring).
- Double tap hides the answer; at the question/saved/completion screen it opens the system exit dialog.
- The phone page can export trial responses as JSON. State survives relaunch if host storage is available. Save failures stop advancement.

`npm test --prefix even/g2` checks progress transitions, persistence validation and both sysEvent/textEvent input paths. The official simulator validated reveal → choose forgotten → save one response. Real G2/R1 gestures, screen readability and phone-lock persistence still need hardware confirmation.

## Storage and next stage

`node_modules` and `dist` here are Dropbox-ignored; retain source and the small packaged artifact. There is no automatic notification-to-plugin launch. After hardware confirmation, add a narrow authenticated adapter to the existing scheduler (including idempotency and revision checks) before reflecting answers in real study records. Do not treat notification dismissal as a remembered answer.

## Device installation — 2026-09-08

Created the developer-portal project `com.vocabreminder.g2`, uploaded 0.1.0, installed it from Even App → My plugins → Developer hub, and launched it. The phone reported G2 connected and restored five saved trial responses. The local preview server and USB reverse port were then stopped. The phone's top-left exit control opens an explicit exit confirmation; cancelling it and going Home is distinct from exiting the plugin. Private installation alone does not establish long-term background survival. The subsequent beta invitation attempt is recorded below.

### Beta invitation attempt

The user approved adding their own account to the testing group. The portal confirmed “Invite sent” and lists the account as “Invited”. The field labelled “Even ID” explicitly uses the placeholder `user@email.com`. The invitation email's Accept and install link opened the official Even landing page; Open in Even Realities App then displayed “테스트 버전이 만료되었습니다. 이 버전은 더 이상 사용할 수 없습니다.” The beta invitation was not accepted. The portal only exposed Private for the uploaded build, including build details and the new-build upload flow; no beta promotion control was visible. The store's public review submission is separate and was not submitted. Private installation remains usable; beta lifecycle validation is blocked by the platform state, not established by this trial.

## Native notification reply probe — 2026-09-09 KST

Installed the isolated offline Android `deviceQa` package (`com.vocabreminder.android.qa`) and enabled its entry in Even App → Settings → Notifications. The production app's entry was already enabled. Posted the existing V-C MessagingStyle notification with a top-level Android RemoteInput reply action at `2026-09-08T15:01:38.177586Z`, then reposted at `2026-09-08T15:03:46.157310Z`. The user confirmed that the notification appears on G2 but no reply options appear. A subsequent QA preference read still contained no `last_reply`. Thus notification delivery was observed by the user, but this standard RemoteInput notification did not expose a reply path on the tested G2 setup. This does not establish the behavior of every notification variant or future firmware. No production cards or review events were changed by this probe.

The [official notification guide](https://support.evenrealities.com/hc/en-us/articles/14274501482639-Notifications) documents single tap as read-and-close and double tap as close-without-read, but does not document forwarding Android reply actions. A phone reply or injected QA response must not be counted as a G2/R1 reply. The standalone plugin remains separate from native notifications and the real review schedule.

### Corrected local infrastructure finding

The current computer reports `SJK’s Mac mini`; it is the machine running the local managed vocabulary server. The vocabulary tunnel LaunchAgent points to a removed Node binary (`/opt/homebrew/Cellar/node@22/22.22.2_2/bin/node`), is repeatedly scheduled, and reports last exit code 126. No vocabulary cloudflared process was running. A separate keyboard project's cloudflared process serves port 8788 and uses a different Worker URL; it was not changed. The configured public vocabulary Worker still responding does not prove that it routes to this local server. Its current upstream mapping remains unverified. No tunnel routing was changed during this read-only diagnosis.

### Tunnel recovery — 2026-09-09 00:36 KST

At the user's request, reinstalled the vocabulary tunnel LaunchAgent using `/opt/homebrew/opt/node@22/bin/node` instead of the removed Cellar patch-version path. Updated the installer to retain the Homebrew major-version opt path on future installs. The supervisor reports `healthy`, cloudflared `running`, and fresh local, published-tunnel, and stable-public authenticated health checks, with `last_error: null`. The normal supervisor successfully republished the Worker mapping; the earlier one-off Python admin probe's HTTP 403 did not establish that the configured management credential was invalid. The rejected always-running G2 workflow remains stopped.
