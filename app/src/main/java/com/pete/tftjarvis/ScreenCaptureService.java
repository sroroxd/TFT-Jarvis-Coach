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

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_API_KEY = "apiKey";
    public static final String EXTRA_MODEL = "model";

    private static final String CHANNEL_ID = "tft_capture";
    private static final int NOTIFICATION_ID = 17;
    private static volatile ScreenCaptureService instance;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final AtomicBoolean awaitingCleanFrame = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler();
    private final SmartFrameDetector smartFrameDetector = new SmartFrameDetector();
    private volatile long rateLimitedUntil = 0L;
    private volatile long lastAnalyzeTapAt = 0L;
    private volatile boolean smartAutoEnabled = true;
    private volatile String lastPhase = "UNKLAR";

    private String apiKey = "";
    private String model = "gpt-6-luna";
    private String previousState = "";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile long cleanFrameRequestedAt;

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
            String providedKey = intent.getStringExtra(EXTRA_API_KEY);
            String providedModel = intent.getStringExtra(EXTRA_MODEL);
            if (providedKey != null && !providedKey.trim().isEmpty()) apiKey = providedKey;
            if (providedModel != null && !providedModel.trim().isEmpty()) model = providedModel;
        }
        if (apiKey.trim().isEmpty()) apiKey = ConfigStore.loadApiKey(this);
        if (model.trim().isEmpty()) model = ConfigStore.loadModel(this);
        smartAutoEnabled = ConfigStore.loadSmartAuto(this);

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
        long now = System.currentTimeMillis();
        if (manual) {
            if (now - lastAnalyzeTapAt < 1000L) return;
            lastAnalyzeTapAt = now;
        }

        if (!analyzing.compareAndSet(false, true)) return;

        long remainingMs = rateLimitedUntil - now;
        if (remainingMs > 0) {
            analyzing.set(false);
            long remainingSeconds = Math.max(1L, (remainingMs + 999L) / 1000L);
            if (remainingSeconds > 30L) {
                setCoachText("Cloud pausiert – später erneut");
            } else {
                setCoachText("Rate-Limit – noch " + remainingSeconds + " s");
            }
            return;
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            analyzing.set(false);
            setCoachText("API-Key fehlt");
            return;
        }

        setCoachText("Analysiere …");
        awaitingCleanFrame.set(true);
        cleanFrameRequestedAt = System.currentTimeMillis();

        mainHandler.post(() -> {
            if (overlay != null) overlay.setVisibility(View.INVISIBLE);
        });

        mainHandler.postDelayed(() -> {
            if (awaitingCleanFrame.compareAndSet(true, false)) {
                analyzing.set(false);
                restoreOverlay();
                setCoachText("Kein aktuelles Bild");
            }
        }, 2200L);
    }

    private void runAnalysis(String frame) {
        aiExecutor.execute(() -> {
            try {
                OpenAIClient.CoachResult result = OpenAIClient.coach(apiKey, model, frame, previousState);
                lastPhase = result.phase;

                boolean hadMatchMemory = previousState != null && !previousState.trim().isEmpty();
                boolean resetByPreMatch = "VOR_MATCH".equals(result.phase);
                boolean resetByNewMatch = result.newMatch;
                boolean showResetNotice = resetByNewMatch || (resetByPreMatch && hadMatchMemory);

                if (resetByPreMatch || resetByNewMatch) {
                    previousState = "";
                }

                if (!resetByPreMatch) {
                    StringBuilder memory = new StringBuilder();
                    if (result.state != null && !result.state.trim().isEmpty()) memory.append(result.state.trim());
                    if (result.plan != null && !result.plan.trim().isEmpty()) {
                        if (memory.length() > 0) memory.append(" | ");
                        memory.append("Letzter Plan: ").append(result.plan.trim());
                    }
                    if (memory.length() > 0) previousState = memory.toString();
                }

                publishCall(result.phase, result.call, result.plan, showResetNotice);
            } catch (OpenAIClient.RateLimitException e) {
                long serverWaitMs = Math.min(86_400_000L, Math.max(1L, e.retryAfterSeconds) * 1000L);
                rateLimitedUntil = System.currentTimeMillis() + serverWaitMs;
                if (e.retryAfterSeconds > 30) {
                    // Respect the server's retry window; do not turn a long limit into a new request after 60 seconds.
                    setCoachText("Cloud pausiert – später erneut");
                } else {
                    setCoachText("Rate-Limit – " + e.retryAfterSeconds + " s Pause");
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Analysefehler" : e.getMessage();
                if (msg.length() > 95) msg = msg.substring(0, 95) + "…";
                setCoachText(msg);
            } finally {
                analyzing.set(false);
            }
        });
    }

    private void publishCall(String phase, String call, String plan, boolean showResetNotice) {
        mainHandler.post(() -> {
            String shortCall = "[" + phase + "] " + call;
            StringBuilder detail = new StringBuilder(shortCall);
            if (plan != null && !plan.trim().isEmpty()) {
                detail.append("\nPLAN: ").append(plan.trim());
            }
            if (showResetNotice) {
                detail.append("\nMATCH: neuer Kontext");
            }

            lastCoachText = shortCall;
            lastDetailText = detail.toString();
            updateHeaderStatus();
            showTransientCall(shortCall);
            if (detailText != null) {
                detailText.setText(lastDetailText);
                if (overlayExpanded) detailText.setVisibility(View.VISIBLE);
            }

            if (tts != null) tts.speak(call, TextToSpeech.QUEUE_FLUSH, null, "tft-call");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification(call));
        });
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
            Bitmap upload = downscale(cropped, 1280);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            upload.compress(Bitmap.CompressFormat.JPEG, 72, out);
            String cleanFrame = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);

            if (upload != cropped) upload.recycle();
            if (cropped != padded) cropped.recycle();
            padded.recycle();

            if (awaitingCleanFrame.compareAndSet(true, false)) {
                restoreOverlay();
                runAnalysis(cleanFrame);
            }
        } catch (Throwable e) {
            if (awaitingCleanFrame.compareAndSet(true, false)) {
                analyzing.set(false);
                restoreOverlay();
                setCoachText("Bild konnte nicht gelesen werden");
            }
        } finally {
            if (image != null) image.close();
        }
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
        detailText.setMaxLines(5);
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
        menu.getMenu().add("Gedächtnis zurücksetzen");
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
            if ("Gedächtnis zurücksetzen".equals(title)) {
                previousState = "";
                lastPhase = "UNKLAR";
                lastDetailText = "";
                smartFrameDetector.reset();
                if (detailText != null) detailText.setVisibility(View.GONE);
                setCoachText("Gedächtnis zurückgesetzt");
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
                status = smartAutoEnabled ? "JARVIS ◉" : "JARVIS ✓";
            }
            coachText.setText(status);
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
        aiExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
