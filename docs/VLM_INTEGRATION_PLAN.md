# VLM integration plan (not built yet; waits for the vlmtest A/B result)

## Model manager in the gallery
- Up to 3 downloadable models: official Gemma 4 E2B, Qwen3.5-2B-VL, abliterated Gemma 4 E2B (community build, unaudited).
- Per model: size, free storage check, download (Wi-Fi only, resumable), SHA-256 verify, delete, redownload.
- Short in-app tutorial: what the model does, what it costs (storage, battery), how to delete it.
- Loading uses a fresh engine per request for Qwen (recurrent state note on its model card).

## Results must not depend on the model file
- Tags, descriptions and categories from the model are stored in the gallery database.
- The user can delete the model to free space and keep all results.
- The model is only needed to categorize new photos or to answer new questions.

## Rules
- Sensitive stays image-only (open_nsfw). A model refusal or malformed output never changes categories.
- Charge-gated, throttled bulk pass; engine released when idle.
- No keys or personal names in code.
