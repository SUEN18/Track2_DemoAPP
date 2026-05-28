package ai.lpcv.actionrecognition;

import android.graphics.Bitmap;
import android.util.Log;
import java.util.List;

public class ActionRecognitionEngine {
    private static final String TAG = "ActionRecognitionEngine";

    static {
        System.loadLibrary("action_recognition_app");
    }

    private long nativeHandle;

    public ActionRecognitionEngine() {
        nativeHandle = nativeInit();
    }

    public void release() {
        nativeRelease(nativeHandle);
        nativeHandle = 0;
    }

    public boolean loadModel(String modelPath, String nativeLibPath, String htpConfigPath) {
        return nativeLoadModel(nativeHandle, modelPath, nativeLibPath, htpConfigPath);
    }

    public float[] detectAction(float[] inputTensors) {
        return nativeDetectAction(nativeHandle, inputTensors);
    }

    private native long nativeInit();
    private native void nativeRelease(long handle);
    private native boolean nativeLoadModel(long handle, String modelPath, String nativeLibPath, String htpConfigPath);
    private native float[] nativeDetectAction(long handle, float[] inputTensors);
}
