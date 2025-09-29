package com.screenomics;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class DevToolsActivity extends Activity {

    String imagesPath;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        TextView wifiResult = findViewById(R.id.wifiResultText);
        TextView mobileResult = findViewById(R.id.mobileResultText);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        batchSizeInput.setText(String.valueOf(prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT)));
        maxSendInput.setText(String.valueOf(prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT)));

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
    }


    /*public class NetTester extends AsyncTask<Void, Integer, Void>{
        @Override
        protected Void doInBackground(Void... params){
            Response response = canReachEndpoint();
            mobileEndpointResult.setText(response.body().toString());
            return null;
        }
    }*/

    public Response canReachEndpoint(){
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(Constants.UPLOAD_ADDRESS).get().build();

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