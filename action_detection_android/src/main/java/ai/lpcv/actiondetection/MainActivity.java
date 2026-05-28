package ai.lpcv.actiondetection;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.text.style.RelativeSizeSpan;
import android.graphics.Typeface;
import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import android.content.Intent;
import ai.lpcv.actiondetection.tutorial.TutorialActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public static ActionDetectionEngine engine;
    private TextView tvStatus;
    private TextView tvResult;
    private ImageView ivPreview;
    private Button btnSelectVideo;
    private Button btnRealTime;
    private JSONArray labels;
    
    private List<Bitmap> previewFrames;
    private int currentFrameIdx = 0;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private Runnable previewRunnable;

    private final ActivityResultLauncher<String> videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    processVideo(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        ivPreview = findViewById(R.id.ivPreview);
        btnSelectVideo = findViewById(R.id.btnSelectVideo);
        btnRealTime = findViewById(R.id.btnRealTime);
        Button btnTutorial = findViewById(R.id.btnTutorial);

        btnSelectVideo.setOnClickListener(v -> videoPickerLauncher.launch("video/*"));
        btnTutorial.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TutorialActivity.class);
            startActivity(intent);
        });

        btnRealTime.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, RealTimeActivity.class);
            startActivity(intent);
        });

        loadLabels();
        initEngine();
    }

    private void loadLabels() {
        try (InputStream is = getAssets().open("class_labels.json")) {
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            if (read != -1) {
                String json = new String(buffer, StandardCharsets.UTF_8);
                labels = new JSONArray(json);
                Log.i(TAG, "Loaded " + labels.length() + " labels");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load labels", e);
        }
    }

    private void initEngine() {
        engine = new ActionDetectionEngine();
        String modelPath = copyAssetToFile("models/model.dlc");
        String nativeLibPath = getApplicationInfo().nativeLibraryDir;
        
        tvStatus.setText("Status: Loading model...");
        new Thread(() -> {
            boolean success = engine.loadModel(modelPath, nativeLibPath, "");
            runOnUiThread(() -> {
                if (success) {
                    tvStatus.setText("Status: Model loaded");
                    btnSelectVideo.setEnabled(true);
                    btnRealTime.setEnabled(true);
                } else {
                    tvStatus.setText("Status: Model load failed");
                }
            });
        }).start();
    }

    private String copyAssetToFile(String assetPath) {
        File file = new File(getFilesDir(), new File(assetPath).getName());
        try (InputStream is = getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) != -1) {
                fos.write(buffer, 0, length);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying asset", e);
        }
        return file.getAbsolutePath();
    }

    private void processVideo(Uri uri) {
        stopPreview();
        tvStatus.setText("Status: Processing video...");
        tvResult.setText("");
        ivPreview.setVisibility(View.GONE);
        
        new Thread(() -> {
            VideoProcessor.ProcessingResult result = VideoProcessor.processVideo(this, uri);
            if (result == null || result.tensor == null) {
                runOnUiThread(() -> tvStatus.setText("Status: Video processing failed"));
                return;
            }

            runOnUiThread(() -> {
                if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
                tvStatus.setText("Status: Running model...");
                this.previewFrames = result.bitmaps;
                startPreview();
            });

            long startTime = System.currentTimeMillis();
            float[] output = engine.detectAction(result.tensor);
            long endTime = System.currentTimeMillis();
            long inferenceTime = endTime - startTime;

            runOnUiThread(() -> {
                if (MainActivity.this.isFinishing() || MainActivity.this.isDestroyed()) return;
                if (output != null) {
                    tvStatus.setText(String.format(Locale.US, "Status: Inference complete\nInference time: %d ms", inferenceTime));
                    displayResults(output);
                } else {
                    tvStatus.setText("Status: Inference failed");
                }
            });
        }).start();
    }

    private void startPreview() {
        if (previewFrames == null || previewFrames.isEmpty()) return;
        ivPreview.setVisibility(View.VISIBLE);
        currentFrameIdx = 0;
        
        // Remove any existing callbacks just in case
        if (previewRunnable != null) {
            previewHandler.removeCallbacks(previewRunnable);
        }

        previewRunnable = new Runnable() {
            @Override
            public void run() {
                if (previewFrames == null || currentFrameIdx >= previewFrames.size()) {
                    return;
                }
                
                ivPreview.setImageBitmap(previewFrames.get(currentFrameIdx));
                currentFrameIdx = (currentFrameIdx + 1) % previewFrames.size();
                
                // Use the field reference to ensure we can stop it later
                previewHandler.postDelayed(this, 250);
            }
        };
        previewHandler.post(previewRunnable);
    }

    private void stopPreview() {
        if (previewRunnable != null) {
            previewHandler.removeCallbacks(previewRunnable);
            previewRunnable = null;
        }
    }

    private void displayResults(float[] output) {
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
        tvResult.setText(ssb);
    }

    private String getLabel(int index) {
        if (labels == null) return "Class " + (index + 1);
        // index starts at 1 means number n maps to labels[n-1].
        // Our loop 'i' is 0-indexed. So class number (i+1) maps to labels[i].
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
        super.onDestroy();
        stopPreview();
        if (engine != null) {
            engine.release();
        }
    }
}
