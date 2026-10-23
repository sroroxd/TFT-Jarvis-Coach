# S24 Ultra screenshots: HUD evidence regression (0.8.4 beta)

Evidence: 2026-10-23 two real TFT images from the user (stage 1-3 and later game).
First shows a lower-left level badge **2** beside the XP purchase price **4**; the
0.8.3 overlay reports `L4(H)`. Bottom-right gold **5** agrees with the overlay.
The overlay also reports `HP100(H)` but own HP is not securely visible in the
provided first screenshot. The evidence summary shows **6 fresh, 0 confirmed**.
A later screenshot shows a Gemini overload status while TFT continues.

Changes:
- Restrict local Level OCR to the small level badge, not the neighboring XP-buy
  button. Restrict gold OCR to the coin counter instead of other numbers on screen.
- Require a stage to be visible before accepting an early frame whose prior phase
  was VOR_MATCH. This allows local corroboration at match start.
- Remove unanchored top-center HP OCR (it could read combat timer).
- A single cloud output cannot label level/gold/HP as confirmed or HOCH.
- A lower level than a previously claimed value marks that old claim uncertain;
  after two consistent observations the stored claim can be corrected at MITTEL.
- If cloud is unavailable, display that the remembered plan is **not checked for
  currency**. Previous match data is not deleted.

Testing:
- JVM unit tests exercise normalized coordinates from the supplied screenshots,
  initial cloud-only confidence, and repair of mistaken prior levels.
- GitHub Actions compiles a debug APK and runs JVM unit tests.
- Physical Android, local OCR result and actual network/TTS on the S24 Ultra are
  **not** proven by these tests. Confirm before merging or publishing as stable.

Policy: Riot's TFT developer policy restricts dynamic in-game suggestions.
This update focuses on reliability and honest status, not adding such features.
