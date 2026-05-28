import qai_hub
import onnx
import os
import sys
import numpy as np

# =============================================================================
# USER CONFIGURATION
# =============================================================================

ONNX_DIR = "job_jxxxxxxxx_qdq_onnx"
VIDEO_ONNX_NAME = "model.onnx"

# Galaxy S25 device 
DEVICE_NAME = "Samsung Galaxy S25"

# Input shape
BATCH = 1
C = 3
T = 16
H = 112
W = 112

# Input file (your preprocessed tensor)
INPUT_NPY = "output.npy"

# =============================================================================


def compile_model(model, device, input_specs):
    """Compile ONNX → DLC using AI Hub"""
    compile_job = qai_hub.submit_compile_job(
        model=model,
        device=device,
        input_specs=input_specs,
        options="--target_runtime qnn_dlc"
    )
    return compile_job


def run_inference(model, device, input_tensor):
    """Run inference job on AI Hub"""
    dataset = qai_hub.upload_dataset({
        "video": [input_tensor]
    })

    inference_job = qai_hub.submit_inference_job(
        model=model,
        device=device,
        inputs=dataset
    )
    return inference_job


def main():

    if not ONNX_DIR:
        print("Error: ONNX_DIR not set")
        sys.exit(1)

    onnx_path = os.path.join(ONNX_DIR, VIDEO_ONNX_NAME)

    if not os.path.exists(onnx_path):
        print(f"ONNX not found: {onnx_path}")
        sys.exit(1)

    # =========================
    # LOAD ONNX
    # =========================
    print("\nLoading ONNX model...")
    
    ## Option 1 - load ONNX from local file and validate it
    model = onnx.load(onnx_path)
    try:
        onnx.checker.check_model(model)
        print("ONNX valid ✅")
    except Exception as e:
        print("ONNX invalid ❌")
        print(e)
        sys.exit(1)
    
    ## Option 2 - directly load ONNX from AI Hub (if you already uploaded it there)
    # model = qai_hub.get_model("mxxxxxxxx")

    # =========================
    # DEVICE
    # =========================
    device = qai_hub.Device(DEVICE_NAME)

    # =========================
    # INPUT SPEC
    # =========================
    input_specs = {
        "video": ((BATCH, C, T, H, W), "float32")
    }

    # =========================
    # COMPILE TO DLC
    # =========================
    print("\nCompiling ONNX → DLC on AI Hub...")

    compile_job = compile_model(model, device, input_specs)

    print("Compile Job ID:", compile_job.job_id)
    compile_job.wait()

    target_model = compile_job.get_target_model()

    # save DLC locally
    target_model.download("model.dlc")

    print("DLC saved as model.dlc")

    # =========================
    # LOAD INPUT
    # =========================
    print("\nLoading input tensor...")

    x = np.load(INPUT_NPY).astype(np.float32)
    x = np.ascontiguousarray(x)

    print("Input shape:", x.shape)
    print("dtype:", x.dtype)

    # =========================
    # RUN INFERENCE
    # =========================
    print(f"\nRunning inference on {DEVICE_NAME}")

    inference_job = run_inference(target_model, device, x)

    print("Inference Job ID:", inference_job.job_id)
    inference_job.wait()

    # =========================
    # OUTPUT
    # =========================
    results = inference_job.download_output_data()

    print("\n===== OUTPUTS =====")

    for name, val in results.items():

        arr = np.array(val)

        print(f"\nTensor: {name}")
        print("Shape:", arr.shape)

        flat = arr.flatten()
        top5 = np.argsort(flat)[-5:][::-1]

        print("Top-5:")
        for i in top5:
            print(f"Class {i}: {flat[i]}")

        print("Predicted class:", np.argmax(flat))


if __name__ == "__main__":
    main()
