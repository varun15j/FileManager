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

## Scan cache and nightly scan (PR 6)
- `media/AnalysisDb` (SQLite) keeps one row per photo/video (hashes, blur, detected objects, size, modified time) and a `reports` table (last 60 scans: new vs reused, groups found, complete or interrupted).
- `media/ScanPlanner` compares what MediaStore lists now with the saved rows: unchanged items are reused, new or changed ones are analyzed newest first, vanished ones are removed. Rows are saved every 20 items, so a stopped scan resumes where it left off.
- `media/ScanEngine` runs the plan. The Analyze button shows every saved result immediately, then keeps adding newly analyzed items.
- `work/ScanWorker` (WorkManager) runs daily at about 02:00 while the device is charging and idle, using the same engine; the system can stop it and it retries. It can be turned off in Settings, where the last scan reports are listed. It needs all-files access.
- Build number 6.

## App icon (PR 7)
Adaptive icon (vector, no PNGs): a fan of photo cards for duplicates, yellow sparkles for the AI part and a green check badge for "fixed", on an indigo gradient. Includes a monochrome layer for themed icons (Android 13+). Source preview in `docs/icon/`. Build number 7.

## Faces: eyes, smile, sad mouth (PR 8)
- `media/FaceTagger` runs MediaPipe Face Landmarker (`face_landmarker.task`, Apache-2.0, bundled) on the 256 px photo copy. Per face it reads eye-blink, mouth-smile and mouth-frown blendshape scores plus a sharpness score of the face crop.
- `media/FaceRules` turns scores into labels: eyes closed, blurry face, smiling, "sad mouth" (frown with no smile; MediaPipe has no mood label, so this is not a real emotion reading) and a photo score. The best-to-keep order is now: face score, resolution, sharpness, file size, so a photo with closed eyes loses to one with open eyes even if it is larger.
- Face data is stored in the SQLite cache (DB version 2). Saved photos without it are analyzed once more when face detection is on. Switch in Settings. Faces are logged to `groups.jsonl` for later tuning. Build number 8.

## Scan database backup and instant results (PR 9)
- The scan database (`analysis.db`) is copied to `Documents/FileManagerTraining/db/analysis.db` after every scan and at most every 3 minutes during one (WAL checkpoint, then an atomic copy). An empty database never replaces a backup.
- On first open after an uninstall, or whenever the database is empty, the copy is restored, then only new or changed files are scanned. Needs All files access.
- The Photos tab shows the saved results as soon as the app opens (no Analyze needed). Saved photos that lack newer data (faces) stay visible; the extra analysis runs afterwards, after new files.
- If MediaStore ids change, saved rows are matched again by path, size and modified time and saved under the new id.

## Face detection on a larger image (PR 10)
- On the phone, only 3 of 4,100 analyzed photos had a face: faces were searched in the 256 px picture used for hashing, where they are tiny. Faces are now searched in a separate decode of about 1024 px or more on the long side. Hashes, blur and objects still use the 256 px picture, so saved results stay comparable.
- Face detector confidence lowered from 0.5 to 0.4. `ANALYSIS_VERSION` is 3, so photos checked with the old method get faces again, after new files, while their saved results stay visible.

## Face tabs and camera-only scan (PR 11)
- New review tabs: Eyes closed, Blurry face, Sad face. Each lists single photos where a face big enough to judge has the problem, with checkboxes, a "keep" button that is logged as feedback, and the same trash-with-undo flow.
- `MediaFilter` limits the scan to camera, gallery, download, screenshot and chat-app photos and videos. It skips app and system folders (Android/data, Android/obb, system apps' media), Music (album art), Ringtones and similar, hidden and thumbnail folders, `Documents` (including the app's own training data), and pictures under 320 px on a side (icons, UI resources). Files in those places never appear in any tab, so they cannot be deleted from here. Saved results for them are dropped on the next complete scan.

## Custom categories you teach the app (PR 12)
- Sample photos are added with "+ Sample" on any photo or picked from the gallery in the Categories tab, then given a name and a description. The app embeds each sample with MediaPipe's Image Embedder (MobileNet V3 small, Apache-2.0, 4 MB bundled), stores the average look and a similarity cut-off (a little below the least typical sample), and lists what most samples share (faces, smiling, closed eyes, person, shape).
- Each category gets its own tab with the photos that look like the samples, best match first, and the usual review and trash flow. "Not <name>, keep" saves that photo as a negative example, so lookalikes drop out, and is logged as training feedback. A slider per category makes matching looser or stricter.
- Embeddings are stored (1 KB per photo, DB v3) only once a category exists; the first scan after creating one embeds saved photos in the background, the nightly scan keeps new photos embedded. Photos already analyzed keep their hashes, blur, objects and faces.
- Everything is in `Documents/FileManagerTraining/categories/<id>/` (`category.json` and small sample copies), so it survives reinstalling. Sample copies are not committed to GitHub without an explicit OK.
- Descriptions are saved with the samples but do not steer matching; matching learns from the photos themselves.

## Categories from dataset folders (PR 13)
- Every sub-folder of `Documents/FileManagerTraining/dataset/` that holds pictures (jpg, png, webp, heic) is a category named after the folder (`sad_mouth` becomes "Sad mouth"). The Categories tab lists the folders with a Learn / Update button; an optional `description.txt` in the folder is saved as the category description.
- Learning a folder embeds its photos, builds the category and searches all saved photos, the same as hand-picked samples. A folder's signature (names, sizes, times) is saved with the category, so photos added later update it automatically on the next app start. Negative examples ("Not this") are kept when a folder is learned again.
- Dataset photos are read in place and not copied; they stay on the phone and the PC and are not committed to GitHub (the repository is public).

## Photos are turned upright before analysis (PR 14)
- Found on a real phone: HEIC camera photos were decoded without their EXIF orientation, so faces, objects and hashes were analyzed sideways (face boxes matched a 90° turn of the photo). That made smile, closed-eyes and sad-mouth results unreliable. `decodeSampled` now reads the orientation tag and turns the picture upright (`Orientation` holds the mapping).
- `ANALYSIS_VERSION` is 4, so every photo is analyzed again once, after new files, while its saved result stays visible. Embeddings from an older analysis are dropped when a photo is analyzed again, so categories are never matched on sideways copies.
- While the re-check runs, a photo's saved hash (from the sideways copy) and a new hash (upright) can differ, so similar groups may be incomplete until it finishes.
