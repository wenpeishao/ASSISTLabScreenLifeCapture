package com.screenomics;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import androidx.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class DevToolsActivity extends Activity {

    String imagesPath;
    private VlmBenchmark vlmBenchmark;
    private CaptureService captureService;
    private boolean serviceBound = false;

    private final ServiceConnection captureConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            captureService = ((CaptureService.LocalBinder) binder).getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            captureService = null;
            serviceBound = false;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Defense in depth: even if the isDev pref was set before this gate
        // existed, the screen must not open in a production release build.
        if (!MainActivity.isDevToolsAllowed()) {
            finish();
            return;
        }
        setContentView(R.layout.dev_tools);
        imagesPath = getApplicationContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt";
        Button resetButton = findViewById(R.id.clearPrefs);
        Button clearImagesButton = findViewById(R.id.clearImages);
        Button attemptUploadButton = findViewById(R.id.attemptUpload);
        Button attemptUploadButton2 = findViewById(R.id.attemptUpload2);
        Button setBatchSizeButton = findViewById(R.id.setBatchSizeButton);
        EditText batchSizeInput = findViewById(R.id.batchSizeInput);
        Button setMaxSendButton = findViewById(R.id.setMaxSendButton);
        EditText maxSendInput = findViewById(R.id.maxSendInput);
        Button disableButton = findViewById(R.id.disableButton);
        Button wifiButton = findViewById(R.id.testWifiButton);
        Button mobileButton = findViewById(R.id.testMobileButton);
        Button accessibilitySettingsButton = findViewById(R.id.openAccessibilitySettingsButton);
        TextView wifiResult = findViewById(R.id.wifiResultText);
        TextView mobileResult = findViewById(R.id.mobileResultText);
        Switch accessibilityCaptureSwitch = findViewById(R.id.accessibilityCaptureSwitch);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        batchSizeInput.setText(String.valueOf(prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT)));
        maxSendInput.setText(String.valueOf(prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT)));
        accessibilityCaptureSwitch.setChecked(prefs.getBoolean("useAccessibilityCapture", false));

        wifiButton.setOnClickListener(view -> {
            if(InternetConnection.checkWiFiConnection(getApplicationContext())){
                wifiResult.setText("OK");
            }else{
                wifiResult.setText("No WiFi");
            }

        });

        mobileButton.setOnClickListener(v -> {
            if(InternetConnection.checkMobileDataConnection(getApplicationContext())){
                mobileResult.setText("OK");
            }else{
                mobileResult.setText("No Cell");
            }
        });

        accessibilityCaptureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("useAccessibilityCapture", isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "Accessibility capture mode enabled for internal testing", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Accessibility capture mode disabled", Toast.LENGTH_SHORT).show();
            }
        });

        accessibilitySettingsButton.setOnClickListener(v -> {
            try {
                startActivity(AccessibilityCaptureService.buildAccessibilitySettingsIntent());
            } catch (Exception e) {
                Toast.makeText(this, "Failed to open accessibility settings", Toast.LENGTH_LONG).show();
            }
        });

        /*
        endpointTestButton.setOnClickListener(v -> {
            new NetTester().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null);

        });*/

        clearImagesButton.setOnClickListener(v -> {
            File dir = new File(imagesPath);
            if (!dir.exists()) dir.mkdir();
            File[] files = dir.listFiles();
            for (File file : files) {
                try {
                    file.delete();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        resetButton.setOnClickListener(v -> {
            File outputDir = new File(imagesPath);
            int numImages = outputDir.listFiles().length;
            if (numImages != 0) {
                Toast.makeText(getApplicationContext(), "Cannot reset if images still exist on the device", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear().apply();
            Toast.makeText(getApplicationContext(), "Reset successful, restarting app", Toast.LENGTH_SHORT).show();
            finishAffinity();
        });

        attemptUploadButton.setOnClickListener(v -> {
            UploadScheduler.scheduleUploadInSeconds(getApplicationContext(), 10);
            Toast.makeText(getApplicationContext(), "Setting upload in 10s...", Toast.LENGTH_SHORT).show();
        });

        attemptUploadButton2.setOnClickListener(v -> {
            UploadScheduler.scheduleUploadInSeconds(getApplicationContext(), 300);
            Toast.makeText(getApplicationContext(), "Setting upload in 5m...", Toast.LENGTH_SHORT).show();
        });

        batchSizeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) { }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) { }

            @Override
            public void afterTextChanged(Editable edt) {
                if (edt.length() == 1 && edt.toString().equals("0"))
                    batchSizeInput.setText("");
            }
        });

        setBatchSizeButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("batchSize", Integer.parseInt(batchSizeInput.getText().toString()));
            editor.apply();
            Toast.makeText(this, "Done!", Toast.LENGTH_SHORT).show();
        });

        setMaxSendButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("maxSend", Integer.parseInt(maxSendInput.getText().toString()));
            editor.apply();
            Toast.makeText(this, "Done!", Toast.LENGTH_SHORT).show();
        });

        disableButton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("isDev", false);
            editor.apply();
            finish();
        });

        // --- VLM Benchmark (mindpulseDev only; card hidden in standard) ---
        // Everything below this guard is dev-flavor-only setup.
        if (!VlmBenchmark.AVAILABLE) {
            findViewById(R.id.vlmCard).setVisibility(android.view.View.GONE);
            return;
        }
        Switch vlmSwitch = findViewById(R.id.vlmBenchmarkSwitch);
        EditText vlmModelPath = findViewById(R.id.vlmModelPathInput);
        EditText vlmMmprojPath = findViewById(R.id.vlmMmprojPathInput);
        EditText vlmThreadsInput = findViewById(R.id.vlmThreadsInput);
        TextView vlmStatus = findViewById(R.id.vlmStatusText);
        TextView vlmMetrics = findViewById(R.id.vlmMetricsText);
        Button vlmExportLog = findViewById(R.id.vlmExportLogButton);

        // Restore saved model path
        vlmModelPath.setText(prefs.getString("vlmModelPath", ""));
        vlmMmprojPath.setText(prefs.getString("vlmMmprojPath", ""));

        // Bind to CaptureService to inject VLM benchmark
        Intent captureIntent = new Intent(this, CaptureService.class);
        bindService(captureIntent, captureConnection, Context.BIND_AUTO_CREATE);

        vlmSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                String modelPath = vlmModelPath.getText().toString().trim();
                if (modelPath.isEmpty()) {
                    Toast.makeText(this, "Set model path first", Toast.LENGTH_SHORT).show();
                    vlmSwitch.setChecked(false);
                    return;
                }
                if (!new File(modelPath).exists()) {
                    Toast.makeText(this, "Model file not found: " + modelPath, Toast.LENGTH_LONG).show();
                    vlmSwitch.setChecked(false);
                    return;
                }

                // Save paths
                prefs.edit()
                    .putString("vlmModelPath", modelPath)
                    .putString("vlmMmprojPath", vlmMmprojPath.getText().toString().trim())
                    .apply();

                String mmproj = vlmMmprojPath.getText().toString().trim();
                int threads = 4;
                try { threads = Integer.parseInt(vlmThreadsInput.getText().toString()); } catch (Exception ignored) {}

                vlmBenchmark = new VlmBenchmark(DevToolsActivity.this);
                vlmBenchmark.setStatusListener(new VlmBenchmark.StatusListener() {
                    @Override
                    public void onStatusUpdate(String status) {
                        vlmStatus.setText(status);
                    }
                    @Override
                    public void onMetricsUpdate(double lastMs, double avgMs, int total, int skipped, float batteryDrain) {
                        vlmMetrics.setText(String.format(Locale.US,
                            "Last: %.0fms  Avg: %.0fms  Total: %d  Skip: %d  Bat: -%.1f%%",
                            lastMs, avgMs, total, skipped, batteryDrain));
                    }
                });

                vlmBenchmark.start(modelPath, mmproj.isEmpty() ? null : mmproj, threads);

                // Inject into CaptureService and AccessibilityCaptureService
                if (serviceBound && captureService != null) {
                    captureService.setVlmBenchmark(vlmBenchmark);
                }
                AccessibilityCaptureService.setVlmBenchmark(vlmBenchmark);

                Toast.makeText(this, "VLM benchmark started", Toast.LENGTH_SHORT).show();
            } else {
                if (vlmBenchmark != null) {
                    vlmBenchmark.stop();
                    if (serviceBound && captureService != null) {
                        captureService.setVlmBenchmark(null);
                    }
                    AccessibilityCaptureService.setVlmBenchmark(null);
                    vlmBenchmark = null;
                }
                vlmStatus.setText("Stopped");
                Toast.makeText(this, "VLM benchmark stopped", Toast.LENGTH_SHORT).show();
            }
        });

        vlmExportLog.setOnClickListener(v -> {
            if (vlmBenchmark != null && vlmBenchmark.getLogFile() != null) {
                String path = vlmBenchmark.getLogFile().getAbsolutePath();
                Toast.makeText(this, "Log: " + path, Toast.LENGTH_LONG).show();
                Log.i("VLM_DEVTOOLS", "Benchmark CSV: " + path);
            } else {
                Toast.makeText(this, "No active benchmark log", Toast.LENGTH_SHORT).show();
            }
        });

        // Headless embedding-only benchmark trigger (dev/testing via adb am start).
        // Example:
        //   adb shell am start -n edu.wisc.chm.screenomics.mindpulse/com.screenomics.DevToolsActivity \
        //     --ez run_embed_bench true \
        //     --es model /sdcard/Android/data/.../files/models/Qwen3.5-0.8B-Q4_K_M.gguf \
        //     --es mmproj /sdcard/Android/data/.../files/models/mmproj-Qwen3.5-0.8B-f16.gguf \
        //     --es image /sdcard/Android/data/.../files/models/testimg.jpg \
        //     --ei threads 6 --ei dur_ms 360000
        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getBooleanExtra("run_embed_bench", false)) {
            String m = launchIntent.getStringExtra("model");
            String mm = launchIntent.getStringExtra("mmproj");
            String img = launchIntent.getStringExtra("image");
            int th = launchIntent.getIntExtra("threads", 4);
            long dur = launchIntent.getIntExtra("dur_ms", 360000);
            boolean useGpu = launchIntent.getBooleanExtra("use_gpu", false);
            Log.i("VLM_DEVTOOLS", "Embed bench: model=" + m + " mmproj=" + mm + " image=" + img
                    + " threads=" + th + " dur_ms=" + dur + " use_gpu=" + useGpu);
            Toast.makeText(this, "Embedding benchmark started (see logcat EMBED_BENCH)", Toast.LENGTH_LONG).show();
            VlmBenchmark.runEmbeddingBenchmark(getApplicationContext(), m, mm, img, th, dur, useGpu);
        }

        if (launchIntent != null && launchIntent.getBooleanExtra("run_caption_bench", false)) {
            String m = launchIntent.getStringExtra("model");
            String mm = launchIntent.getStringExtra("mmproj");
            String img = launchIntent.getStringExtra("image");
            int th = launchIntent.getIntExtra("threads", 4);
            long dur = launchIntent.getIntExtra("dur_ms", 30000);
            int maxTok = launchIntent.getIntExtra("max_tokens", 40);
            String prompt = launchIntent.getStringExtra("prompt");
            if (prompt == null) prompt = "Describe what you see in one short sentence.";
            boolean think = launchIntent.getBooleanExtra("think", false);
            Log.i("VLM_DEVTOOLS", "Caption bench: model=" + m + " image=" + img + " maxTok=" + maxTok + " think=" + think);
            Toast.makeText(this, "Caption benchmark started (see logcat CAP_BENCH)", Toast.LENGTH_LONG).show();
            VlmBenchmark.runCaptionBenchmark(getApplicationContext(), m, mm, img, th, dur, prompt, maxTok, think);
        }
    }


    /*public class NetTester extends AsyncTask<Void, Integer, Void>{
        @Override
        protected Void doInBackground(Void... params){
            Response response = canReachEndpoint();
            mobileEndpointResult.setText(response.body().toString());
            return null;
        }
    }*/

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(captureConnection);
            serviceBound = false;
        }
        // The benchmark may keep running in the capture services after this screen
        // closes, but the listener holds this Activity and its views — detach it.
        if (vlmBenchmark != null) {
            vlmBenchmark.setStatusListener(null);
        }
    }

    public Response canReachEndpoint(){
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(Constants.HEALTHCHECK_ADDRESS).get().build();

        Response response = null;
        try {
            //long startTime = System.nanoTime();
            response = client.newCall(request).execute();
            Log.d("SCREENOMICS_TEST", response.toString());
        } catch (IOException e) {
            Log.d("SCREENOMICS_ERROR", e.toString());
            e.printStackTrace();
        }
        return response;
    }
}
