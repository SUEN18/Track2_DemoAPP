# LPCVC 2026 Track 2 Demo App

This repository contains the demonstration application for the **Track 2 model of the** [**2026 IEEE Low-Power Computer Vision Challenge (LPCVC)**](lpcv.ai) running on physical mobile edge devices. The system has been validated on **Android 16** and **Samsung Galaxy S25 & S26** hardware.

---

## 🚀 Getting Started

You can set up the application using one of the following two paths:

### Option 1: Direct APK Installation (Recommended for quick testing)
* Download the compiled release APK directly from [here](https://drive.google.com/file/d/1jzuKTN39_cUJEygZAIcDVaONEy7DgIxx/view?usp=drive_link).
* Install the APK onto your supported Android device.

### Option 2: Build from Source
If you wish to modify the application, you can clone this repository and build the `action_recognition_android` module locally.

#### Prerequisites
1. **Qualcomm AI Engine Direct SDK (QAIRT):** Before opening the project in Android Studio, please follow the official environment setup guides found in the [Qualcomm Neural Processing SDK Documentation](https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-2/setup.html?product=1601111740010412).
2. **Path Configuration:** Define your local QAIRT path inside `build.gradle` where indicated:
```groovy
   def qnnSDKLocalPath = System.getenv("QAIRT_SDK_ROOT") ?: "C:\\Your\\Path\\To\\QAIRT\\qairt\\2.45.0.260326"
```

---

## 🛠️ App Functions & Modes

The application features three main operational modes tailored for testing action detection on the edge:

### 1. Action Tutorials

* **Comprehensive Directory:** Displays all 92 valid action classes sorted alphabetically.
* **Low-Overhead Previews:** Shows a low-definition, 16-frame preview loop of each action as a thumbnail. Tapping any item pulls up the full-resolution detailed video.
* **Pagination:** Seamlessly navigate through the asset library across 8 interactive pages.

### 2. Live Inference Mode

* **Live Camera Stream:** Actively pulls live input frames from the device's camera, with the option to toggle between the rear and front camera.
* **Frame Sampling:** Frames are systematically captured at **4 FPS**. The 16 most recent frames are sent dynamically to the model pipeline once every second.
* **Predictions:** Displays the model inference time alongside the top-3 classified categories.

### 3. Video File Inference Mode

* **File Picker:** Allows the user to select any local video file for inference.
* **Smart Uniform Sampling:**
* *Videos < 4 seconds:* 16 frames are uniformly sampled across the total duration.
* *Videos ≥ 4 seconds:* Grabs the first 16 frames sampled strictly at 4 FPS.


* **Visual Status Pipeline:** Real-time text updates guide you through the process:
`Processing Video` -> `Running Model` -> `Inference Complete`
* **Performance Telemetry:** Displays the model inference time alongside the top-3 classified categories.

---

## 📂 Project Assets & Modification

The app comes pre-packaged with baseline assets configured out-of-the-box using tools adapted from the official [lpcvai/26LPCVC_Track2_Sample_Solution](https://github.com/lpcvai/26LPCVC_Track2_Sample_Solution) pipeline:

* 📄 `assets/class_labels.json`
Contains the 92 alphabetized target action labels designated for LPCVC 2026 Track 2.
* 🤖 `assets/models/model.dlc`
The converted Deep Learning Container (DLC) format optimized from ONNX for Snapdragon execution. See `model_conversion.py` for conversion scripts. The model is adapted from the solution of the Track 2 first place winner, EfficientAI. Link to their repo is [here](https://github.com/shuangtianxiaoye/LPCV-Track2-EfficientAI). 
* 📋 `assets/tutorial/label_videos.json`
A JSON lookup table linking label names to specific asset file paths (`select_example_videos.py`).
* 🎥 `assets/tutorial/videos/`
A curated set of randomly selected evaluation split videos from the [**QEVD dataset**](https://www.qualcomm.com/developer/software/qevd-dataset) (1 unique action video per folder item).
* `assets/tutorial/previews/`
Pre-rendered, 16-frame downsampled lightweight representations utilized for UI thumbnails (`create_example_thumbnails.py`).

---

## App Demonstration

VIDEO_ID_HERE

---

## 📚 References
- [1] IEEE Low Power Computer Vision Challenge Organizing Committee. IEEE Low Power Computer Vision Challenge. Annual competition series on low power computer vision. Available: https://lpcv.ai/

- [2] S. Panchal, A. Bhattacharyya, G. Berger, A. Mercier, C. Böhm, F. Dietrichkeit, R. Pourreza, X. Li, P. Maden, M. Lee, M. Todorovich, I. Bax, and R. Memisevic, "Live Fitness Coaching as a Testbed for Situated Interaction," 2024.