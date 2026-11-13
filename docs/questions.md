# Open questions

Claude Code: add questions here instead of guessing. Format: date, version, question, answer.

| Date | Version | Question | Answer |
| --- | --- | --- | --- |
| 2026-11-13 | v1.0 | Who is the pilot customer? | Not found yet |
| 2026-11-13 | v2.0 | Which payment gateway (e.g. PayHere)? | To decide |
| 2026-11-13 | v0.1 | Login limit is 10/min per IP (SEC-60). A warehouse where 30 workers log in at shift start behind one office IP would hit it. Keep 10, or raise it (e.g. 30) and rely on the per-account lockout? | Open (kept at 10, configurable: wms.rate-limit.login-per-minute) |
