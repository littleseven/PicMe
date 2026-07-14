What's new in PoLang v1.0.10

✨ New Features
• Photo Editor Filters: Added a filter panel in the photo editor with real-time style effect preview — Toon, Sketch, Posterize, Emboss, and Crosshatch.
• Smarter AI Tagging: MobileCLIP now powers bilingual (Chinese/English) zero-shot scene and object classification, combined with Qwen-generated activity descriptions for richer, more accurate photo tags.

🐛 Bug Fixes
• Fixed face slimming and eye enlargement not applying correctly in the photo editor, and corrected the direction of warp effects.
• Restored the multi-pass beauty pipeline for photo editing to ensure consistent smoothing, whitening, and makeup results.

⚡ Performance
• Camera launch is now faster — the LLM unloads by default when entering the camera and reloads asynchronously, reducing startup latency.

🔧 Under the Hood
• Extracted :mnn-core as a standalone module for cleaner dependency boundaries.
• Removed unused native libraries (libncnn.so) to reduce APK size.
• Aligned NDK versions and fixed KSP incremental cache corruption for more stable builds.

Thank you for using PoLang! If you enjoy the app, please consider leaving a review.
