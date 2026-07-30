You are diagnosing a bug in an Android (Kotlin/Compose) project: PoLang (破浪相册).

Build git SHA: __GIT_SHA__
User-reported problem:
__DESCRIPTION__

Sanitized app logs (PoLang:* tags):
__LOGS__

Crash trace (if any):
__CRASH_TRACE__

Your task: find the ROOT CAUSE in the source code (checked out at the above SHA, in the current directory). Explore the relevant files. Do NOT modify any file — analysis only.

Output STRICTLY this JSON and nothing else:
{"rootCause": "<one-paragraph root cause>", "suspectFiles": ["<file:line>", ...], "suggestedFix": "<brief fix direction>"}
