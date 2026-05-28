"""
QEVD Action Video Sampler and Label Mapper

This script constructs an example dataset from the QEVD action recognition dataset.

It performs the following steps:
1. Traverses a directory structured by action classes (action_1, action_2, ...)
2. Randomly selects one video from each class
3. Copies selected videos into a flat directory for UI/demo usage
4. Generates a JSON mapping from action label → selected video filename

Inputs:
- Source dataset root (structured by action class folders)

Outputs:
- example_actions/ (sampled videos)
- label_videos.json (label → video mapping)
"""

import json
import random
import shutil
from pathlib import Path

# =============================================================================
# USER CONFIGURATION
# =============================================================================

# Parent directory containing action_1, action_2, ...
# This assumes that the QEVD dataset has been downloaded and formatted according
# to the instructions in https://github.com/lpcvai/26LPCVC_Track2_Sample_Solution
videos_dir = Path("./train")

# Output directory
output_dir = Path("./example_actions")
output_dir.mkdir(parents=True, exist_ok=True)

# =============================================================================


# Supported video extensions
VIDEO_EXTENSIONS = {".mp4"}

# JSON mappings
label_videos = {}

# Iterate through subdirectories
for subdir in sorted(videos_dir.iterdir()):
    if not subdir.is_dir():
        continue

    # Get all video files in this subdirectory
    video_files = [
        f for f in subdir.iterdir()
        if f.is_file() and f.suffix.lower() in VIDEO_EXTENSIONS
    ]

    if not video_files:
        print(f"Skipping {subdir.name}: no videos found")
        continue

    # Select a random video
    selected_video = random.choice(video_files)

    # Destination path
    dest_path = output_dir / selected_video.name

    # Handle duplicate filenames
    if dest_path.exists():
        stem = selected_video.stem
        suffix = selected_video.suffix
        counter = 1

        while dest_path.exists():
            new_name = f"{stem}_{counter}{suffix}"
            dest_path = output_dir / new_name
            counter += 1

    # Copy video
    shutil.copy2(selected_video, dest_path)

    # Update mappings
    video_name = dest_path.name
    label_name = subdir.name.replace("_", " ")
    label_videos[label_name] = video_name

    print(f"Copied: {selected_video} -> {dest_path}")

# Save JSON
with open("label_videos.json", "w", encoding="utf-8") as f:
    json.dump(label_videos, f, indent=4)

print("Done!")
print("Saved:")
print(" - label_videos.json")
