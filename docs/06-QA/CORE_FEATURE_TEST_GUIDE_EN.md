# PicMe Core Feature Test Guide

> Version: v1.0.2 | Updated: 2026-06-25
>
> This guide covers the core feature test procedures for the PicMe app, including model downloads, permission grants, photo capture, tag generation, and AI chat.

---

## 1. Environment Setup

| Item | Requirement |
|------|------|
| Android Device | Physical device (arm64-v8a), API 24+ |
| RAM | 8GB+ recommended (required for Qwen3.5-2B on-device inference) |
| Storage | 5GB+ free space (models ~3GB + photo library) |
| Installation | `adb install -r app/build/outputs/apk/debug/picme-debug.apk` |
| API Key | OpenAI-compatible API required (DeepSeek / Qwen, etc.) |

---

## 2. Required Permissions

Grant permissions in sequence on first launch; manual confirmation in system settings is recommended:

| Permission | Purpose | Required |
|------|------|:---:|
| `CAMERA` | Photo capture, real-time face detection | ✅ |
| `POST_NOTIFICATIONS` | Model download progress, TAG scan notifications | ✅ |
| `READ_MEDIA_IMAGES` | Reading existing photos from gallery | ✅ |
| `RECORD_AUDIO` | Voice input | ⭕ |
| `SYSTEM_ALERT_WINDOW` | Floating chat bubble | ⭕ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent background scan interruption | ⭕ |

> **TAG scanning requires persistent background execution**: It is recommended to add PicMe to the battery optimization whitelist in system settings.

---

## 3. Required Model Downloads

Go to **`Settings → Model Center`** and ensure the following models marked as **"Required"** are downloaded:

| Model ID | Name | Purpose | Size (approx.) |
|------|------|------|:---:|
| `qwen3_5_2b` | Qwen3.5-2B | On-device image understanding & tag generation | ~2.5GB |
| `sherpa-onnx-zipformer-zh-en` | Sherpa-ONNX Zipformer | Streaming speech recognition (voice input) | ~280MB |
| `sherpa-onnx-kws-zipformer-wenetspeech` | Sherpa-ONNX KWS | Wake-word detection | ~14MB |
| `face-det-retina500m-mnn` | MNN ROI (Det500M) | Default high-precision face detection | ~5MB |
| `face-landmark-2d106-mnn` | MNN 2D106 | 106-point facial landmark detection | ~1MB |
| `face-embedding-glint360k-r100-mnn` | Glint360K R100 | Face feature vector extraction (clustering/recognition) | ~248MB |
| `mobileclip-onnx` | MobileCLIP-S2 | Image/text semantic encoding (semantic search/photo tagging) | ~397MB |
| `opus-mt-zh-en` | OPUS-MT | Chinese→English translation (semantic search query translation) | ~70MB |

> Model Center tags are sorted with "**Face Detection**" and "**Chat**" at the top for quick access to required models.

---

## 4. Core Pages & Features

### 4.1 Navigation Structure

```
┌──────────────────────────────────────┐
│  Chat           ←→  Camera           │
│  Gallery        ←→ Settings           │
└──────────────────────────────────────┘
```

| Page | Route | Entry Point |
|------|------|------|
| Camera | `camera` | Bottom tab, 2nd |
| Gallery | `gallery` | Bottom tab, 3rd |
| Chat | `chat` | Bottom tab, 1st |
| Settings | `settings` | Gear icon in top-right of Chat / Gallery |
| Model Center | `model_center/{tag}` | Inside Settings page |
| TAG Control | `tag_control` | Entry inside Gallery page |

---

### 4.2 Camera Page

| Action | Verification Point |
|------|--------|
| Tap shutter button | Photo saved successfully with haptic feedback |
| Switch front/rear cameras | Preview switches normally, no black screen |
| Adjust beauty parameters | Slider takes effect in real-time: smooth, whiten, slim face, etc. |
| Continuous capture | 5–10 shots without lag or OOM |

---

### 4.3 Gallery Page

| Action | Verification Point |
|------|--------|
| Photo grid loading | Thumbnails display correctly, smooth scrolling |
| Tap a photo | Opens full-screen preview with facial landmark annotations |
| Group by person | Person clusters display correctly in PERSON mode |
| Search photos | Enter tag keywords to filter photos |
| TAG Control entry | Tap entry to open tag generation control page |

---

### 4.4 Tag Generation (TAG Control Page)

#### Control Page Layout

```
┌──────────────────────────────────┐
│     Scan Progress Card            │
│     Database Statistics Card      │
│     ├ Total / With Faces / Tagged / Person Clusters │
│     └ MFNet Model Status          │
│                                   │
│     3-Pass Pipeline Overview      │
│                                   │
│ ┌──────────────────────────┐      │
│ │ Full 3-Pass Scan         │      │
│ ├──────────────────────────┤      │
│ │ Pass 1: Face Detection   │      │
│ │ Pass 2: DBSCAN Clustering│      │
│ │ Pass 3: Qwen Tags        │      │
│ │ Pass 3: Regenerate Tags  │      │
│ │ ─────────────────────── │      │
│ │ Incremental Scan         │      │
│ │ Cancel Scan              │      │
│ └──────────────────────────┘      │
└──────────────────────────────────┘
```

#### Button Function Descriptions

| Button | Function | Prerequisites |
|------|------|----------|
| **Full 3-Pass Scan** | Execute P1→P2→P3 sequentially | All 5 required models downloaded |
| **Pass 1: Face Detection** | Face detection + embedding extraction only | MNN ROI + 2D106 + Glint360K R100 |
| **Pass 2: DBSCAN Clustering** | Re-cluster based on existing embeddings | Pass 1 has been executed |
| **Pass 3: Qwen Tags** | Generate tags for untagged photos | Pass 1 executed, Qwen model downloaded |
| **Pass 3: Regenerate Tags** | Clear all tags and regenerate from scratch | Qwen model downloaded |
| **Incremental Scan** | Process only new/untagged photos | Existing scan baseline |
| **Cancel Scan** | Interrupt the current scan task | Scan in progress |

#### Expected Results (10 photos with faces)

| Pass | Duration (Snapdragon 8 Gen3) | Expected Output |
|------|:---:|------|
| P1 | ~30s | `Faces: 10`, `Embeddings: ≥10` |
| P2 | ~2s | `Person Clusters: 2–5` (depends on distinct faces) |
| P3 | ~2min | `Tagged: 10`, each photo with scenes/activities/tags |

#### Key Verification Points

- [ ] After P1, the "With Faces" count increases correctly
- [ ] After P2, the number of person clusters matches actual distinct faces (different people are not merged incorrectly)
- [ ] After P3, each photo can be searched by tag in the gallery
- [ ] Scanning continues after screen lock / app switch (foreground service notification persists)
- [ ] State stops correctly after canceling the scan
- [ ] After Pass 3 Regenerate, old tags are replaced

---

### 4.5 Chat Page

| Action | Verification Point |
|------|--------|
| Send text message | AI responds normally, typing animation shown |
| Send image | Capture or select from gallery; AI understands image content |
| Markdown rendering | Code blocks, tables, lists render correctly |
| Model switching | Local Qwen / cloud models switch normally |
| Sidebar | Opens/closes normally, history conversations visible |
| Clear conversation | Conversation list cleared correctly |

---

### 4.6 Model Center

| Action | Verification Point |
|------|--------|
| Tag sorting | "Face Detection" and "Chat" appear first |
| "Required" badge | MNN series + Qwen show red "Required" badge |
| Download model | Download progress notification shown, status updates on completion |
| Lite badge | Models < 50MB display "Lite" badge |
| Switch tabs | Different category model lists switch normally |

---

## 5. Quick Verification Flow (~10 minutes)

```
1. Install APK → Open app → Grant all permissions

2. Settings → Model Center → Prioritize downloading the 5 "Required" models
   Wait for downloads to complete (Qwen3.5-2B is ~2.5GB, Wi-Fi recommended)

3. Camera → Capture 3–5 sample photos with faces (different people)

4. Gallery → TAG Control → "Full 3-Pass Scan"
   Wait for completion (~2–5 minutes; initial Qwen loading has additional overhead)

5. Gallery → Verify:
   ✓ Photos can be browsed normally
   ✓ Tag search filters to matching photos
   ✓ PERSON grouping shows correct person clusters

6. Chat → Send a message with an image
   → Verify that AI correctly understands the image and responds
```

---

## 6. FAQ

| Issue | Troubleshooting Steps |
|------|----------|
| Pass 1 result is 0 | ① Check if MNN ROI + 2D106 models are downloaded<br>② Confirm camera permission is granted |
| Pass 2 person clusters inaccurate | ① Check if Glint360K R100 model is downloaded<br>② Clustering threshold in `ClusteringConfig.kt` (current cosine similarity threshold: 0.72) |
| Pass 3 tags are empty | ① Check if Qwen3.5-2B model is downloaded<br>② Confirm API Key is configured correctly<br>③ Confirm device has sufficient memory (4GB+ free) |
| Model download failed | ① Check network connection<br>② Confirm free storage > 5GB<br>③ Check download notification bar for error messages |
| Scan stops mid-way | ① Confirm foreground service notification is visible<br>② Check battery optimization whitelist<br>③ Check logcat for `PicMe:TagScheduler` tag logs |
| Qwen inference too slow | ① Currently CPU inference; OpenCL fallback is disabled<br>② Snapdragon 8 Gen3: ~15–30s per image |
