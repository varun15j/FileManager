# Architecture

Kotlin, Jetpack Compose, Material 3. Single `:app` module for now, with package layers that can be split into modules later.

- `core/` – pure Kotlin, no Android types, unit-tested: `FileOps` (list/copy/move/rename/search), `Trash` (reversible delete), `DuplicateFinder`.
- `ui/` – Compose screens and AndroidViewModels (Browser, Duplicates).
- Next: `media/` (MediaStore queries, EXIF/date taken), `similarity/` (dHash/pHash, TFLite embeddings), `cleanup/` (analyzer, junk, empty folders).

## Safety rules
1. Nothing is deleted without an explicit confirm dialog.
2. "Delete" moves to an app trash first (undo / restore). Permanent removal is a separate action.
3. Suggested keeper is pre-selected, never forced; a group can never lose all its copies (button disabled).
4. Android 11+: all-files access (`MANAGE_EXTERNAL_STORAGE`) for the browser. For MediaStore items from other apps, later milestones use `MediaStore.createDeleteRequest` / `createTrashRequest`.

## Duplicate pipeline (milestone 1, exact)
size → hash of first+last 64 KB → full SHA-256. Cheap filters first so large files are rarely read fully.

## Roadmap
- M2: storage analyzer, large files, junk, empty folders.
- M3 (started in PR 2): similar photos – group by date taken (EXIF/MediaStore) within a time window, then dHash/pHash Hamming distance; keeper = resolution, size, sharpness (Laplacian variance).
- M4: on-device ML similarity – MobileNet / ML Kit embeddings via TFLite, cosine similarity to refine groups.
- M5: similar videos – sample frames (MediaMetadataRetriever), hash them, compare with duration and size.
- M6: MediaStore trash/delete requests, image thumbnails (Coil), polish.

Status: M1 code is written but not yet compiled locally (no Android SDK in the authoring environment); the included GitHub Actions workflow builds and runs unit tests.

## Photo/video analysis (PR 2)
- `media/ImageMetrics`: dHash (9x8), Hamming distance, Laplacian-variance blur score. Platform-free, unit-tested.
- `media/SimilarGrouper`: photos grouped by date-taken window (10 min) plus Hamming <= 10; videos by duration (3%) plus 5 sampled frame hashes; union-find clustering; keeper = highest resolution, then sharpest, then largest.
- `media/MediaScanner`: MediaStore query, 256 px sampled decode, MediaMetadataRetriever frames.
- `ui/CleanupScreen`: Similar / Blurry / Videos tabs. Delete uses `MediaStore.createTrashRequest` (system confirmation, recoverable) on Android 11+, app trash below.
- Thresholds (Hamming 10, blur 60) are first guesses and need tuning on real photos. On-device ML embeddings (M4) are not in yet.

## Object-aware similarity (PR 3)
`media/ObjectTagger` runs the COCO EfficientDet-Lite0 detector (Apache-2.0, model file taken from the ADAS_cam project) through MediaPipe Tasks to find people and vehicles with normalized positions. `SceneMatcher` treats two photos as the same scene when the number of people and vehicles matches and each object sits in a similar place and size. `SimilarGrouper` accepts a looser hash distance (20 instead of 10) only in that case, so shots where the subject moved a little, or the framing changed, still group. The review cards show where people stand in the frame. The ADAS_cam `adas_india` model is not used: it was trained on dashcam frames and scored 26% mAP50 on its own test set.

## Training log and progressive scan (PR 4)
- Scanning is progressive: newest media first, groups regrouped every ~1.5 s while it runs, and the user's check marks survive the updates (`putIfAbsent`).
- `training/TrainingStore` writes to `Documents/FileManagerTraining` (outside app storage, so it survives uninstall; needs all-files access, otherwise it falls back to app storage). `decisions.jsonl` has one flat line per decision (approved / rejected / cancelled, items, deleted, kept, overrides), `groups.jsonl` has what the analyzer saw, `scans.jsonl` has scan summaries, and `samples/` holds 256 px thumbnails (switchable in the Training tab).
- Accuracy = approved / (approved + rejected) per kind; "overrides" counts items where the user changed the pre-selection. Stats are re-read from the folder at startup, so a reinstall picks up earlier feedback.
- `tools/pull-training-data.ps1` copies the folder to the PC for training. Thresholds are not auto-tuned yet; the data is what a later tuning step needs.

## Settings and build numbers (PR 5)
- Settings tab: similarity thresholds (`media/Thresholds`, stored in SharedPreferences, applied on the next analysis), training samples switch, build info.
- Build number: `app/version.properties` (`buildNumber=N`) feeds `versionCode`, `versionName` (0.1.N) and `BuildConfig`. It goes up by 1 in every pull request, not every commit; a CI job fails a PR that does not raise it. The Settings screen shows the build number and git commit.
