package ai.lpcv.actionrecognition;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.text.style.RelativeSizeSpan;
import android.graphics.Typeface;
import org.json.JSONArray;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RealTimeActivity extends AppCompatActivity {
    private static final String TAG = "RealTimeActivity";
    private static final int PERMISSION_REQUEST_CAMERA = 1001;

    private PreviewView viewFinder;
    private TextView tvResult;
    private TextView tvInferenceTime;
    private JSONArray labels;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ProcessCameraProvider cameraProvider;

    private ExecutorService inferenceExecutor;
    private final LinkedList<Bitmap> frameBuffer = new LinkedList<>();
    private final Object bufferLock = new Object();

    private static final long FRAME_INTERVAL_MS = 250; // 4 FPS
    private static final long INFERENCE_INTERVAL_MS = 1000; // 1 second
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            captureFrame();
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    private long lastInferenceTimestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_real_time);

        viewFinder = findViewById(R.id.viewFinder);
        tvResult = findViewById(R.id.tvResult);
        tvInferenceTime = findViewById(R.id.tvInferenceTime);
        ImageButton btnClose = findViewById(R.id.btnClose);
        ImageButton btnFlipCamera = findViewById(R.id.btnFlipCamera);

        btnClose.setOnClickListener(v -> finish());
        btnFlipCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? 
                         CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        inferenceExecutor = Executors.newSingleThreadExecutor();

        loadLabels();
        
        if (MainActivity.engine == null) {
            Toast.makeText(this, "Model not loaded", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
        }
    }

    private void loadLabels() {
        try (InputStream is = getAssets().open("class_labels.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            int readCount = is.read(buffer);
            if (readCount != -1) {
                String json = new String(buffer, StandardCharsets.UTF_8);
                labels = new JSONArray(json);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load labels", e);
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);
                
                mainHandler.removeCallbacks(captureRunnable);
                mainHandler.post(captureRunnable);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureFrame() {
        Bitmap bitmap = viewFinder.getBitmap();
        if (bitmap != null) {
            // viewFinder is square, so bitmap should be approximately square. 
            // Scaling to 112x112 directly since we want square input for model.
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, 112, 112, true);
            
            synchronized (bufferLock) {
                frameBuffer.addLast(resized);
                if (frameBuffer.size() > 16) {
                    Bitmap removed = frameBuffer.removeFirst();
                    removed.recycle();
                }
                
                long currentTime = System.currentTimeMillis();
                if (frameBuffer.size() == 16 && (currentTime - lastInferenceTimestamp >= INFERENCE_INTERVAL_MS)) {
                    lastInferenceTimestamp = currentTime;
                    final List<Bitmap> framesToProcess = new ArrayList<>(frameBuffer);
                    inferenceExecutor.execute(() -> runInference(framesToProcess));
                }
            }
        }
    }

    private void runInference(List<Bitmap> frames) {
        int CROP_SIZE = 112;
        int framePixels = CROP_SIZE * CROP_SIZE;
        float[] tensor = new float[3 * 16 * framePixels];
        int[] pixels = new int[framePixels];
        int planeSize = 16 * framePixels;

        for (int i = 0; i < 16; i++) {
            Bitmap frame = frames.get(i);
            
            // Frame is already 112x112
            frame.getPixels(pixels, 0, CROP_SIZE, 0, 0, CROP_SIZE, CROP_SIZE);

            int rBase = i * framePixels;
            int gBase = planeSize + i * framePixels;
            int bBase = 2 * planeSize + i * framePixels;

            float inv255 = 1.0f / 255.0f;
            for (int j = 0; j < framePixels; j++) {
                int pixel = pixels[j];
                tensor[rBase + j] = ((pixel >> 16) & 0xFF) * inv255;
                tensor[gBase + j] = ((pixel >> 8) & 0xFF) * inv255;
                tensor[bBase + j] = (pixel & 0xFF) * inv255;
            }
        }

        long startTime = System.currentTimeMillis();
        float[] output = MainActivity.engine.detectAction(tensor);
        long endTime = System.currentTimeMillis();
        final long inferenceTime = endTime - startTime;

        if (output != null) {
            displayResults(output, inferenceTime);
        }
    }

    private void displayResults(float[] output, long inferenceTime) {
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < output.length; i++) {
            String label = getLabel(i);
            predictions.add(new Prediction(label, output[i]));
        }

        Collections.sort(predictions, (p1, p2) -> Float.compare(p2.score, p1.score));

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (int i = 0; i < Math.min(3, predictions.size()); i++) {
            Prediction p = predictions.get(i);
            if (i == 0) {
                int start = ssb.length();
                ssb.append("#1 ").append(p.label.toUpperCase()).append("\n");
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), 0);
                ssb.setSpan(new RelativeSizeSpan(1.2f), start, ssb.length(), 0);
                ssb.append(String.format(Locale.US, "Score: %.4f\n\n", p.score));
            } else {
                ssb.append(String.format(Locale.US, "#%d %s: %.4f\n", i + 1, p.label, p.score));
            }
        }
        
        runOnUiThread(() -> {
            tvResult.setText(ssb);
            tvInferenceTime.setText(String.format(Locale.US, "Inference Time: %d ms", inferenceTime));
        });
    }

    private String getLabel(int index) {
        if (labels == null) return "Class " + (index + 1);
        return labels.optString(index, "Class " + (index + 1));
    }

    private static class Prediction {
        String label;
        float score;

        Prediction(String label, float score) {
            this.label = label;
            this.score = score;
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(captureRunnable);
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdown();
        }
        // Don't release MainActivity.engine here!
        super.onDestroy();
    }
}
