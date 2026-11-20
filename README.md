# EWVE

Independent first-stage Android video-editor project.

## Included in this initial version
- Native Android / Kotlin project, named **EWVE**.
- Portrait editor layout with the top tool strip, central video player, side controls,
  transport strip, and vertically scrolling five-column storyboard.
- **Import Videos** opens Android's document picker and loads the chosen video in the player.
- The imported video does not autoplay; playback starts only when **Play** is pressed.
- Two concurrent frame processors extract up to 120 real thumbnails from the selected video:
  processor 1 handles frames 1–60 and processor 2 handles frames 61–120.
- The video itself is not inserted as a storyboard tile.
- Storyboard supports tap-first / long-press-second inclusive range selection or deselection,
  Select All, Un Select, and Delete selected storyboard frames.
- Deleting storyboard frames only removes storyboard entries; it does not modify the source video.
- Other editor tools remain disabled or are placeholders in this first milestone.

## Build
Open in Android Studio with JDK 17 and Android SDK 35, or push to GitHub and run
the included GitHub Actions workflow.

- Application ID: `com.ewve`
- Minimum Android version: API 23
- Version: 1.0
