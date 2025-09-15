package com.screenomics;

import android.Manifest;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class PermissionTestActivity extends AppCompatActivity {

    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_test);

        statusTextView = findViewById(R.id.permission_status);

        Button cameraButton = findViewById(R.id.test_camera_button);
        Button locationButton = findViewById(R.id.test_location_button);
        Button notificationButton = findViewById(R.id.test_notification_button);
        Button allButton = findViewById(R.id.test_all_button);

        cameraButton.setOnClickListener(v -> testCameraPermission());
        locationButton.setOnClickListener(v -> testLocationPermission());
        notificationButton.setOnClickListener(v -> testNotificationPermission());
        allButton.setOnClickListener(v -> testAllPermissions());

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Permission Status:\n\n");

        status.append("Camera: ")
              .append(PermissionHelper.hasPermission(this, Manifest.permission.CAMERA) ? "✓ Granted" : "✗ Denied")
              .append("\n");

        status.append("Location (Fine): ")
              .append(PermissionHelper.hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ? "✓ Granted" : "✗ Denied")
              .append("\n");

        status.append("Location (Coarse): ")
              .append(PermissionHelper.hasPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ? "✓ Granted" : "✗ Denied")
              .append("\n");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            status.append("Background Location: ")
                  .append(PermissionHelper.hasPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ? "✓ Granted" : "✗ Denied")
                  .append("\n");
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            status.append("Notifications: ")
                  .append(PermissionHelper.hasPermission(this, Manifest.permission.POST_NOTIFICATIONS) ? "✓ Granted" : "✗ Denied")
                  .append("\n");
        }

        statusTextView.setText(status.toString());
    }

    private void testCameraPermission() {
        PermissionHelper.requestPermissionWithExplanation(
            this,
            Manifest.permission.CAMERA,
            PermissionHelper.PERMISSION_REQUEST_CAMERA,
            createCallback("Camera")
        );
    }

    private void testLocationPermission() {
        PermissionHelper.requestLocationPermissions(this, createCallback("Location"));
    }

    private void testNotificationPermission() {
        PermissionHelper.requestNotificationPermission(this, createCallback("Notification"));
    }

    private void testAllPermissions() {
        String[] allPermissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            allPermissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            };
        }

        PermissionHelper.requestPermissionsWithExplanation(
            this,
            allPermissions,
            PermissionHelper.PERMISSION_REQUEST_ALL
        );
    }

    private PermissionHelper.PermissionCallback createCallback(String permissionName) {
        return new PermissionHelper.PermissionCallback() {
            @Override
            public void onPermissionGranted() {
                Toast.makeText(PermissionTestActivity.this,
                    permissionName + " permission granted!",
                    Toast.LENGTH_SHORT).show();
                updateStatus();
            }

            @Override
            public void onPermissionDenied() {
                Toast.makeText(PermissionTestActivity.this,
                    permissionName + " permission denied",
                    Toast.LENGTH_SHORT).show();
                updateStatus();
            }

            @Override
            public void onPermissionPermanentlyDenied() {
                Toast.makeText(PermissionTestActivity.this,
                    permissionName + " permission permanently denied",
                    Toast.LENGTH_LONG).show();
                updateStatus();
            }
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
    }
}