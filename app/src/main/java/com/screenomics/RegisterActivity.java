package com.screenomics;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.preference.PreferenceManager;

import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.text.SimpleDateFormat;
import java.util.Locale;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

public class RegisterActivity extends AppCompatActivity {
    private static final String TAG = "RegisterActivity";

    // Point to your Receiver base (also saved into SharedPreferences as base_url)
    private static final String RECEIVER_BASE = "https://mindpulse.ssc.wisc.edu";
    private static final String ENROLL_PATH   = "/api/v1/enroll";
    private static final String HEALTH_PATH   = "/api/v1/health";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS   = "mindpulse_client_key";

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private OkHttpClient http;

    private String key;   // user-entered/QR code (short 8 or long token)
    private String hash;  // SHA-256 of key (hex) used for UI verification

    private Button continueButton;
    private Button scanQrButton;
    private ActivityResultLauncher<String> imagePickerLauncher;
    private ActivityResultLauncher<ScanOptions> qrScannerLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.register);

        http = HttpClientProvider.get(this); // includes HttpSignatureInterceptor

        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) processQRImageFromGallery(uri); }
        );
        qrScannerLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                setQR(result.getContents());
                Toast.makeText(this, "QR code scanned", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
            }
        });
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        launchQrScanner();
                    } else {
                        Toast.makeText(this, "Camera permission is required to scan QR codes.", Toast.LENGTH_LONG).show();
                    }
                });

        continueButton = findViewById(R.id.continueButton);
        continueButton.setOnClickListener(v -> {
            if (key == null || !(key.length() == 8 || (key.length() >= 40 && key.length() <= 60))) {
                Toast.makeText(this, "Scan or enter a valid 8- or 40–60-character code.", Toast.LENGTH_SHORT).show();
                return;
            }
            enrollWithReceiver(key);
        });

        scanQrButton = findViewById(R.id.scanQrButton);
        scanQrButton.setOnClickListener(v -> startQrScanFlow());

        Button manualEntryButton = findViewById(R.id.manualEntryButton);
        manualEntryButton.setOnClickListener(v -> showManualInputDialog());

        Button uploadFromGalleryButton = findViewById(R.id.uploadFromGalleryButton);
        uploadFromGalleryButton.setOnClickListener(v -> openImagePicker());
    }

    private void startQrScanFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchQrScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Align the QR code within the frame");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        qrScannerLauncher.launch(options);
    }

    private void showManualInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Manual Code Entry");
        builder.setMessage("Enter your registration code (8- or 40–60-character).");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter your code");
        builder.setView(input);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String mText = input.getText().toString().trim();
            if (mText.length() == 8 || (mText.length() >= 40 && mText.length() <= 60)) {
                setQR(mText);
            } else {
                Toast.makeText(this, "Invalid code length. Must be 8 or ~40–60 characters.", Toast.LENGTH_LONG).show();
                showManualInputDialog();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.setCancelable(false);
        builder.show();
    }

    private void setQR(String _qrCode) {
        if (_qrCode == null) return;
        int len = _qrCode.trim().length();
        if (!(len == 8 || (len >= 40 && len <= 60))) {
            Toast.makeText(this, "Invalid code. Must be 8 or 40–60 characters.", Toast.LENGTH_SHORT).show();
            return;
        }

        key = _qrCode.trim();
        try {
            hash = toHexString(getSHA(key));
            continueButton.setText(len == 8
                    ? "Short code: " + key
                    : "Verification: " + hash.substring(0, 4));
            continueButton.setEnabled(true);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    /** Enroll with Receiver (signed via HttpSignatureInterceptor) */
    private void enrollWithReceiver(String code) {
        continueButton.setEnabled(false);
        continueButton.setText("Enrolling…");

        new Thread(() -> {
            // optional health check to warm up TLS, etc.
            try {
                Request health = new Request.Builder()
                        .url(RECEIVER_BASE + HEALTH_PATH)
                        .get().build();
                http.newCall(health).execute().close();
            } catch (Exception ignored) {}

            // prepare signing public key
            String clientPubPem;
            try {
                clientPubPem = getOrCreateClientPublicKeyPem();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    continueButton.setEnabled(true);
                    continueButton.setText("Continue");
                    Toast.makeText(this, "Keypair error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }

            // build JSON body
            JSONObject body = new JSONObject();
            boolean longToken = (code.length() >= 40 && code.length() <= 60);
            try {
                body.put("client_public_key", clientPubPem);
                if (longToken) {
                    String shortCode = toHexString(getSHA(code)).substring(0, 8);
                    body.put("enrollment_token", code);
                    body.put("short_code", shortCode);
                } else { // short code only
                    body.put("short_code", code);
                }
            } catch (JSONException | NoSuchAlgorithmException ignored) {}

            // request with optional Authorization: Bearer <token> for long tokens
            Request.Builder rb = new Request.Builder()
                    .url(RECEIVER_BASE + ENROLL_PATH)
                    .post(RequestBody.create(body.toString(), JSON_MEDIA));

            if (longToken) {
                rb.addHeader("Authorization", "Bearer " + code);
            }

            Request req = rb.build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    int codeVal = resp.code();
                    runOnUiThread(() -> {
                        continueButton.setEnabled(true);
                        continueButton.setText("Continue");
                        Toast.makeText(this, "Enrollment failed (" + codeVal + ")", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                String json = resp.body() != null ? resp.body().string() : "{}";
                JSONObject obj = new JSONObject(json);
                String pptId       = obj.optString("ppt_id", "");
                String studyId     = obj.optString("study_id", "");
                String imagePubPem = obj.optString("image_public_key", "");

                // persist for later uploads
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString("ppt_id", pptId);
                ed.putString("study_id", studyId);
                ed.putString("image_public_key", imagePubPem); // new key name used by Batch
                ed.putString("base_url", RECEIVER_BASE);
                ed.putString("key", key);
                ed.putString("hash", hash);

                if (longToken) {
                    ed.putString("enrollment_token", code);
                } else {
                    ed.remove("enrollment_token");
                }

                // remove any legacy key name
                ed.remove("image_public_key_pem");
                ed.apply();

                runOnUiThread(() -> {
                    String shortId = pptId.length() >= 4 ? pptId.substring(0, 4) : pptId;
                    continueButton.setText("Enrolled: " + shortId);
                    continueButton.setEnabled(true);
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    continueButton.setEnabled(true);
                    continueButton.setText("Continue");
                    Toast.makeText(this, "Enrollment error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Ensure the RSA key supports both PSS and PKCS#1 paddings */
    private String getOrCreateClientPublicKeyPem() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);

        // Always delete old alias to force new key with correct paddings
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            Log.w(TAG, "Deleting existing key alias to regenerate with both paddings");
            ks.deleteEntry(KEYSTORE_ALIAS);
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                )
                .setUserAuthenticationRequired(false)
                .build();

        try {
            kpg.initialize(spec);
            KeyPair kp = kpg.generateKeyPair();
            Log.i(TAG, "✅ Generated new RSA key in AndroidKeyStore with PSS + PKCS#1 support");
            return exportPublicKeyPem(kp.getPublic());
        } catch (Exception ex) {
            Log.e(TAG, "❌ AndroidKeyStore key generation failed, falling back to software key: " + ex.getMessage());
            // Fallback: generate an in-memory software keypair
            KeyPairGenerator softGen = KeyPairGenerator.getInstance("RSA");
            softGen.initialize(2048);
            KeyPair kp = softGen.generateKeyPair();
            Log.w(TAG, "⚠️ Using in-memory software RSA key (not persisted)");
            return exportPublicKeyPem(kp.getPublic());
        }
    }

    /** Utility to encode public key as PEM */
    private static String exportPublicKeyPem(PublicKey pub) {
        String b64 = Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 64)
            sb.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
        return "-----BEGIN PUBLIC KEY-----\n" + sb.toString().trim() + "\n-----END PUBLIC KEY-----";
    }

    private byte[] getSHA(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(input.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    private String toHexString(byte[] hash) {
        BigInteger num = new BigInteger(1, hash);
        StringBuilder hex = new StringBuilder(num.toString(16));
        while (hex.length() < 64) hex.insert(0, '0');
        return hex.toString();
    }

    private void openImagePicker() { imagePickerLauncher.launch("image/*"); }

    private void processQRImageFromGallery(Uri uri) {
        try {
            Bitmap bmp = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            String qr = decodeQRCode(bmp);
            if (qr != null && !qr.isEmpty()) {
                setQR(qr);
                Toast.makeText(this, "QR code found!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No QR code found in the image", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error reading QR: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String decodeQRCode(Bitmap bitmap) {
        try {
            int[] intArray = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(intArray, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            RGBLuminanceSource source = new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), intArray);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(binaryBitmap);
            return result.getText();
        } catch (Exception e) {
            Log.w(TAG, "QR not found", e);
            return null;
        }
    }

    @Override public void onBackPressed() {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return keyCode == KeyEvent.KEYCODE_BACK || super.onKeyDown(keyCode, event);
    }
}
