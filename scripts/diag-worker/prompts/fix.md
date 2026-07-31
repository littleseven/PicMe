You are fixing a confirmed bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).
You are on a fresh branch. Apply a MINIMAL fix — do not refactor unrelated code.

Confirmed root cause:
__ROOT_CAUSE__

Fix direction suggested:
__SUGGESTED_FIX__

Requirements:
- ACTUALLY EDIT the relevant source files to apply the fix (use your file-editing tools). Merely describing or listing the change is NOT enough — the files must be modified on disk.
- You are at the repository root (your current working directory); the Android project is here.
- Make the smallest change that resolves the root cause.
- Do not change public API signatures unless strictly required.
- After editing, do NOT run the build (the wrapper will run tests separately).

Output STRICTLY this JSON and nothing else:
{"changedFiles": ["<file>", ...], "summary": "<one-line summary of the fix>"}
