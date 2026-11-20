# EWVE — first-stage Android video editor

- Application ID: `com.ewve`; Java 17; Android SDK 35; minSdk 23.
- Toolbar order follows the supplied reference: Import Videos, Export Video, Delete,
  Tracks, Color And Light, Blur Sharpen Noise, Merge Join, Video Text.
- Multi-select video import through Android's document picker (`video/*`), including
  common video formats supported by the device. The first selected video loads in the player.
- Player preview image from the first extracted frame; playback starts only with Play.
- Drag vertically on the left half of the player to adjust player-only brightness overlay;
  drag vertically on the right half to adjust player volume.
- Play/Pause, seek forward/back 10 seconds, and playback speed presets.
- Storyboard has 120 extracted frames and a 1–15 column selector (default 5).
- Tap an endpoint, then long-press the second endpoint for inclusive range selection/deselection.
- Select All, Un Select, Delete selected storyboard frames, Undo and Redo (up to 50 history states).
- The imported source video is not inserted as a storyboard tile.
- Export, tracks, crop, color grading, blur/sharpen/noise, merge, and text remain future tools.
