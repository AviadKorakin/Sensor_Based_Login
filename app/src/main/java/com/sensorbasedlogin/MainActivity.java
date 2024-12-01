package com.sensorbasedlogin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Canvas;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.biometric.BiometricPrompt;

import android.os.Bundle;

import com.sensorbasedlogin.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    private static final String SECRET_KEYWORD = "hasamba";

    // Task validation flags
    private boolean isBarcodeValid = false;
    private boolean isBiometricsValid = false;
    private boolean isShakeValid = false;
    private boolean isWifiValid = false;
    private boolean isDarkValid = false;
    private boolean isLoginTriggered = false; // Flag to prevent multiple triggers

    // Shake detection
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener shakeEventListener;
    private Sensor lightSensor;
    private SensorEventListener lightEventListener;

    private static final float SHAKE_THRESHOLD = 24.0f; // Adjust as needed
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private static final String SECRET_WIFI_SSID = "Home WIFI";


    // ActivityResultLauncher for QR Scanner
    private final ActivityResultLauncher<Intent> qrScannerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String scannedValue = result.getData().getStringExtra("SCANNED_VALUE");
                    handleQRCodeResult(scannedValue);
                } else {
                    handleQRCodeResult(null);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize View Binding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Check Camera Permissions
        checkCameraPermission();
        checkLocationPermission();

        // Initialize the SensorManager
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        // Initialize accelerometer and proximity sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        // Set up shake detection
        setupShakeDetection();

        // Set up light detection
        setupLightDetection();

        // Get the connectivity and Wi-Fi manager
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        // Set up task listeners
        setupTaskListeners();

        // Set up slider login functionality
        setupSliderLogin();
    }

    @SuppressLint("SetTextI18n")
    private void setupTaskListeners() {
        binding.layoutBarcode.setOnClickListener(v -> {
            binding.instructionText.setText("Scan the QR code to proceed.");
            startQRScanner();
        });

        binding.layoutBiometric.setOnClickListener(v -> {
            binding.instructionText.setText("Authenticate using your biometrics.");
            validateBiometric();
        });

        binding.layoutShake.setOnClickListener(v -> binding.instructionText.setText("Shake the device to proceed."));

        binding.layoutWifi.setOnClickListener(v -> {
            binding.instructionText.setText("Ensure you are connected to the specific Wi-Fi.");
            validateWifiConnection();
        });

        binding.layoutDarkPlace.setOnClickListener(v -> binding.instructionText.setText("Go to darker place in order to process"));
    }
    private void checkLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 102);
        }
    }
    private void setupShakeDetection() {
        // Define a sensor event listener to detect shake gestures
        shakeEventListener = new SensorEventListener() {
            private long lastShakeTime = 0;

            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    float x = event.values[0]; // X-axis acceleration
                    float y = event.values[1]; // Y-axis acceleration
                    float z = event.values[2]; // Z-axis acceleration

                    // Calculate the magnitude of acceleration
                    double magnitude = Math.sqrt(x * x + y * y + z * z);
                    long currentTime = System.currentTimeMillis();

                    // Check if the magnitude exceeds the shake threshold
                    // Add a 1-second cooldown between shakes to prevent false positives
                    if (magnitude > SHAKE_THRESHOLD && (currentTime - lastShakeTime > 1000)) {
                        lastShakeTime = currentTime; // Update last shake time
                        onShakeDetected(); // Trigger shake detected logic
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Not needed for this use case
            }
        };
    }

    @SuppressLint("SetTextI18n")
    private void onShakeDetected() {
        // Update the UI to indicate the shake was successfully detected
        isShakeValid = true;
        binding.layoutShake.setBackgroundResource(R.drawable.circle_green);
        binding.instructionText.setText("Device shaken successfully!");
    }
    private void setupLightDetection() {
        // Define a sensor event listener for the light sensor
        lightEventListener = new SensorEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                    float lightIntensity = event.values[0]; // Retrieve light intensity in lux

                    // If light intensity is below the threshold, it indicates a darker environment
                    if (lightIntensity < 150) { // Threshold for low light; adjust as needed
                        // Mark the task as valid when in a sufficiently dark environment
                        isDarkValid = true;

                        // Update the UI to visually indicate the task is completed
                        binding.layoutDarkPlace.setBackgroundResource(R.drawable.circle_green);
                    } else {
                        // Mark the task as invalid if the light intensity is too high
                        isDarkValid = false;

                        // Update the UI to visually indicate the task is not completed
                        binding.layoutDarkPlace.setBackgroundResource(R.drawable.circle_red);
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // No specific action required for accuracy changes in this use case
            }
        };
    }


    private void setupSliderLogin() {
        // Set the SeekBar change listener
        binding.sliderLogin.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (progress == 100 && !isLoginTriggered) { // Check if the progress is at the end and the action hasn't been triggered yet
                    isLoginTriggered = true; // Lock to prevent multiple triggers

                    if (areAllTasksValid()) { // Check if all tasks are validated
                        Toast.makeText(MainActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();

                    } else {
                        Toast.makeText(MainActivity.this, "Complete all tasks to log in.", Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                // Optional: Add behavior if required when the user starts touching the slider
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                if (seekBar.getProgress() < 100) { // Check if the slider was released before reaching the end
                    Toast.makeText(MainActivity.this, "Hold and slide to the end to login.", Toast.LENGTH_SHORT).show();
                }
                isLoginTriggered = false; // Reset the flag
                binding.sliderLogin.setProgress(4); // Reset the slider progress
            }
        });

        // Customize the slider thumb with a TextView displaying "Login"
        binding.sliderLogin.setThumb(getLoginTextAsDrawable());
    }

    @SuppressLint("SetTextI18n")
    private Drawable getLoginTextAsDrawable() {
        // Create a TextView programmatically to use as a drawable
        TextView textView = new TextView(this);
        textView.setText("Login");
        textView.setTextColor(ContextCompat.getColor(this, android.R.color.white)); // Customize text color
        textView.setTextSize(16); // Customize text size
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(16, 8, 16, 8);
        textView.setBackgroundResource(R.drawable.slider_thumb_background); // Add a background drawable if needed

        // Measure and layout the TextView
        textView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());

        // Convert the TextView to a bitmap drawable
        Bitmap bitmap = Bitmap.createBitmap(textView.getMeasuredWidth(), textView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        textView.draw(canvas);

        return new BitmapDrawable(getResources(), bitmap); // Return the bitmap as a drawable
    }

    private boolean areAllTasksValid() {
        return isBarcodeValid && isBiometricsValid && isShakeValid && isWifiValid && isDarkValid;
    }

    private void startQRScanner() {
        // Send the secret keyword to QRScannerActivity
        Intent intent = new Intent(this, QRScannerActivity.class);
        intent.putExtra("SECRET_KEYWORD", SECRET_KEYWORD);
        qrScannerLauncher.launch(intent); // Launch QRScannerActivity
    }

    @SuppressLint("SetTextI18n")
    private void handleQRCodeResult(String scannedValue) {
        if (SECRET_KEYWORD.equalsIgnoreCase(scannedValue)) {
            isBarcodeValid = true;
            binding.layoutBarcode.setBackgroundResource(R.drawable.circle_green);
            binding.instructionText.setText("QR Code scanned successfully!");
        } else {
            isBarcodeValid = false;
            binding.layoutBarcode.setBackgroundResource(R.drawable.circle_red);
            binding.instructionText.setText(scannedValue == null ? "No QR code detected. Try again." : "Invalid QR code. Try again.");
        }
    }

    @SuppressLint("SetTextI18n")
    private void validateBiometric() {
        // Initialize the BiometricManager to check for biometric authentication support
        BiometricManager biometricManager = BiometricManager.from(this);

        // Check if the device supports strong biometric authentication (e.g., fingerprint, face recognition)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS) {

            // Configure the BiometricPrompt dialog with a title, subtitle, and a cancel button
            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Biometric Authentication") // Title shown on the dialog
                    .setSubtitle("Authenticate using your fingerprint or other biometrics") // Subtitle shown below the title
                    .setNegativeButtonText("Cancel") // Text for the cancel button
                    .build();

            // Create a BiometricPrompt instance for handling authentication
            BiometricPrompt biometricPrompt = new BiometricPrompt(
                    this, // The activity context
                    ContextCompat.getMainExecutor(this), // Ensures callback methods run on the main thread
                    new BiometricPrompt.AuthenticationCallback() { // Callback to handle authentication results

                        // Called when authentication is successful
                        @SuppressLint("SetTextI18n")
                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);

                            // Update the UI to indicate success and set the task as valid
                            isBiometricsValid = true;
                            binding.layoutBiometric.setBackgroundResource(R.drawable.circle_green);
                            binding.instructionText.setText("Biometric validated successfully!");
                        }

                        // Called when an error occurs during authentication (e.g., user cancels)
                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);

                            // Update the UI to indicate an error and set the task as invalid
                            isBiometricsValid = false;
                            binding.layoutBiometric.setBackgroundResource(R.drawable.circle_red);
                            binding.instructionText.setText("Authentication error: " + errString);
                        }

                        // Called when authentication fails (e.g., biometric doesn't match)
                        @SuppressLint("SetTextI18n")
                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();

                            // Update the UI to indicate failure and set the task as invalid
                            isBiometricsValid = false;
                            binding.layoutBiometric.setBackgroundResource(R.drawable.circle_red);
                            binding.instructionText.setText("Biometric not recognized. Try again.");
                        }
                    }
            );

            // Start the biometric authentication process using the configured prompt
            biometricPrompt.authenticate(promptInfo);

        } else {
            // If the device does not support biometric authentication or no biometrics are enrolled:

            // Set the biometric task as invalid
            isBiometricsValid = false;

            // Update the UI to indicate lack of biometric support or enrollment
            binding.layoutBiometric.setBackgroundResource(R.drawable.circle_red);
            binding.instructionText.setText("No biometric hardware or enrolled biometrics found.");
        }
    }

    @SuppressLint("SetTextI18n")
    private void validateWifiConnection() {
        if (connectivityManager != null && wifiManager != null) {
            // Get the active network
            android.net.Network network = connectivityManager.getActiveNetwork();
            if (network != null) {
                // Check if the active network has Wi-Fi capabilities
                android.net.NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    // Get the current Wi-Fi SSID
                    String currentSSID = wifiManager.getConnectionInfo().getSSID();
                    currentSSID = currentSSID.replace("\"", ""); // Remove quotes from SSID if present

                    if (SECRET_WIFI_SSID.equals(currentSSID)) {
                        // Successfully connected to the correct network
                        isWifiValid = true;
                        binding.layoutWifi.setBackgroundResource(R.drawable.circle_green);
                        binding.instructionText.setText("Connected to SecretWifi!");
                    } else {
                        // Connected to Wi-Fi but not the correct network
                        isWifiValid = false;
                        binding.layoutWifi.setBackgroundResource(R.drawable.circle_red);
                        binding.instructionText.setText("Connected to Wi-Fi but not SecretWifi. Current network: " + currentSSID);
                    }
                } else {
                    // Not connected to Wi-Fi
                    isWifiValid = false;
                    binding.layoutWifi.setBackgroundResource(R.drawable.circle_red);
                    binding.instructionText.setText("Not connected to Wi-Fi.");
                }
            } else {
                // No active network
                isWifiValid = false;
                binding.layoutWifi.setBackgroundResource(R.drawable.circle_red);
                binding.instructionText.setText("Not connected to any network.");
            }
        } else {
            // Unable to check Wi-Fi status
            isWifiValid = false;
            binding.layoutWifi.setBackgroundResource(R.drawable.circle_red);
            binding.instructionText.setText("Unable to verify Wi-Fi connection.");
        }
    }


    @SuppressLint("SetTextI18n")
    private void validateDarkPlace() {
        binding.instructionText.setText("Move to a darker place.");
    }

    private void checkCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 101);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            validateWifiConnection(); // Retry fetching SSID if permission granted
        } else {
            Toast.makeText(this, "Location permission required to validate Wi-Fi connection.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register listeners
        if (accelerometer != null) {
            sensorManager.registerListener(shakeEventListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (lightSensor != null) {
            sensorManager.registerListener(lightEventListener, lightSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister listeners
        sensorManager.unregisterListener(shakeEventListener);
        if (lightSensor != null) {
            sensorManager.unregisterListener(lightEventListener);
        }
    }
}
