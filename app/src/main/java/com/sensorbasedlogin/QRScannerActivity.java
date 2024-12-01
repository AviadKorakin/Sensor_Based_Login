package com.sensorbasedlogin;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.sensorbasedlogin.databinding.ActivityQrscannerBinding;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScannerActivity extends AppCompatActivity {

    private ActivityQrscannerBinding binding; // View Binding for layout elements
    private final ExecutorService cameraExecutor = Executors.newFixedThreadPool(2); // Separate thread pool for camera processing
    private BarcodeScanner barcodeScanner; // ML Kit barcode scanner instance
    private boolean isCooldown = false; // Prevents processing too frequently
    private boolean isFlashlightOn = false; // Tracks flashlight state
    private boolean isProcessingFrame = false; // Ensures only one frame is processed at a time
    private CameraControl cameraControl; // Used to toggle flashlight on/off
    private String secretKeyword; // The secret keyword passed from MainActivity
    private final Handler handler = new Handler(); // Handler for delayed actions (e.g., UI reset)
    private InputImage image;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve the secret keyword from the Intent sent by MainActivity
        secretKeyword = getIntent().getStringExtra("SECRET_KEYWORD");

        // Initialize View Binding for accessing layout elements
        binding = ActivityQrscannerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize the Barcode Scanner using ML Kit
        barcodeScanner = BarcodeScanning.getClient();

        // Start the camera and bind preview/analyzer
        startCamera();

        // Set up the flashlight button to toggle flashlight state
        binding.flashlightButton.setOnClickListener(v -> toggleFlashlight());
    }

    /**
     * Initializes and starts the camera, binds preview and analyzer to lifecycle.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Camera configuration: use the back-facing camera
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Configure the camera preview use case
                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider()); // Links preview to UI element

                // Configure the ImageAnalysis use case for barcode scanning
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Only analyze the latest frame
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Enforce YUV format for compatibility
                        .build();

                // Set up the analyzer to process frames for barcodes
                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isProcessingFrame && !isCooldown) {
                        isProcessingFrame = true; // Lock frame processing to prevent overlap
                        InputImage croppedImage = cropToOverlay(
                                InputImage.fromMediaImage(Objects.requireNonNull(imageProxy.getImage()), imageProxy.getImageInfo().getRotationDegrees()),
                                imageProxy
                        );
                        if (croppedImage != null) {
                            scanBarcode(croppedImage, () -> isProcessingFrame = false); // Process barcode and release lock
                        }
                    }
                    imageProxy.close(); // Release the frame for reuse
                });

                // Bind all use cases (preview + analysis) to the camera lifecycle
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                // Store the CameraControl instance for flashlight control
                cameraControl = camera.getCameraControl();

            } catch (Exception e) {
                Log.e("CameraX", "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Toggles the flashlight state (on/off) when the flashlight button is clicked.
     */
    private void toggleFlashlight() {
        if (cameraControl != null) {
            binding.flashlightButton.setEnabled(false); // Temporarily disable button
            isFlashlightOn = !isFlashlightOn;
            cameraControl.enableTorch(isFlashlightOn).addListener(() -> runOnUiThread(() -> {
                binding.flashlightButton.setEnabled(true); // Re-enable button
                // Update button color based on flashlight state
                binding.flashlightButton.setColorFilter(
                        isFlashlightOn ? ContextCompat.getColor(this, android.R.color.holo_orange_light)
                                : ContextCompat.getColor(this, android.R.color.white)
                );
            }), ContextCompat.getMainExecutor(this));
        }
    }

    /**
     * Processes a single frame to detect barcodes using ML Kit.
     *
     * @param image       The input image to process.
     * @param onComplete  Callback to execute after processing finishes.
     */
    private void scanBarcode(InputImage image, Runnable onComplete) {
        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String rawValue = barcode.getRawValue();
                        if (rawValue != null && !rawValue.isEmpty()) {
                            handleScannedResult(rawValue); // Pass the barcode value for further handling
                            onComplete.run(); // Release lock
                            return;
                        }
                    }
                    onComplete.run(); // Release lock if no valid barcode is found
                })
                .addOnFailureListener(e -> {
                    Log.e("MLKit", "Barcode scanning failed", e);
                    onComplete.run(); // Release lock in case of failure
                });
    }

    /**
     * Handles the result of a scanned barcode and updates the UI based on the secret keyword match.
     *
     * @param scannedValue The value of the scanned barcode.
     */
    private void handleScannedResult(String scannedValue) {
        runOnUiThread(() -> {
            isCooldown = true; // Enable cooldown to prevent rapid re-scanning

            // Display the scanned value in the overlay
            binding.scannedTextView.setVisibility(View.VISIBLE);
            binding.scannedTextView.setText(scannedValue);

            // Check if the scanned value matches the secret keyword
            if (secretKeyword.equalsIgnoreCase(scannedValue)) {
                binding.overlayFrame.setBackgroundResource(R.drawable.qr_scanner_frame_green); // Highlight frame in green
                handler.postDelayed(() -> {
                    // Return the result to the parent activity and close the scanner
                    setResult(RESULT_OK, new Intent().putExtra("SCANNED_VALUE", scannedValue));
                    finish();
                }, 1000);
            } else {
                binding.overlayFrame.setBackgroundResource(R.drawable.qr_scanner_frame_red); // Highlight frame in red
                handler.postDelayed(() -> {
                    resetUI(); // Reset UI for the next scan
                    isCooldown = false; // Disable cooldown
                }, 1000);
            }
        });
    }

    /**
     * Resets the UI to its initial state after an invalid scan or timeout.
     */
    private void resetUI() {
        binding.overlayFrame.setBackgroundResource(R.drawable.qr_scanner_frame); // Reset frame color
        binding.scannedTextView.setVisibility(View.INVISIBLE); // Hide scanned text
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null); // Cancel any pending callbacks
        cameraExecutor.shutdown(); // Shut down the camera executor to free resources
        binding = null; // Clear the binding to prevent memory leaks
    }

    /**
     * Crops the input image to match the dimensions of the overlay frame.
     *
     * @param image       The full camera frame image.
     * @param imageProxy  The ImageProxy representing the current frame.
     * @return A cropped InputImage matching the overlay area.
     */
    private InputImage cropToOverlay(InputImage image, ImageProxy imageProxy) {
        this.image = image;
        int[] location = new int[2];
        binding.overlayFrame.getLocationOnScreen(location); // Get overlay position on screen

        int overlayX = location[0];
        int overlayY = location[1];
        int overlayWidth = binding.overlayFrame.getWidth();
        int overlayHeight = binding.overlayFrame.getHeight();

        // Scale overlay coordinates to match the camera frame dimensions
        int imageWidth = imageProxy.getWidth();
        int imageHeight = imageProxy.getHeight();
        float widthRatio = (float) imageWidth / binding.previewView.getWidth();
        float heightRatio = (float) imageHeight / binding.previewView.getHeight();

        int cropX = (int) (overlayX * widthRatio);
        int cropY = (int) (overlayY * heightRatio);
        int cropWidth = (int) (overlayWidth * widthRatio);
        int cropHeight = (int) (overlayHeight * heightRatio);

        // Convert the ImageProxy to a Bitmap
        Bitmap bitmap = imageProxyToBitmap(imageProxy);

        if (bitmap != null) {
            // Crop the Bitmap to the overlay area
            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight);
            return InputImage.fromBitmap(croppedBitmap, 0); // Return the cropped InputImage
        } else {
            Log.e("CropToOverlay", "Error converting ImageProxy to Bitmap");
            return null;
        }
    }

    /**
     * Converts an ImageProxy to a Bitmap.
     *
     * @param imageProxy The ImageProxy to convert.
     * @return A Bitmap representation of the ImageProxy.
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // Copy Y, U, and V buffers to NV21 array
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        // Convert NV21 to Bitmap
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }
}
