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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFICATIONS = 4102;

    private TextView statusText;
    private EditText apiKeyInput;
    private EditText modelInput;
    private Spinner providerInput;
    private boolean startAfterOverlayPermission;
    private boolean providerUiReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        modelInput = findViewById(R.id.modelInput);
        providerInput = findViewById(R.id.providerInput);

        Button startButton = findViewById(R.id.startButton);
        Button rememberButton = findViewById(R.id.rememberButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button forgetKeyButton = findViewById(R.id.forgetKeyButton);

        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"Gemini Free", "OpenAI optional"}
        );
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerInput.setAdapter(providerAdapter);

        String savedProvider = ConfigStore.loadProvider(this);
        providerInput.setSelection(
                ConfigStore.PROVIDER_OPENAI.equals(savedProvider) ? 1 : 0,
                false
        );
        loadProviderFields(savedProvider);
        providerUiReady = true;

        providerInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
            ) {
                if (!providerUiReady) return;
                String provider = position == 1
                        ? ConfigStore.PROVIDER_OPENAI
                        : ConfigStore.PROVIDER_GEMINI;
                ConfigStore.saveProvider(MainActivity.this, provider);
                loadProviderFields(provider);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
        }

        startButton.setOnClickListener(v -> startCoachingFlow());

        rememberButton.setOnClickListener(v -> {
            if (!ScreenCaptureService.isRunning()) {
                status("Coaching zuerst starten");
            } else {
                ScreenCaptureService.requestAnalysis();
                status("Analyse angefordert (ggf. vorgemerkt)");
            }
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status("Coaching gestoppt");
        });

        forgetKeyButton.setOnClickListener(v -> {
            String provider = selectedProvider();
            apiKeyInput.setText("");
            if (ConfigStore.PROVIDER_GEMINI.equals(provider)) {
                ConfigStore.saveGemini(
                        this,
                        "",
                        modelInput.getText().toString().trim()
                );
            } else {
                ConfigStore.save(
                        this,
                        "",
                        modelInput.getText().toString().trim()
                );
            }
            status("API-Key gelöscht");
        });
    }

    private void loadProviderFields(String provider) {
        if (ConfigStore.PROVIDER_OPENAI.equals(provider)) {
            apiKeyInput.setHint("OpenAI API-Key");
            apiKeyInput.setText(ConfigStore.loadApiKey(this));
            modelInput.setHint("OpenAI Modell");
            modelInput.setText(ConfigStore.loadModel(this));
        } else {
            apiKeyInput.setHint("Gemini API-Key");
            apiKeyInput.setText(ConfigStore.loadGeminiApiKey(this));
            modelInput.setHint("Gemini Modell");
            modelInput.setText(ConfigStore.loadGeminiModel(this));
        }
    }

    private String selectedProvider() {
        return providerInput.getSelectedItemPosition() == 1
                ? ConfigStore.PROVIDER_OPENAI
                : ConfigStore.PROVIDER_GEMINI;
    }

    private void startCoachingFlow() {
        String provider = selectedProvider();
        String rawApiKey = apiKeyInput.getText().toString();
        String apiKey = CredentialValidator.normalizeApiKey(rawApiKey);

        if (apiKey.isEmpty()) {
            status(rawApiKey == null || rawApiKey.trim().isEmpty()
                    ? (ConfigStore.PROVIDER_GEMINI.equals(provider)
                        ? "Gemini API-Key fehlt"
                        : "OpenAI API-Key fehlt")
                    : CredentialValidator.invalidKeyMessage(provider));
            return;
        }

        String fallbackModel = ConfigStore.PROVIDER_GEMINI.equals(provider)
                ? GeminiClient.DEFAULT_MODEL
                : "gpt-6-luna";
        String model = CredentialValidator.normalizeModel(
                modelInput.getText().toString(),
                fallbackModel
        );
        modelInput.setText(model);

        ConfigStore.saveProvider(this, provider);
        if (ConfigStore.PROVIDER_GEMINI.equals(provider)) {
            ConfigStore.saveGemini(this, apiKey, model);
        } else {
            ConfigStore.save(this, apiKey, model);
        }

        if (!Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = true;
            status("Bitte Overlay erlauben");
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
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
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        status("Bildschirmfreigabe bestätigen");
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode == RESULT_OK && data != null) {
            String provider = selectedProvider();

            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            service.putExtra(ScreenCaptureService.EXTRA_PROVIDER, provider);
            String safeKey = CredentialValidator.normalizeApiKey(apiKeyInput.getText().toString());
            String safeModel = CredentialValidator.normalizeModel(
                    modelInput.getText().toString(),
                    ConfigStore.PROVIDER_GEMINI.equals(provider)
                            ? GeminiClient.DEFAULT_MODEL
                            : "gpt-6-luna"
            );
            if (safeKey.isEmpty()) {
                status(CredentialValidator.invalidKeyMessage(provider));
                return;
            }
            service.putExtra(ScreenCaptureService.EXTRA_API_KEY, safeKey);
            service.putExtra(ScreenCaptureService.EXTRA_MODEL, safeModel);

            startForegroundService(service);
            status(
                    ConfigStore.PROVIDER_GEMINI.equals(provider)
                            ? "Aktiv – Gemini + Smart-Auto"
                            : "Aktiv – OpenAI + Smart-Auto"
            );
        } else {
            status("Bildschirmfreigabe nicht erteilt");
        }
    }

    private void status(String text) {
        statusText.setText("Status: " + text);
    }
}
