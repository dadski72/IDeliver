# CLAUDE.md

Project context for Claude Code. Read this before making changes.

## What this is

An Android app for gig delivery drivers. It reads incoming DoorDash and Uber Eats
offers, evaluates them against user-defined rules, and shows a suggestion overlay
so the driver can decide fast.

Personal-use app, sideloaded. Not shipping to Play Store.

## Hard constraints — do not violate

1. **The app never accepts or declines an offer.** No gesture injection, no
   `performAction(ACTION_CLICK)`, no automation of any kind against the DoorDash
   or Uber Eats apps. The app displays a recommendation; the human taps the
   button. This is what keeps the user's driver accounts alive. If a task seems
   to require tapping something in another app, stop and flag it.
2. **Never obscure or sit near the accept/decline buttons.** The overlay must be
   positioned so a mis-tap is impossible.
3. **No credentials, no account scraping, no API reverse-engineering.** The app
   only reads what the OS already surfaces: notifications, and (secondarily)
   on-screen content.
4. **All captured data stays on device.** No analytics SDKs, no crash reporters
   that ship payload content, no network calls with offer data. Offer text can
   contain customer addresses.

## Stack

- Kotlin, native Android. Not React Native — the capture, overlay, and service
  work is all platform API surface.
- Jetpack Compose for settings/analytics UI. The overlay itself is a plain
  `WindowManager` view (Compose in an overlay window is more trouble than it's
  worth).
- Room for persistence. Kotlin coroutines/Flow.
- ML Kit text recognition, on-device only, for the OCR path (later phase).
- minSdk 29, target current stable.

## Module structure

```
app/            activities, settings UI, onboarding + permission flow
core-capture/   NotificationListenerService (primary), AccessibilityService (secondary)
core-parse/     raw captured text -> Offer
core-rules/     Offer + Settings -> Verdict     [pure, no Android deps]
core-overlay/   the floating suggestion box
core-data/      Room, offer log, analytics queries
```

The important seam is the `Offer` type. Capture and parsing are the fragile
layers that break whenever DoorDash or Uber ships an update. Nothing downstream
of `Offer` should ever know whether a value came from a notification, an
accessibility node, or OCR.

## Core types

```kotlin
data class Offer(
  val platform: Platform,        // DOORDASH | UBER_EATS
  val source: CaptureSource,     // NOTIFICATION | ACCESSIBILITY | OCR
  val payCents: Int?,
  val miles: Double?,
  val estMinutes: Int?,
  val storeName: String?,
  val stops: Int?,
  val seenAt: Instant,
  val confidence: Float,
  val rawText: String            // always retained — this is how parser breaks get fixed
)

data class Verdict(
  val decision: Decision,        // ACCEPT | MARGINAL | DECLINE | INSUFFICIENT_DATA
  val dollarsPerMile: Double?,
  val dollarsPerHour: Double?,
  val netAfterMileage: Double?,
  val reasons: List<String>      // "below $2.00/mi", "blacklisted store"
)
```

Every `Offer` field except `platform`, `source`, `seenAt`, `confidence`, and
`rawText` is nullable and must stay that way. Notification payloads are thin and
inconsistent; the rules engine degrades gracefully or returns
`INSUFFICIENT_DATA`. Never fabricate a default for a missing field.

## Platform realities (verified July 2026 — re-check before relying on these)

- **NotificationListenerService is the primary capture path.** It is the only one
  that works while the source app is backgrounded, survives screen lock, and
  needs no per-session re-consent. Multi-apping depends entirely on this.
- **AccessibilityService is secondary and has a limited shelf life.** Android 17
  blocks non-`isAccessibilityTool` apps from the accessibility API under Advanced
  Protection Mode, and revokes it from installed apps. Treat this path as
  enrichment that may disappear. The app must remain fully functional without it.
- **MediaProjection/OCR is a last-resort later phase.** Per-session user consent
  is required on Android 14+, the token cannot be cached across restarts, capture
  auto-stops on screen lock, and a prominent status bar chip is shown throughout.
  Poor fit for a phone mounted in a car. Do not build on it yet.
- Notifications get **updated in place** — same `key`, new content, as timers
  count down. Dedupe by `key`; one logical offer must not spawn repeat overlays.
- `notification.contentIntent` is a `PendingIntent` that jumps straight to the
  offer screen. Wire the overlay to fire it. This is the highest-value affordance
  in the whole app.
- Some offer notifications are custom `RemoteViews` rather than standard
  templates, so `extras` may come back near-empty. Extract what you can and mark
  low confidence.
- Foreground service types are mandatory and enforced. Declare correctly or the
  service throws `SecurityException` on start.

## Current phase

**Phase 1 — fixture collection.** The dump harness in `core-capture/` is the
first thing built, and it ships behind a permanent debug flag rather than being
throwaway code. It records every notification from the DoorDash and Uber Eats
packages to timestamped JSONL on disk: package, key, postTime, all `extras`
keys and values, flattened `RemoteViews` text, and whether the event was a post
or an update. Includes an in-app export button — collection must not require adb.

Parser development is blocked until real fixtures exist. Do not write
`core-parse/` against guessed notification formats; the field names and copy are
undocumented, version-specific, and change often.

Buildable in parallel, none of it dependent on payload shape:
`core-rules/` (pure functions, unit-tested against synthetic offers),
`core-data/` (Room schema, nullable fields), `core-overlay/` (driven by a fake
verdict generator), settings UI, permission onboarding.

## Conventions

- `core-rules/` is a pure Kotlin module with no Android dependencies and real
  unit test coverage. It is the only place where a silent bug directly costs the
  user money.
- Parser work is driven by fixture files in `core-parse/src/test/fixtures/`.
  Every parser change adds the fixture that motivated it.
- Ship a `MARGINAL` verdict state, not a binary yes/no. Most offers land near the
  line and a two-state badge trains the driver to stop thinking.
- Overlay must be readable at a glance in direct daylight.
- Mileage deduction uses the current IRS rate, stored as configurable settings,
  not a hardcoded constant.