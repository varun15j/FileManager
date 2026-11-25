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
- M3: similar photos – group by date taken (EXIF/MediaStore) within a time window, then dHash/pHash Hamming distance; keeper = resolution, size, sharpness (Laplacian variance).
- M4: on-device ML similarity – MobileNet / ML Kit embeddings via TFLite, cosine similarity to refine groups.
- M5: similar videos – sample frames (MediaMetadataRetriever), hash them, compare with duration and size.
- M6: MediaStore trash/delete requests, image thumbnails (Coil), polish.

Status: M1 code is written but not yet compiled locally (no Android SDK in the authoring environment); the included GitHub Actions workflow builds and runs unit tests.
