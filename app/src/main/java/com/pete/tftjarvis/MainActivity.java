package com.pete.tftjarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFICATIONS = 4102;

    private TextView statusText;
    private EditText apiKeyInput;
    private EditText modelInput;
    private boolean startAfterOverlayPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        modelInput = findViewById(R.id.modelInput);
        Button startButton = findViewById(R.id.startButton);
        Button rememberButton = findViewById(R.id.rememberButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button forgetKeyButton = findViewById(R.id.forgetKeyButton);

        String savedKey = ConfigStore.loadApiKey(this);
        if (!savedKey.trim().isEmpty()) apiKeyInput.setText(savedKey);
        modelInput.setText(ConfigStore.loadModel(this));

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }

        startButton.setOnClickListener(v -> startCoachingFlow());
        rememberButton.setOnClickListener(v -> {
            if (!ScreenCaptureService.isRunning()) {
                status("Coaching zuerst starten");
            } else {
                ScreenCaptureService.requestAnalysis();
                status("Analyse ausgelöst");
            }
        });
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status("Coaching gestoppt");
        });
        forgetKeyButton.setOnClickListener(v -> {
            apiKeyInput.setText("");
            ConfigStore.save(this, "", modelInput.getText().toString().trim());
            status("Eingabefeld geleert");
        });
    }

    private void startCoachingFlow() {
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
            status("OpenAI API-Key fehlt");
            return;
        }
        String model = modelInput.getText().toString().trim();
        if (model.isEmpty()) model = "gpt-6-luna";
        ConfigStore.save(this, apiKey, model);

        if (!Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = true;
            status("Bitte Overlay erlauben");
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        requestScreenCapture();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startAfterOverlayPermission && Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = false;
            requestScreenCapture();
        }
    }


    private void requestScreenCapture() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        status("Bildschirmfreigabe bestätigen");
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            service.putExtra(ScreenCaptureService.EXTRA_API_KEY, apiKeyInput.getText().toString().trim());
            service.putExtra(ScreenCaptureService.EXTRA_MODEL, modelInput.getText().toString().trim());
            startForegroundService(service);
            status("Aktiv – Smart-Auto läuft; ANALYSE bleibt verfügbar");
        } else {
            status("Bildschirmfreigabe nicht erteilt");
        }
    }

    private void status(String text) {
        statusText.setText("Status: " + text);
    }
}
