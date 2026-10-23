# JARVIS 0.8.3 reliability beta – acceptance notes

This is a **development/QA build**, not a claim of live-coaching readiness.

Changes:
- Malformed Gemini HTTP envelopes and truncated response JSON are handled safely.
- Display of raw unexpected model/API exception text in the overlay is suppressed.
- At most one pending manual analysis trigger is held during a running request.
- Once the current request completes, the pending trigger captures a **new** image.
- End-of-service clears the pending trigger.
- The cloud request path and Android MediaProjection implementation are otherwise left unchanged.

Automated JVM unit tests:
1. Empty/one/multiple manual triggers and reset.
2. Valid JSON response envelope.
3. Truncated transport JSON shows a safe localized message.
4. Truncated model payload is not accepted.

CI builds an Android debug APK for a PR and runs JVM tests. This does not demonstrate that MediaProjection,
TTS, timing, OS lifecycle, frame freshness, or battery usage works on a physical Galaxy S24 Ultra.

**Policy:** Riot's current TFT developer policy does not permit applications that dynamically dictate
decisions from current live game state. This branch only patches reliability defects in existing code;
distribution/use of dynamic live Ranked recommendations requires a separate policy/approval review.
See https://developer.riotgames.com/docs/tft
