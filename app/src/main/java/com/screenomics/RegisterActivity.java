package com.screenomics;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.preference.PreferenceManager;

import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.common.util.concurrent.ListenableFuture;

import android.util.Size;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

public class RegisterActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CAMERA = 0;
    private static final int PERMISSION_REQUEST_CAM_NOTIF = 1;
    private String key;
    private String hash;
    private PreviewView previewView;
    private Button errorButton;
    private Button continueButton;
    private Button manualEntryButton;
    private Button uploadFromGalleryButton;
    private Button skipForTestingButton;
    private View permissionOverlay;
    private View alternativeOptions;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    private ActivityResultLauncher<String> imagePickerLauncher;
// passphrase:  uwscreenomics022
    @Override
    protected void onResume() {
        super.onResume();
        // Check camera permission when returning from settings
        // Add small delay to ensure permission state is updated
        previewView.post(() -> {
            checkAndStartCamera();
        });
    }

    private void checkAndStartCamera() {
        boolean hasPermission = PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);
        Log.d("RegisterActivity", "checkAndStartCamera - hasPermission: " + hasPermission);

        if (hasPermission) {
            if (permissionOverlay != null) {
                permissionOverlay.setVisibility(View.GONE);
                Log.d("RegisterActivity", "Permission overlay hidden");
            }
            // Always try to start camera when permission is available
            // This handles cases where user grants permission from settings
            if (cameraProviderFuture != null && previewView != null) {
                Log.d("RegisterActivity", "Starting camera...");
                startCamera();
            } else {
                Log.w("RegisterActivity", "Camera provider or preview view is null");
            }
        } else {
            // Show overlay if permission not granted
            if (permissionOverlay != null) {
                permissionOverlay.setVisibility(View.VISIBLE);
                Log.d("RegisterActivity", "Permission overlay shown");
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.register);

        // Check if this is an update operation
        boolean isUpdate = getIntent().getBooleanExtra("isUpdate", false);
        Log.d("RegisterActivity", "onCreate - isUpdate: " + isUpdate);

        if (isUpdate) {
            // Hide the skip for testing button when updating
            View skipButton = findViewById(R.id.skipForTestingButton);
            if (skipButton != null) {
                skipButton.setVisibility(View.GONE);
                Log.d("RegisterActivity", "Skip button hidden for update mode");
            }
        }

        // Initialize image picker launcher
        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processQRImageFromGallery(uri);
                }
            }
        );

        previewView = findViewById(R.id.activity_main_previewView);
        permissionOverlay = findViewById(R.id.camera_permission_overlay);
        alternativeOptions = findViewById(R.id.alternativeOptions);

        errorButton = findViewById(R.id.errorButton);
        errorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showDialog();
            }
        });

        continueButton = findViewById(R.id.continueButton);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                commitSharedPreferences();
                Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                RegisterActivity.this.startActivity(intent);
                finish();
            }
        });

        manualEntryButton = findViewById(R.id.manualEntryButton);
        manualEntryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showManualInputDialog();
            }
        });

        uploadFromGalleryButton = findViewById(R.id.uploadFromGalleryButton);
        uploadFromGalleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openImagePicker();
            }
        });

        skipForTestingButton = findViewById(R.id.skipForTestingButton);
        skipForTestingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                generateTestId();
            }
        });

        // Setup permission overlay buttons
        Button grantPermissionButton = findViewById(R.id.grant_permission_button);
        Button manualEntryOverlayButton = findViewById(R.id.manual_entry_button);

        if (grantPermissionButton != null) {
            grantPermissionButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestCamera(); // Use the centralized method
                }
            });
        }

        if (manualEntryOverlayButton != null) {
            manualEntryOverlayButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showManualInputDialog();
                }
            });
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        requestCamera();
    }

    private void showDialog() {
        showManualInputDialog();
    }

    private void showManualInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Manual Code Entry");
        builder.setMessage("If you have your registration code, you can enter it manually below.\n\n" +
                          "The code should be 64 characters long.");

        // Create a custom layout for better UX
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter your 64-character code");
        builder.setView(input);

        builder.setPositiveButton("Submit", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String m_Text = input.getText().toString().trim();
                if (m_Text.length() == 64) {
                    setQR(m_Text);
                } else {
                    Toast.makeText(RegisterActivity.this,
                        "Invalid code length. Code must be exactly 64 characters.",
                        Toast.LENGTH_LONG).show();
                    showManualInputDialog(); // Show dialog again
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                // Try camera again
                requestCamera();
            }
        });
        builder.setCancelable(false);
        builder.show();
    }


    private void requestCamera() {
        PermissionHelper.requestPermissionWithExplanation(
            this,
            Manifest.permission.CAMERA,
            PermissionHelper.PERMISSION_REQUEST_CAMERA,
            new PermissionHelper.PermissionCallback() {
                @Override
                public void onPermissionGranted() {
                    if (permissionOverlay != null) {
                        permissionOverlay.setVisibility(View.GONE);
                    }
                    startCamera();
                }

                @Override
                public void onPermissionDenied() {
                    if (permissionOverlay != null) {
                        permissionOverlay.setVisibility(View.VISIBLE);
                    }
                    showManualInputDialog();
                }

                @Override
                public void onPermissionPermanentlyDenied() {
                    if (permissionOverlay != null) {
                        permissionOverlay.setVisibility(View.VISIBLE);
                    }
                }
            }
        );

        // Show overlay initially if permission not granted
        if (!PermissionHelper.hasPermission(this, Manifest.permission.CAMERA)) {
            if (permissionOverlay != null) {
                permissionOverlay.setVisibility(View.VISIBLE);
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PermissionHelper.PERMISSION_REQUEST_CAMERA) {
            boolean cameraGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            PermissionHelper.handlePermissionResult(
                this,
                Manifest.permission.CAMERA,
                cameraGranted,
                new PermissionHelper.PermissionCallback() {
                    @Override
                    public void onPermissionGranted() {
                        if (permissionOverlay != null) {
                            permissionOverlay.setVisibility(View.GONE);
                        }
                        startCamera();
                    }

                    @Override
                    public void onPermissionDenied() {
                        if (permissionOverlay != null) {
                            permissionOverlay.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onPermissionPermanentlyDenied() {
                        if (permissionOverlay != null) {
                            permissionOverlay.setVisibility(View.VISIBLE);
                        }
                    }
                }
            );

            // Handle notification permission if it was also requested (Android 13+)
            if (grantResults.length > 1) {
                boolean notificationGranted = grantResults[1] == PackageManager.PERMISSION_GRANTED;
                if (cameraGranted && !notificationGranted) {
                    Toast.makeText(this, "Notifications disabled. You won't receive app alerts.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }


    private void startCamera() {
        if (!PermissionHelper.hasPermission(this, Manifest.permission.CAMERA)) {
            return; // Don't start camera without permission
        }

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll();
                bindCameraPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("RegisterActivity", "Error starting camera", e);
                Toast.makeText(this, "Error starting camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview(@NonNull ProcessCameraProvider cameraProvider) {
        try {
            previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);

            Preview preview = new Preview.Builder()
                    .build();

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new QRCodeImageAnalyzer(new QRCodeFoundListener() {
                @Override
                public void onQRCodeFound(String _qrCode) {
                    setQR(_qrCode);
                }

                @Override
                public void qrCodeNotFound() { }
            }));

            // Bind to lifecycle
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, preview);

            Log.d("RegisterActivity", "Camera successfully bound");
        } catch (Exception e) {
            Log.e("RegisterActivity", "Error binding camera preview", e);
            Toast.makeText(this, "Failed to start camera preview", Toast.LENGTH_SHORT).show();
        }
    }

    private void setQR(String _qrCode) {
        if (_qrCode.length() == 64) {
            key = _qrCode;
            try {
                System.out.println("getSHA " + getSHA(key));
                System.out.println("hash " + toHexString(getSHA(key)));
                hash = toHexString(getSHA(key));
                continueButton.setText("Verification code: " + hash.substring(0, 4));
                continueButton.setEnabled(true);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
    }

    private void commitSharedPreferences() {
        commitSharedPreferences(false);
    }

    private void commitSharedPreferences(boolean isTester) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("key", key);
        editor.putString("hash", hash);
        editor.putBoolean("isTester", isTester);
        if (isTester) {
            editor.putString("testerTimestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        }
        editor.apply();
    }


    private byte[] getSHA(String input)  throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Converter.hexStringToByteArray(input));
        return md.digest();
    }

    private String toHexString(byte[] hash) {
        BigInteger num = new BigInteger(1, hash);
        StringBuilder hexString = new StringBuilder(num.toString(16));
        while (hexString.length() < 64) {
            hexString.insert(0, '0');
        }
        return hexString.toString();
    }

    @Override
    public void onBackPressed () { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {

        if (keyCode == KeyEvent.KEYCODE_BACK)  //Override Keyback to do nothing in this case.
        {
            return true;
        }
        return super.onKeyDown(keyCode, event);  //-->All others key will work as usual
    }

    private void openImagePicker() {
        imagePickerLauncher.launch("image/*");
    }

    private void processQRImageFromGallery(Uri imageUri) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
            String qrCode = decodeQRCode(bitmap);
            if (qrCode != null && !qrCode.isEmpty()) {
                setQR(qrCode);
                Toast.makeText(this, "QR code found!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No QR code found in the image", Toast.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            Log.e("RegisterActivity", "Error processing image", e);
            Toast.makeText(this, "Error processing image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("RegisterActivity", "Error decoding QR code", e);
            Toast.makeText(this, "Error reading QR code from image", Toast.LENGTH_LONG).show();
        }
    }

    private String decodeQRCode(Bitmap bitmap) {
        try {
            int[] intArray = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(intArray, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

            RGBLuminanceSource source = new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), intArray);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(binaryBitmap);
            return result.getText();
        } catch (Exception e) {
            Log.w("RegisterActivity", "QR code not found in image", e);
            return null;
        }
    }

    private void generateTestId() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Generate Test ID");
        builder.setMessage("This will create a test ID for development purposes.\n\n" +
                          "Test data will be identifiable on the backend for easy filtering.\n\n" +
                          "Continue with test ID generation?");
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("Generate Test ID", (dialog, which) -> {
            try {
                // Generate a test ID with "TEST_" prefix and timestamp
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                SecureRandom random = new SecureRandom();

                // Generate 32 random hex characters (like a real QR code but shorter)
                StringBuilder testKey = new StringBuilder("TEST_");
                testKey.append(timestamp).append("_");

                // Add random hex characters to make it 64 characters total
                String hexChars = "0123456789abcdef";
                int remainingLength = 64 - testKey.length();
                for (int i = 0; i < remainingLength; i++) {
                    testKey.append(hexChars.charAt(random.nextInt(hexChars.length())));
                }

                key = testKey.toString();
                hash = toHexString(getSHA(key));

                continueButton.setText("Test ID: " + hash.substring(0, 8) + "...");
                continueButton.setEnabled(true);

                Toast.makeText(this, "Test ID generated successfully!", Toast.LENGTH_SHORT).show();

                // Show the generated test info
                showTestIdInfo();

            } catch (NoSuchAlgorithmException e) {
                Log.e("RegisterActivity", "Error generating test ID", e);
                Toast.makeText(this, "Error generating test ID", Toast.LENGTH_LONG).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showTestIdInfo() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Test ID Generated");

        String info = "Test ID Details:\n\n" +
                     "Key: " + key + "\n\n" +
                     "Hash: " + hash.substring(0, 16) + "...\n\n" +
                     "This is a TESTER account. All data will be marked as test data on the backend.";

        builder.setMessage(info);
        builder.setPositiveButton("Continue to App", (dialog, which) -> {
            commitSharedPreferences(true); // Mark as tester
            Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        });

        builder.setNegativeButton("Generate New", (dialog, which) -> {
            generateTestId(); // Generate another test ID
        });

        builder.setCancelable(false);
        builder.show();
    }

}
