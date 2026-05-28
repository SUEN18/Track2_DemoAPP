package ai.lpcv.actionrecognition;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class VideoProcessor {
    private static final String TAG = "VideoProcessor";
    public static final int CLIP_LEN = 16;
    private static final int FRAME_RATE = 4; // Target FPS
    private static final int RESIZE_H = 128;
    private static final int RESIZE_W = 171;
    private static final int CROP_SIZE = 112;

    public static class ProcessingResult {
        public float[] tensor;
        public List<Bitmap> bitmaps;

        public ProcessingResult(float[] tensor, List<Bitmap> bitmaps) {
            this.tensor = tensor;
            this.bitmaps = bitmaps;
        }
    }

    public static ProcessingResult processVideo(Context context, Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

            // Model expects (C, T, H, W) = (3, 16, 112, 112)
            int framePixels = CROP_SIZE * CROP_SIZE;
            int totalTensorSize = 3 * CLIP_LEN * framePixels;
            float[] allFramesData = new float[totalTensorSize];
            List<Bitmap> bitmaps = new ArrayList<>(CLIP_LEN);
            
            // Reuse pixel buffer for efficiency
            int[] pixels = new int[framePixels];
            
            // Pre-calculate plane size for indexing (C, T, H, W)
            int planeSize = CLIP_LEN * framePixels;

            Bitmap lastValidFrame = null;
            for (int i = 0; i < CLIP_LEN; i++) {
                long timeUs = i * (1000000 / FRAME_RATE);
                if (durationMs > 0 && timeUs > durationMs * 1000) {
                    timeUs = (durationMs - 1) * 1000;
                }
                
                // Use getScaledFrameAtTime (API 27+) to resize during decoding.
                Bitmap frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, RESIZE_W, RESIZE_H);
                
                // Fallback mechanism
                if (frame == null) {
                    frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, RESIZE_W, RESIZE_H);
                }
                if (frame == null) {
                    frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_NEXT_SYNC, RESIZE_W, RESIZE_H);
                }
                
                // Extra safety: ensure frame is large enough for crop
                if (frame != null && (frame.getWidth() < CROP_SIZE || frame.getHeight() < CROP_SIZE)) {
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, RESIZE_W, RESIZE_H, true);
                    if (scaled != frame) {
                        frame.recycle();
                        frame = scaled;
                    }
                }

                if (frame == null && lastValidFrame != null) {
                    frame = lastValidFrame;
                }

                if (frame == null) {
                    // Last resort: try non-scaled frame
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame != null) {
                        Bitmap scaled = Bitmap.createScaledBitmap(frame, RESIZE_W, RESIZE_H, true);
                        frame.recycle();
                        frame = scaled;
                    }
                }

                if (frame == null) {
                    Log.e(TAG, "Failed to get any frame at " + timeUs);
                    continue;
                }
                
                lastValidFrame = frame;
                bitmaps.add(frame);
                
                addFrameToTensorOptimized(frame, allFramesData, i, pixels, planeSize, framePixels);
            }
            
            return new ProcessingResult(allFramesData, bitmaps);

        } catch (Exception e) {
            Log.e(TAG, "Error processing video", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private static void addFrameToTensorOptimized(Bitmap bitmap, float[] tensor, int frameIdx, int[] pixels, int planeSize, int frameSize) {
        // Model expects CROP_SIZE x CROP_SIZE center crop
        int startX = Math.max(0, (bitmap.getWidth() - CROP_SIZE) / 2);
        int startY = Math.max(0, (bitmap.getHeight() - CROP_SIZE) / 2);
        
        // Fast batch pixel retrieval
        bitmap.getPixels(pixels, 0, CROP_SIZE, startX, startY, CROP_SIZE, CROP_SIZE);
        
        // Offset for (C, T, H, W) indexing
        int rBase = frameIdx * frameSize;
        int gBase = planeSize + frameIdx * frameSize;
        int bBase = 2 * planeSize + frameIdx * frameSize;
        
        float inv255 = 1.0f / 255.0f;
        for (int i = 0; i < frameSize; i++) {
            int pixel = pixels[i];
            tensor[rBase + i] = ((pixel >> 16) & 0xFF) * inv255;
            tensor[gBase + i] = ((pixel >> 8) & 0xFF) * inv255;
            tensor[bBase + i] = (pixel & 0xFF) * inv255;
        }
    }
}
