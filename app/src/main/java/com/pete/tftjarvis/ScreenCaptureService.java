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
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
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
    public static final String EXTRA_INTERVAL_SECONDS = "intervalSeconds";

    private static final String CHANNEL_ID = "tft_capture";
    private static final int NOTIFICATION_ID = 17;
    private static volatile ScreenCaptureService instance;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler();

    private volatile String latestFrameBase64;
    private volatile long latestFrameTime;
    private volatile long generation;
    private volatile boolean destroyed;
    private String apiKey = "";
    private String model = "gpt-6-luna";
    private String previousState = "";
    private int intervalSeconds = 7;
    private boolean autoEnabled;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private long lastEncodedAt;

    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView coachText;
    private Button autoButton;
    private TextToSpeech tts;

    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!autoEnabled) return;
            analyzeCurrentFrame();
            mainHandler.postDelayed(this, intervalSeconds * 1000L);
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
            intervalSeconds = Math.max(4, intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 7));
        }
        if (apiKey.trim().isEmpty()) apiKey = ConfigStore.loadApiKey(this);
        if (model.trim().isEmpty()) model = ConfigStore.loadModel(this);

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
        if (s != null) s.analyzeCurrentFrame();
    }

    private void analyzeCurrentFrame() {
        if (!analyzing.compareAndSet(false, true)) return;
        final String frame = latestFrameBase64;
        final long frameTime = latestFrameTime;
        final long requestGeneration = generation;
        if (frame == null || frame.trim().isEmpty() || SystemClock.elapsedRealtime() - frameTime > 4000L) {
            analyzing.set(false);
            setCoachText("Noch kein Bild");
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            analyzing.set(false);
            setCoachText("API-Key fehlt");
            return;
        }

        setCoachText("Analysiere …");
        aiExecutor.execute(() -> {
            try {
                OpenAIClient.CoachResult result = OpenAIClient.coach(apiKey, model, frame, previousState);
                if (!destroyed && requestGeneration == generation && SystemClock.elapsedRealtime() - frameTime <= 10000L) {
                    if (result.state != null && !result.state.trim().isEmpty()) previousState = result.state;
                    publishCall(result.call, requestGeneration);
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

    private void publishCall(String call, long requestGeneration) {
        mainHandler.post(() -> {
            if (destroyed || requestGeneration != generation) return;
            setCoachText(call);
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
            if (now - lastEncodedAt < 1000L) return;
            lastEncodedAt = now;

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
            Bitmap upload = downscale(cropped, 1920);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            upload.compress(Bitmap.CompressFormat.JPEG, 78, out);
            latestFrameBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            latestFrameTime = SystemClock.elapsedRealtime();

            if (upload != cropped) upload.recycle();
            if (cropped != padded) cropped.recycle();
            padded.recycle();
        } catch (Throwable ignored) {
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
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(12, 8, 12, 8);
        overlay.setBackgroundColor(0xE6000000);

        coachText = new TextView(this);
        coachText.setText("JARVIS bereit");
        coachText.setTextColor(0xFFFFFFFF);
        coachText.setTextSize(16f);
        coachText.setPadding(8, 0, 14, 0);
        overlay.addView(coachText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button rememberButton = new Button(this);
        rememberButton.setText("MERKEN");
        rememberButton.setAllCaps(false);
        rememberButton.setOnClickListener(v -> analyzeCurrentFrame());
        overlay.addView(rememberButton);

        autoButton = new Button(this);
        autoButton.setText("AUTO AUS");
        autoButton.setAllCaps(false);
        autoButton.setOnClickListener(v -> toggleAuto());
        overlay.addView(autoButton);

        Button resetButton = new Button(this);
        resetButton.setText("RESET");
        resetButton.setAllCaps(false);
        resetButton.setOnClickListener(v -> {
            generation++;
            previousState = "";
            setCoachText("Gedächtnis zurückgesetzt");
        });
        overlay.addView(resetButton);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = 28;
        windowManager.addView(overlay, p);
    }

    private void toggleAuto() {
        autoEnabled = !autoEnabled;
        mainHandler.removeCallbacks(autoTick);
        if (autoButton != null) autoButton.setText(autoEnabled ? "AUTO " + intervalSeconds + "s" : "AUTO AUS");
        if (autoEnabled) {
            analyzeCurrentFrame();
            mainHandler.postDelayed(autoTick, intervalSeconds * 1000L);
        }
    }

    private void setCoachText(String text) {
        mainHandler.post(() -> {
            if (overlay == null) createOverlayIfAllowed();
            if (coachText != null) coachText.setText(text);
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
        destroyed = true;
        generation++;
        autoEnabled = false;
        mainHandler.removeCallbacks(autoTick);
        instance = null;
        latestFrameBase64 = null;
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
