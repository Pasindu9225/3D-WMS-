# Open questions

Claude Code: add questions here instead of guessing. Format: date, version, question, answer.

| Date | Version | Question | Answer |
| --- | --- | --- | --- |
| 2026-11-13 | v1.0 | Who is the pilot customer? | Not found yet |
| 2026-11-13 | v2.0 | Which payment gateway (e.g. PayHere)? | To decide |
| 2026-11-13 | v0.1 | Login limit is 10/min per IP (SEC-60). A warehouse where 30 workers log in at shift start behind one office IP would hit it. Keep 10, or raise it (e.g. 30) and rely on the per-account lockout? | Open (kept at 10, configurable: wms.rate-limit.login-per-minute) |
| 2026-11-15 | v0.4 | Should a purchase order be approved by someone other than the person who created it? Now anyone with inbound:manage can create and approve, which suits a 1–3 person office | Same person may create and approve (user, 2026-11-15) |
| 2026-11-17 | v1.0 | Every warehouse role (receiver, picker, packer) has catalog:read, so they can see customers' and suppliers' phone, email and address. Fine for a small company's own staff; split off a partners:read permission (office roles only) if the pilot wants contact details hidden from the floor? | Open (security review v1.0 F2; kept as is for the pilot) |
