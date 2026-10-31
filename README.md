# Nova Glucose

Android app (APK) that follows a FreeStyle Libre sensor through **LibreLinkUp** and proposes food ideas.

- Reads the latest glucose + 12-hour graph every minute (LibreLinkUp follower login), live notification
- High / low / old-reading alerts with food tips (limits editable)
- Built-in food ideas by glucose level and time of day — Tunisian, Gulf and international dishes, with approximate carbs
- **Ask AI** for food ideas — Gemini, ChatGPT or Claude (API key in Settings)
- **Carbs from photo / video** — the AI lists each food with its carbs; every item can be corrected; saved to a logbook that shows glucose 2 hours later
- Never suggests insulin doses. Food ideas follow standard guidance — check with the diabetes care team.

The LibreLinkUp connection is unofficial (Abbott doesn't publish it) — keep LibreLinkUp's own alarms on.

Builds run on GitHub Actions; each build is published as a release (`NovaGlucose-1.0-build-NN.apk`).
Signing key: `keystore/nova-glucose.jks.enc`, opened with the `NOVA_GLUCOSE_KEY` secret.

Designed by A.HAMROUNI
