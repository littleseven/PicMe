You are diagnosing a bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).

Build git SHA: __GIT_SHA__
User-reported problem:
__DESCRIPTION__

Sanitized app logs (PoLang:* tags):
__LOGS__

Crash trace (if any):
__CRASH_TRACE__

Your task: find the ROOT CAUSE in the source code (checked out at the above SHA, in the current directory). Explore the relevant files. Do NOT modify any file — analysis only.

OUTPUT RULES (critical — follow exactly):
- Reply with ONLY the JSON object below. No prose, no greeting, no explanation.
- No markdown code fences. The first character of your reply MUST be "{" and the last MUST be "}".
- Keep rootCause to one paragraph. If unsure, still output the JSON with your best guess.

{"rootCause": "<one-paragraph root cause>", "suspectFiles": ["<file:line>", ...], "suggestedFix": "<brief fix direction>"}
