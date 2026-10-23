package com.pete.tftjarvis;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_API_KEY = "apiKey";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_PROVIDER = "provider";

    private static final String CHANNEL_ID = "tft_capture";
    private static final int NOTIFICATION_ID = 17;
    private static volatile ScreenCaptureService instance;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final AtomicBoolean awaitingCleanFrame = new AtomicBoolean(false);
    private final ManualRequestLatch pendingManualRequest = new ManualRequestLatch();
    private final AtomicLong captureSequence = new AtomicLong(0L);
    private final Handler mainHandler = new Handler();
    private final SmartFrameDetector smartFrameDetector = new SmartFrameDetector();
    private final LocalHudPerception localHudPerception = new LocalHudPerception();
    private final EvidenceLedger evidenceLedger = new EvidenceLedger();
    // Discard pending cloud replies if the player resets the match memory.
    private final AtomicLong stateGeneration = new AtomicLong(0L);
    private volatile long rateLimitedUntil = 0L;
    private volatile long lastAnalyzeTapAt = 0L;
    private volatile boolean smartAutoEnabled = true;
    private volatile boolean speechMuted = false;
    // Privacy-safe operational counters: never retain screen pixels, keys or prompts.
    private final AtomicLong capturedFrameCount = new AtomicLong();
    private final AtomicLong cloudAttemptCount = new AtomicLong();
    private final AtomicLong cloudAcceptedCount = new AtomicLong();
    private final AtomicLong cloudErrorCount = new AtomicLong();
    private volatile long lastOcrMs;
    private volatile long lastCloudMs;
    private volatile String lastErrorType = "-";
    private volatile boolean currentAnalysisManual = false;
    private volatile String lastPhase = "UNKLAR";
    private volatile boolean matchEndedLock = false;
    private String lastSpokenKey = "";
    private long lastSpokenAt = 0L;

    private String provider = ConfigStore.PROVIDER_GEMINI;
    private String apiKey = "";
    private String model = GeminiClient.DEFAULT_MODEL;
    private final MatchState matchState = new MatchState();

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile long cleanFrameRequestedAt;
    // Tied to the BEGINNING of capture, not the later OCR/network request.
    private volatile long activeCaptureGeneration;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlay;
    private LinearLayout controlsRow;
    private TextView coachText;
    private TextView transientCallText;
    private TextView detailText;
    private TextView expandButton;
    private boolean overlayExpanded;
    private String lastCoachText = "JARVIS bereit";
    private String lastDetailText = "";
    private TextToSpeech tts;

    private final Runnable hideTransientCall = new Runnable() {
        @Override public void run() {
            if (!overlayExpanded && transientCallText != null) {
                transientCallText.setVisibility(View.GONE);
            }
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification("Bildschirm-Coaching aktiv"));

        if (intent != null) {
            String providedProvider = intent.getStringExtra(EXTRA_PROVIDER);
            String providedKey = intent.getStringExtra(EXTRA_API_KEY);
            String providedModel = intent.getStringExtra(EXTRA_MODEL);

            if (ConfigStore.PROVIDER_OPENAI.equalsIgnoreCase(providedProvider)) {
                provider = ConfigStore.PROVIDER_OPENAI;
            } else if (ConfigStore.PROVIDER_GEMINI.equalsIgnoreCase(providedProvider)) {
                provider = ConfigStore.PROVIDER_GEMINI;
            } else {
                provider = ConfigStore.loadProvider(this);
            }

            String safeProvidedKey = CredentialValidator.normalizeApiKey(providedKey);
            if (!safeProvidedKey.isEmpty()) apiKey = safeProvidedKey;
            if (providedModel != null && !providedModel.trim().isEmpty()) model = providedModel.trim();
        } else {
            provider = ConfigStore.loadProvider(this);
        }

        if (ConfigStore.PROVIDER_GEMINI.equals(provider)) {
            if (apiKey.trim().isEmpty()) apiKey = ConfigStore.loadGeminiApiKey(this);
            if (model.trim().isEmpty() || model.startsWith("gpt-")) {
                model = ConfigStore.loadGeminiModel(this);
            }
        } else {
            if (apiKey.trim().isEmpty()) apiKey = ConfigStore.loadApiKey(this);
            if (model.trim().isEmpty() || model.startsWith("gemini-")) {
                model = ConfigStore.loadModel(this);
            }
        }

        smartAutoEnabled = ConfigStore.loadSmartAuto(this);
        speechMuted = ConfigStore.loadSpeechMuted(this);

        createOverlayIfAllowed();

        if (intent != null && mediaProjection == null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) {
                data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                //noinspection deprecation
                data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode == Activity.RESULT_OK && data != null) {
                startProjection(resultCode, data);
                setCoachText("JARVIS bereit");
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void requestAnalysis() {
        ScreenCaptureService s = instance;
        if (s != null) s.beginAnalysis(true);
    }

    private void beginAnalysis(boolean manual) {
        beginAnalysis(manual, false);
    }

    private void beginAnalysis(boolean manual, boolean queued) {
        long now = System.currentTimeMillis();
        // A manual tap made during another request is never lost to debounce.
        if (manual && analyzing.get()) {
            pendingManualRequest.enqueue();
            setCoachText("Manuelle Analyse vorgemerkt");
            return;
        }
        if (manual && !queued && now - lastAnalyzeTapAt < 1000L) return;
        if (!analyzing.compareAndSet(false, true)) {
            if (manual) pendingManualRequest.enqueue();
            return;
        }
        if (manual && !queued) lastAnalyzeTapAt = now;
        currentAnalysisManual = manual;
        activeCaptureGeneration = stateGeneration.get();

        long remainingMs = rateLimitedUntil - now;
        if (remainingMs > 0) {
            analyzing.set(false);
            long remainingSeconds = Math.max(1L, (remainingMs + 999L) / 1000L);
            if (remainingSeconds > 30L) {
                setCoachText((ConfigStore.PROVIDER_GEMINI.equals(provider) ? "Gemini" : "OpenAI")
                        + "-Limit aktiv – später erneut");
            } else {
                setCoachText("Rate-Limit – noch " + remainingSeconds + " s");
            }
            return;
        }

        apiKey = CredentialValidator.normalizeApiKey(apiKey);
        if (apiKey.isEmpty()) {
            analyzing.set(false);
            setCoachText(CredentialValidator.invalidKeyMessage(provider));
            return;
        }

        setCoachText("Analysiere …");
        awaitingCleanFrame.set(true);
        long captureId = captureSequence.incrementAndGet();
        cleanFrameRequestedAt = System.currentTimeMillis();

        mainHandler.post(() -> {
            if (overlay != null) overlay.setVisibility(View.INVISIBLE);
        });

        mainHandler.postDelayed(() -> {
            // Ignore the timeout from an earlier, already completed capture.
            if (captureSequence.get() != captureId) return;
            if (awaitingCleanFrame.compareAndSet(true, false)) {
                finishAnalysis();
                restoreOverlay();
                setCoachText("Kein aktuelles Spielbild verfügbar");
            }
        }, 2200L);
    }

    private void runAnalysis(String frame, String detailFrame, String detailLabel,
                             LocalHudPerception.Observation localHud) {
        final boolean manualRequest = currentAnalysisManual;
        final long expectedGeneration = activeCaptureGeneration;
        final long capturedAt = localHud == null ? 0L : localHud.observedAt;
        aiExecutor.execute(() -> {
            long cloudStart = System.nanoTime();
            try {
                if (expectedGeneration != stateGeneration.get()) return;
                evidenceLedger.recordLocal(localHud);
                String stateContext = matchState.toPromptContext() + evidenceLedger.toPromptContext();

                OpenAIClient.CoachResult result;
                cloudAttemptCount.incrementAndGet();
                if (ConfigStore.PROVIDER_GEMINI.equals(provider)) {
                    result = GeminiClient.coach(
                            apiKey, model, frame, detailFrame, detailLabel, stateContext);
                } else {
                    result = OpenAIClient.coach(
                            apiKey, model, frame, detailFrame, detailLabel, stateContext);
                }

                if (expectedGeneration != stateGeneration.get()) {
                    // RESET already cleared match memory; don't erase evidence from
                    // any newer observation or resurrect the old response.
                    smartFrameDetector.discardPendingAnalysis();
                    return;
                }
                if (FrameFreshnessGate.shouldDiscard(capturedAt, System.currentTimeMillis())) {
                    // Only discard the obsolete response. Previously established
                    // evidence belongs to the same match and must remain intact.
                    smartFrameDetector.discardPendingAnalysis();
                    setCoachText("Bildanalyse veraltet – warte auf aktuelles Spielbild");
                    return;
                }
                evidenceLedger.recordCloud(result, capturedAt);
                DecisionGate.GateResult gated = DecisionGate.validate(result, matchState, evidenceLedger);
                result = gated.result;
                if (result == null) throw new Exception("Entscheidung konnte nicht verifiziert werden");
                cloudAcceptedCount.incrementAndGet();
                String gateNotice = gated.blocked ? "GATE: " + gated.reason : null;

                String resultPhase = result.phase;
                boolean hadMatchMemory = matchState.hasMemory();
                boolean endScreen = "ERGEBNIS".equals(resultPhase)
                        || ("VOR_MATCH".equals(resultPhase)
                        && result.call != null
                        && result.call.toLowerCase(Locale.ROOT).contains("spiel beendet"));
                String localStage = localHud != null && localHud.stage != null
                        ? localHud.stage.value
                        : null;
                boolean clearlyNewMatch = result.newMatch
                        || isEarlyMatchStage(result.stage)
                        || isEarlyMatchStage(localStage);

                // Low-confidence flicker must not kick a stable pre-match screen back to UNKLAR.
                if ("UNKLAR".equals(resultPhase)
                        && "NIEDRIG".equals(result.confidence)
                        && "VOR_MATCH".equals(lastPhase)
                        && !matchState.hasMemory()
                        && !isEarlyMatchStage(localStage)) {
                    lastPhase = "VOR_MATCH";
                    smartFrameDetector.commitAnalyzed(System.currentTimeMillis());
                    publishCall("VOR_MATCH", "Warte auf Matchstart", "", "INFO", "MITTEL",
                            null, manualRequest);
                    return;
                }

                // Once an end screen has been confirmed, stale model context may not resurrect
                // the old fight. Only lobby/queue or clear stage-1 evidence can unlock it.
                if (matchEndedLock && !endScreen) {
                    if ("VOR_MATCH".equals(resultPhase) || clearlyNewMatch) {
                        matchEndedLock = false;
                    } else {
                        lastPhase = "ERGEBNIS";
                        smartFrameDetector.commitAnalyzed(System.currentTimeMillis());
                        publishCall("ERGEBNIS", "Spiel beendet", "Warte auf neue Spielsuche.",
                                "INFO", "HOCH", null, manualRequest);
                        return;
                    }
                }

                if (endScreen) {
                    matchEndedLock = true;
                    lastPhase = "ERGEBNIS";
                    if (hadMatchMemory) matchState.clear();
                    evidenceLedger.clear();
                    smartFrameDetector.commitAnalyzed(System.currentTimeMillis());
                    publishCall("ERGEBNIS", "Spiel beendet", "Ergebnis bestätigt; bereit für die nächste Partie.",
                            "INFO", "HOCH",
                            hadMatchMemory ? "MATCH: beendet – State zurückgesetzt" : "MATCH: beendet",
                            manualRequest);
                    return;
                }

                boolean resetByPreMatch = "VOR_MATCH".equals(resultPhase) && hadMatchMemory;
                boolean resetByNewMatch = result.newMatch;
                String resetNotice = null;

                if (resetByPreMatch || resetByNewMatch) {
                    matchState.clear();
                    evidenceLedger.clear();
                    resetNotice = resetByNewMatch
                            ? "MATCH: neuer Start"
                            : "MATCH: voriger State zurückgesetzt";
                }
                if (gateNotice != null) {
                    resetNotice = resetNotice == null ? gateNotice : resetNotice + "\n" + gateNotice;
                }

                lastPhase = resultPhase;
                if (!"VOR_MATCH".equals(resultPhase)) {
                    matchState.apply(result, localHud);
                }

                smartFrameDetector.commitAnalyzed(System.currentTimeMillis());
                publishCall(resultPhase, result.call, result.plan, result.priority, result.confidence,
                        resetNotice, manualRequest);
            } catch (GeminiClient.RateLimitException e) {
                cloudErrorCount.incrementAndGet();
                lastErrorType = "GeminiRateLimit";
                if (expectedGeneration != stateGeneration.get()) return;
                smartFrameDetector.discardPendingAnalysis();
                handleRateLimit(e.retryAfterSeconds, "Gemini");
            } catch (OpenAIClient.RateLimitException e) {
                cloudErrorCount.incrementAndGet();
                lastErrorType = "OpenAIRateLimit";
                if (expectedGeneration != stateGeneration.get()) return;
                smartFrameDetector.discardPendingAnalysis();
                handleRateLimit(e.retryAfterSeconds, "OpenAI");
            } catch (Exception e) {
                cloudErrorCount.incrementAndGet();
                lastErrorType = e.getClass().getSimpleName();
                // A late cloud error from a reset match must not replace the
                // visible status or rate limit state of the next match.
                if (expectedGeneration != stateGeneration.get()) return;
                smartFrameDetector.discardPendingAnalysis();
                String msg = e.getMessage() == null ? "Analysefehler" : e.getMessage();
                String lower = msg.toLowerCase(Locale.ROOT);
                boolean transientCloud = lower.contains("503")
                        || lower.contains("high demand")
                        || lower.contains("zu langsam")
                        || lower.contains("temporarily")
                        || lower.contains("unavailable")
                        || lower.contains("overloaded");
                boolean badHeader = lower.contains("unexpected char")
                        || lower.contains("header value")
                        || lower.contains("invalid header");
                boolean malformed = lower.contains("unterminated")
                        || lower.contains("end of input")
                        || lower.contains("unvollständig")
                        || lower.contains("malformed");
                if (badHeader) {
                    setCoachText(CredentialValidator.invalidKeyMessage(provider));
                } else if (malformed) {
                    setCoachText("KI-Antwort unvollständig – erneut beim nächsten stabilen Bild");
                } else if (transientCloud) {
                    // Do not immediately hammer the free endpoint again and do not imply
                    // that the strategic memory was lost. The next successful frame continues
                    // from the same MatchState.
                    rateLimitedUntil = Math.max(
                            rateLimitedUntil,
                            System.currentTimeMillis() + 20_000L
                    );
                    setCoachText("KI ausgelastet – letzter Plan nicht aktuell bestätigt");
                } else {
                    // Never display arbitrary HTTP/model error bodies or prompt fragments.
                    android.util.Log.w("TFTJarvis", "Analyse fehlgeschlagen: "
                            + e.getClass().getSimpleName());
                    setCoachText("Analyse fehlgeschlagen – erneut versuchen");
                }
            } finally {
                lastCloudMs = Math.max(0L, (System.nanoTime() - cloudStart) / 1_000_000L);
                finishAnalysis();
            }
        });
    }

    private void finishAnalysis() {
        analyzing.set(false);
        mainHandler.post(() -> {
            if (instance == this && pendingManualRequest.take()) {
                // Always request a NEW screen frame; don't replay an old upload.
                beginAnalysis(true, true);
            }
        });
    }

    private void handleRateLimit(int retryAfterSeconds, String source) {
        long seconds = Math.max(1L, retryAfterSeconds);
        long waitMs = Math.min(86_400_000L, seconds * 1000L);
        rateLimitedUntil = System.currentTimeMillis() + waitMs;

        if (seconds > 30L) {
            setCoachText(source + "-Limit aktiv – später erneut");
        } else {
            setCoachText(source + "-Limit – " + seconds + " s Pause");
        }
    }

    private void publishCall(String phase, String call, String plan, String priority,
                             String confidence, String resetNotice, boolean manualRequest) {
        mainHandler.post(() -> {
            String badge = "KRITISCH".equals(priority) ? "!" : ("WICHTIG".equals(priority) ? "*" : "i");
            String shortCall = "[" + badge + "][" + phase + "] " + call;
            StringBuilder detail = new StringBuilder(shortCall);
            detail.append("\nSICHERHEIT: ").append(confidence);
            detail.append("\n").append(matchState.debugSummary());
            detail.append("\n").append(evidenceLedger.debugSummary());
            if (matchState.primaryTrait != null && !matchState.primaryTrait.trim().isEmpty()) {
                detail.append("\nKERN: ").append(matchState.primaryTrait.trim());
                if (matchState.isStrategyEstablished()) detail.append(" ✓");
                else detail.append(" (Tendenz)");
            }
            if (matchState.compPlan != null && !matchState.compPlan.trim().isEmpty()) {
                detail.append("\nCOMP: ").append(matchState.compPlan.trim());
            }
            if (plan != null && !plan.trim().isEmpty()) {
                detail.append("\nNÄCHSTER SCHRITT: ").append(plan.trim());
            }
            if (resetNotice != null && !resetNotice.trim().isEmpty()) {
                detail.append("\n").append(resetNotice.trim());
            }

            lastCoachText = shortCall;
            lastDetailText = detail.toString();
            updateHeaderStatus();
            showTransientCall(shortCall);
            if (detailText != null) {
                detailText.setText(lastDetailText);
                if (overlayExpanded) detailText.setVisibility(View.VISIBLE);
            }

            if (tts != null && shouldSpeak(phase, call, priority, confidence, manualRequest)) {
                tts.speak(call, TextToSpeech.QUEUE_FLUSH, null, "tft-call");
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification(call));
        });
    }

    private static boolean isEarlyMatchStage(String stage) {
        return stage != null && stage.matches("1-\\d+");
    }

    private boolean shouldSpeak(String phase, String call, String priority,
                                String confidence, boolean manualRequest) {
        if (speechMuted || call == null || call.trim().isEmpty()) return false;
        String normalizedCall = call.toLowerCase(Locale.GERMAN)
                .replaceAll("[^a-z0-9äöüß ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalizedCall.isEmpty()
                || normalizedCall.contains("nicht sicher erkennbar")
                || normalizedCall.contains("warte auf planungsphase")
                || "VOR_MATCH".equals(phase)
                || "ERGEBNIS".equals(phase)
                || "INFO".equals(priority)
                || "NIEDRIG".equals(confidence)) {
            return false;
        }

        boolean eligible;
        if (manualRequest) {
            eligible = ("KRITISCH".equals(priority) || "WICHTIG".equals(priority))
                    && ("HOCH".equals(confidence) || "MITTEL".equals(confidence));
        } else {
            eligible = "KRITISCH".equals(priority) && "HOCH".equals(confidence);
        }
        if (!eligible) return false;

        long now = System.currentTimeMillis();
        String key = phase + "|" + normalizedCall;
        if (key.equals(lastSpokenKey) && now - lastSpokenAt < 30_000L) return false;

        lastSpokenKey = key;
        lastSpokenAt = now;
        return true;
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(getMainLooper()));

        int width;
        int height;
        int density = getResources().getDisplayMetrics().densityDpi;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            width = dm.widthPixels;
            height = dm.heightPixels;
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        captureThread = new HandlerThread("tft-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "TFTJarvisCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            capturedFrameCount.incrementAndGet();

            long now = System.currentTimeMillis();

            if (!awaitingCleanFrame.get()) {
                if (smartAutoEnabled
                        && !analyzing.get()
                        && now >= rateLimitedUntil
                        && smartFrameDetector.observe(image, now, lastPhase)) {
                    smartFrameDetector.markCloudAttempt(now);
                    mainHandler.post(() -> beginAnalysis(false));
                }
                return;
            }

            if (now - cleanFrameRequestedAt < 180L) return;

            smartFrameDetector.markAnalyzed(image, now);

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888
            );
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            long ocrStart = System.nanoTime();
            LocalHudPerception.Observation localHud = localHudPerception.observe(cropped, lastPhase);
            lastOcrMs = Math.max(0L, (System.nanoTime() - ocrStart) / 1_000_000L);
            Bitmap upload = downscale(cropped, 1280);
            String cleanFrame = encodeJpeg(upload, 72);

            String detailFrame = null;
            String detailLabel = null;
            TftRoiSelector.Selection selection = TftRoiSelector.select(cropped, lastPhase);
            if (selection != null) {
                Bitmap detailUpload = downscale(selection.bitmap, 1024);
                detailFrame = encodeJpeg(detailUpload, 80);
                detailLabel = selection.label;
                if (detailUpload != selection.bitmap) detailUpload.recycle();
                selection.bitmap.recycle();
            }

            if (upload != cropped) upload.recycle();
            if (cropped != padded) cropped.recycle();
            padded.recycle();

            if (awaitingCleanFrame.compareAndSet(true, false)) {
                restoreOverlay();
                runAnalysis(cleanFrame, detailFrame, detailLabel, localHud);
            }
        } catch (Throwable e) {
            if (awaitingCleanFrame.compareAndSet(true, false)) {
                // An interrupted image decode must not become the last analyzed
                // baseline; allow Smart-Auto to retry on a new clean frame.
                smartFrameDetector.discardPendingAnalysis();
                finishAnalysis();
                restoreOverlay();
                setCoachText("Bild konnte nicht gelesen werden");
            }
        } finally {
            if (image != null) image.close();
        }
    }

    private String encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap downscale(Bitmap source, int maxSide) {
        int w = source.getWidth();
        int h = source.getHeight();
        int largest = Math.max(w, h);
        if (largest <= maxSide) return source;
        float scale = (float) maxSide / largest;
        return Bitmap.createScaledBitmap(source, Math.round(w * scale), Math.round(h * scale), true);
    }

    private void createOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(6), dp(3), dp(6), dp(3));
        overlay.setBackground(makeRoundedBackground(0xD9111318, 16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        coachText = new TextView(this);
        coachText.setText("JARVIS ✓");
        coachText.setTextColor(0xFFF4F4F4);
        coachText.setTextSize(12f);
        coachText.setSingleLine(true);
        coachText.setPadding(dp(6), dp(2), dp(6), dp(2));
        attachDragBehavior(coachText);
        header.addView(coachText);


        expandButton = makeChip("▾");
        expandButton.setTextSize(10f);
        expandButton.setPadding(dp(7), dp(3), dp(7), dp(3));
        expandButton.setContentDescription("JARVIS aufklappen");
        expandButton.setOnClickListener(v -> toggleOverlayExpanded());
        header.addView(expandButton);

        overlay.addView(header);

        transientCallText = new TextView(this);
        transientCallText.setTextColor(0xFFF4F4F4);
        transientCallText.setTextSize(11.5f);
        transientCallText.setMaxLines(2);
        transientCallText.setEllipsize(TextUtils.TruncateAt.END);
        transientCallText.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.52f));
        transientCallText.setPadding(dp(7), dp(4), dp(7), dp(4));
        transientCallText.setBackground(makeRoundedBackground(0xE0202329, 10));
        LinearLayout.LayoutParams transientLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        transientLp.setMargins(0, dp(3), 0, 0);
        transientCallText.setLayoutParams(transientLp);
        transientCallText.setVisibility(View.GONE);
        overlay.addView(transientCallText);

        detailText = new TextView(this);
        detailText.setTextColor(0xFFF4F4F4);
        detailText.setTextSize(11.5f);
        detailText.setMaxLines(9);
        detailText.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.62f));
        detailText.setPadding(dp(7), dp(4), dp(7), dp(2));
        detailText.setVisibility(View.GONE);
        overlay.addView(detailText);

        controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(0, dp(3), 0, 0);
        controlsRow.setVisibility(View.GONE);

        TextView analyzeButton = makeChip("ANALYSE");
        analyzeButton.setTextSize(10f);
        analyzeButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        analyzeButton.setOnClickListener(v -> beginAnalysis(true));
        controlsRow.addView(analyzeButton);

        TextView moreButton = makeChip("⋮");
        moreButton.setTextSize(14f);
        moreButton.setPadding(dp(8), dp(2), dp(8), dp(2));
        moreButton.setContentDescription("Mehr");
        moreButton.setOnClickListener(this::showMoreMenu);
        controlsRow.addView(moreButton);

        overlay.addView(controlsRow);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(10);
        overlayParams.y = dp(10);
        windowManager.addView(overlay, overlayParams);
        updateHeaderStatus();
    }

    private TextView makeChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(0xFFF1F1F1);
        chip.setTextSize(11f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(makeRoundedBackground(0xB82A2D33, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(dp(4), 0, 0, 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private GradientDrawable makeRoundedBackground(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private void toggleOverlayExpanded() {
        overlayExpanded = !overlayExpanded;
        mainHandler.removeCallbacks(hideTransientCall);

        if (controlsRow != null) controlsRow.setVisibility(overlayExpanded ? View.VISIBLE : View.GONE);
        if (detailText != null) {
            detailText.setText(lastDetailText);
            detailText.setVisibility(overlayExpanded && !lastDetailText.trim().isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (transientCallText != null) transientCallText.setVisibility(View.GONE);
        if (expandButton != null) {
            expandButton.setText(overlayExpanded ? "▴" : "▾");
            expandButton.setContentDescription(overlayExpanded ? "JARVIS einklappen" : "JARVIS aufklappen");
        }
        updateHeaderStatus();

        if (overlay != null && windowManager != null && overlayParams != null) {
            windowManager.updateViewLayout(overlay, overlayParams);
        }
    }

    private void showTransientCall(String text) {
        if (transientCallText == null || overlayExpanded) return;
        transientCallText.setText(text);
        transientCallText.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideTransientCall);
        mainHandler.postDelayed(hideTransientCall, 5000L);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(smartAutoEnabled
                ? "Intelligente Automatik ausschalten"
                : "Intelligente Automatik einschalten");
        menu.getMenu().add(speechMuted ? "Sprache einschalten" : "Stumm schalten");
        menu.getMenu().add("Status anzeigen");
        menu.getMenu().add("Neue Partie / Gedächtnis zurücksetzen");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.startsWith("Intelligente Automatik")) {
                smartAutoEnabled = !smartAutoEnabled;
                ConfigStore.saveSmartAuto(this, smartAutoEnabled);
                smartFrameDetector.reset();
                setCoachText(smartAutoEnabled ? "Smart-Auto aktiv" : "Smart-Auto aus");
                updateHeaderStatus();
                return true;
            }
            if ("Stumm schalten".equals(title) || "Sprache einschalten".equals(title)) {
                speechMuted = !speechMuted;
                ConfigStore.saveSpeechMuted(this, speechMuted);
                if (speechMuted && tts != null) tts.stop();
                setCoachText(speechMuted ? "Sprachausgabe stumm" : "Sprachausgabe aktiv");
                updateHeaderStatus();
                return true;
            }
            if ("Status anzeigen".equals(title)) {
                String source = ConfigStore.PROVIDER_GEMINI.equals(provider) ? "Gemini" : "OpenAI";
                setCoachText("Status: " + source + " | Auto: "
                        + (smartAutoEnabled ? "AN" : "AUS")
                        + " | Sprache: " + (speechMuted ? "STUMM" : "AN")
                        + " | Phase: " + lastPhase
                        + " | " + evidenceLedger.debugSummary()
                        + " | Frames: " + capturedFrameCount.get()
                        + " | Cloud: " + cloudAcceptedCount.get() + "/"
                        + cloudAttemptCount.get()
                        + " | Fehler: " + cloudErrorCount.get() + " (" + lastErrorType + ")"
                        + " | OCR: " + lastOcrMs + "ms"
                        + " | KI: " + lastCloudMs + "ms");
                return true;
            }
            if ("Neue Partie / Gedächtnis zurücksetzen".equals(title)) {
                stateGeneration.incrementAndGet();
                pendingManualRequest.clear();
                matchState.clear();
                evidenceLedger.clear();
                lastPhase = "UNKLAR";
                matchEndedLock = false;
                lastDetailText = "";
                lastSpokenKey = "";
                lastSpokenAt = 0L;
                smartFrameDetector.reset();
                if (detailText != null) detailText.setVisibility(View.GONE);
                setCoachText("Neue Partie – Gedächtnis zurückgesetzt");
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void updateHeaderStatus() {
        if (coachText != null) {
            String status;
            if (analyzing.get()) {
                status = "JARVIS …";
            } else {
                String source = ConfigStore.PROVIDER_GEMINI.equals(provider) ? "G" : "O";
                status = smartAutoEnabled
                        ? "JARVIS " + source + " ◉"
                        : "JARVIS " + source + " ✓";
            }
            coachText.setText(speechMuted ? status + " | STUMM" : status);
        }
    }

    private void attachDragBehavior(View dragHandle) {
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayParams == null || windowManager == null || overlay == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = overlayParams.x;
                        startY = overlayParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downRawX);
                        int dy = Math.round(event.getRawY() - downRawY);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - overlay.getWidth());
                        int maxY = Math.max(0, getResources().getDisplayMetrics().heightPixels - overlay.getHeight());
                        overlayParams.x = Math.max(0, Math.min(startX + dx, maxX));
                        overlayParams.y = Math.max(0, Math.min(startY + dy, maxY));
                        windowManager.updateViewLayout(overlay, overlayParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggleOverlayExpanded();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void restoreOverlay() {
        mainHandler.post(() -> {
            if (overlay != null) overlay.setVisibility(View.VISIBLE);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setCoachText(String text) {
        mainHandler.post(() -> {
            lastCoachText = text;
            if (overlay == null) createOverlayIfAllowed();
            updateHeaderStatus();

            if ("JARVIS bereit".equals(text)) {
                return;
            }
            if ("Analysiere …".equals(text)) {
                return;
            }

            lastDetailText = text;
            if (overlayExpanded) {
                if (detailText != null) {
                    detailText.setText(text);
                    detailText.setVisibility(View.VISIBLE);
                }
            } else {
                showTransientCall(text);
            }
        });
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TFT Jarvis Coach")
                .setContentText(text)
                .setContentIntent(pi)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.GERMAN);
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "TFT Bildschirm-Coaching",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        stateGeneration.incrementAndGet();
        pendingManualRequest.clear();
        captureSequence.incrementAndGet();
        awaitingCleanFrame.set(false);
        mainHandler.removeCallbacks(hideTransientCall);
        instance = null;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        }
        if (imageReader != null) imageReader.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (captureThread != null) captureThread.quitSafely();
        if (tts != null) tts.shutdown();
        localHudPerception.close();
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
