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
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFICATIONS = 4102;

    private TextView statusText;
    private EditText apiKeyInput;
    private EditText modelInput;
    private Spinner providerInput;
    private Spinner ttsEngineSpinner;
    private Spinner ttsVoiceSpinner;
    private boolean startAfterOverlayPermission;
    private boolean providerUiReady;
    private boolean ttsUiReady;
    private SpeechController speechController;

    private final List<TextToSpeech.EngineInfo> availableEngines = new ArrayList<>();
    private final List<Voice> availableVoices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        modelInput = findViewById(R.id.modelInput);
        providerInput = findViewById(R.id.providerInput);
        ttsEngineSpinner = findViewById(R.id.ttsEngineSpinner);
        ttsVoiceSpinner = findViewById(R.id.ttsVoiceSpinner);

        createSpeechController();

        Button startButton = findViewById(R.id.startButton);
        Button rememberButton = findViewById(R.id.rememberButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button forgetKeyButton = findViewById(R.id.forgetKeyButton);
        Button speechTestButton = findViewById(R.id.speechTestButton);
        Button ttsSettingsButton = findViewById(R.id.ttsSettingsButton);
        Button speechResetButton = findViewById(R.id.speechResetButton);

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
                status("Analyse angefordert (falls beschäftigt: einmal vorgemerkt)");
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

        speechTestButton.setOnClickListener(v -> {
            SpeechController.SpeechResult result = speechController.speakTestPhrase("JARVIS bereit. Artefakt erkannt.", false);
            status("Testansage: " + result.reason);
        });

        ttsSettingsButton.setOnClickListener(v -> {
            try {
                Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                } catch (Exception ex) {
                    status("System-TTS-Einstellungen konnten nicht geöffnet werden");
                }
            }
        });

        speechResetButton.setOnClickListener(v -> {
            speechController.resetAudioParameters();
            ScreenCaptureService.reloadSpeechSettings();
            setupTtsSpinners();
            status("Spracheinstellungen zurückgesetzt");
        });

        setupTtsSpinners();
    }

    private void createSpeechController() {
        if (speechController != null) speechController.shutdown();
        speechController = new SpeechController(this);
        SpeechController created = speechController;
        created.setOnInitCallback(state -> ttsEngineSpinner.post(() -> {
            if (!isDestroyed() && speechController == created) setupTtsSpinners();
        }));
    }

    private void setupTtsSpinners() {
        if (speechController == null || isDestroyed()) return;
        ttsUiReady = false;
        availableEngines.clear();
        availableEngines.addAll(speechController.getAvailableEngines());

        List<String> engineNames = new ArrayList<>();
        engineNames.add("System-Standard Engine");
        String savedEngine = ConfigStore.loadTtsEnginePackage(this);
        int selectedEngineIdx = 0;

        for (int i = 0; i < availableEngines.size(); i++) {
            TextToSpeech.EngineInfo info = availableEngines.get(i);
            engineNames.add(info.label + " (" + info.name + ")");
            if (info.name.equalsIgnoreCase(savedEngine)) {
                selectedEngineIdx = i + 1;
            }
        }

        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, engineNames);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ttsEngineSpinner.setAdapter(engineAdapter);
        ttsEngineSpinner.setSelection(selectedEngineIdx, false);

        ttsEngineSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!ttsUiReady || speechController.getEngineState() != SpeechController.EngineState.READY) return;
                String selectedPkg = (position > 0 && position <= availableEngines.size())
                        ? availableEngines.get(position - 1).name
                        : "";
                if (selectedPkg.equals(ConfigStore.loadTtsEnginePackage(MainActivity.this))) return;
                ConfigStore.saveTtsEnginePackage(MainActivity.this, selectedPkg);
                ConfigStore.saveTtsVoiceName(MainActivity.this, "");
                createSpeechController();
                ScreenCaptureService.reloadSpeechSettings();
                setupTtsSpinners();
                status("TTS-Engine geändert: " + (selectedPkg.isEmpty() ? "Default" : selectedPkg));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        availableVoices.clear();
        availableVoices.addAll(speechController.getAvailableGermanVoices());

        List<String> voiceNames = new ArrayList<>();
        voiceNames.add("Standard (System-Default Stimme)");
        String savedVoice = ConfigStore.loadTtsVoiceName(this);
        int selectedVoiceIdx = 0;

        for (int i = 0; i < availableVoices.size(); i++) {
            Voice v = availableVoices.get(i);
            String name = v.getName();
            voiceNames.add(name);
            if (name.equalsIgnoreCase(savedVoice)) {
                selectedVoiceIdx = i + 1;
            }
        }

        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, voiceNames);
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ttsVoiceSpinner.setAdapter(voiceAdapter);
        ttsVoiceSpinner.setSelection(selectedVoiceIdx, false);

        ttsVoiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!ttsUiReady || speechController.getEngineState() != SpeechController.EngineState.READY) return;
                String selectedVoiceName = (position > 0 && position <= availableVoices.size())
                        ? availableVoices.get(position - 1).getName()
                        : "";
                if (selectedVoiceName.equals(ConfigStore.loadTtsVoiceName(MainActivity.this))) return;
                speechController.setVoiceName(selectedVoiceName);
                ScreenCaptureService.reloadSpeechSettings();
                status("Stimme gewählt: " + (selectedVoiceName.isEmpty() ? "Standard" : selectedVoiceName));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ttsUiReady = true;
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
        if (speechController != null) {
            setupTtsSpinners();
        }
    }

    @Override
    protected void onDestroy() {
        if (speechController != null) {
            speechController.shutdown();
            speechController = null;
        }
        super.onDestroy();
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
