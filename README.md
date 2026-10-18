# 8CloudGallery

Android gallery app with a light cloud look. Milestone 1: fast local gallery. Cloud backup to Telegram (MTProto/TDLib, client-side encryption) comes in a later milestone.

## Current features (milestone 1)

- Photo/video grid via MediaStore, pinch to change columns (2-6) + columns setting
- Date headers when sorting by date
- Sort by date, size or name, ascending/descending
- Viewer: pinch zoom, swipe between items, videos play inline
- Heart button under the image = favorite; favorites filter in the grid
- Info panel: file name, date, size, resolution, duration, album, EXIF (camera, ISO, aperture, focal length, exposure)
- Cloud-status badge placeholder (everything is "local only" until backup lands)
- Settings: grid columns, default sort, theme (system/light/dark)

## Build

GitHub Actions builds the debug APK (`app-debug-apk` artifact). Signing uses the default debug keystore; a release signing setup is intentionally not part of this milestone.

Package: `app.eightcloud.gallery` (debug builds use the `.debug` suffix so they install next to a future release build).
