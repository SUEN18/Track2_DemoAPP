from pathlib import Path
import math
import torch
import torchvision.transforms as transforms
from torchvision.io import read_video
from PIL import Image

# =============================================================================
# USER CONFIGURATION
# =============================================================================

# Directory containing the unique action videos
# may be selected using `select_example_videos.py`
DIR_NAME = Path("example_actions")

# path to save thumbnails to
OUTPUT_DIR = Path(f"thumbnails_{DIR_NAME.name}")

# =============================================================================


def sample_frames(video_path: Path, num_frames: int = 16) -> torch.Tensor:
    """
    Uniformly sample 16 frames based on temporal criteria:
      1. > 4 seconds: Grab the first 4 seconds of frames, then sample uniformly.
      2. 2 to 4 seconds: Uniformly sample from the entire video.
      3. < 2 seconds: Duplicate the video (looping), then sample uniformly.
    
    Returns: (T, C, H, W)
    """
    video, _, info = read_video(
        str(video_path),
        pts_unit="sec",
        output_format="THWC",
    )

    total_frames = video.shape[0]
    if total_frames == 0:
        raise ValueError(f"No frames in {video_path}")

    # Calculate native FPS to accurately measure seconds
    fps = info.get("video_fps", 30.0) 
    duration = total_frames / fps

    # Target boundaries in terms of frame counts
    two_seconds_frames = int(2 * fps)
    four_seconds_frames = int(4 * fps)

    # Apply conditional criteria
    if duration > 4.0:
        # Criterion 1: Grab the first 4 seconds only
        working_video = video[:four_seconds_frames]
    elif duration < 2.0:
        # Criterion 3: Duplicate the video frames to handle short videos
        # We tile/repeat the video along the frame dimension to extend it
        repeats = math.ceil(two_seconds_frames / total_frames) + 1
        working_video = video.repeat(repeats, 1, 1, 1)
    else:
        # Criterion 2: Video is between [2, 4] seconds, keep as-is
        working_video = video

    # Uniformly grab 16 frames from our modified 'working_video' pool
    working_total = working_video.shape[0]
    indices = torch.linspace(0, working_total - 1, steps=num_frames).round().long()

    sampled = working_video[indices]       # (T, H, W, C)
    sampled = sampled.permute(0, 3, 1, 2)  # (T, C, H, W)

    transform = transforms.Compose([
        transforms.ConvertImageDtype(torch.float32),
        transforms.Resize((256, 256), antialias=False),
    ])

    return transform(sampled)


def make_grid(frames: torch.Tensor, cols: int) -> Image.Image:
    """
    Convert (T, C, H, W) → grid image.
    """
    T, C, H, W = frames.shape
    rows = math.ceil(T / cols)

    grid_img = Image.new("RGB", (cols * W, rows * H))

    for i in range(T):
        frame = frames[i]
        frame = (frame * 255).clamp(0, 255).byte()
        frame = frame.permute(1, 2, 0).cpu().numpy()
        img = Image.fromarray(frame)

        x = (i % cols) * W
        y = (i // cols) * H

        grid_img.paste(img, (x, y))

    return grid_img


def process_directory(input_dir: Path, output_dir: Path, num_frames: int = 16):
    output_dir.mkdir(parents=True, exist_ok=True)

    video_files = sorted([p for p in input_dir.iterdir() if p.suffix == ".mp4"])

    print(f"Found {len(video_files)} videos.")

    cols = int(math.sqrt(num_frames))
    success = 0

    for video_path in video_files:
        try:
            print(f"Processing: {video_path.name}")

            frames = sample_frames(video_path, num_frames=num_frames)
            grid = make_grid(frames, cols=cols)

            out_path = output_dir / f"{video_path.stem}.jpg"
            grid.save(out_path, quality=90)

            success += 1

        except Exception as e:
            print(f"Failed {video_path.name}: {e}")
            import traceback
            traceback.print_exc()

    print(f"\nDone: {success}/{len(video_files)} saved to {output_dir}")


if __name__ == "__main__":
    process_directory(DIR_NAME, OUTPUT_DIR, num_frames=16)
