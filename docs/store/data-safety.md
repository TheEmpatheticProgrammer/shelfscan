# Play Console: Data safety form answers

Matches `docs/privacy-policy.md` and the option A setup (your own model through the gateway, no third-party AI).
If that changes (for example a hosted model), update both.

**Data collection and security**
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (release builds use HTTPS only) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: by email to the contact address (nothing is tied to an account) |

**Data types**: declare only these two; everything else (location, personal info, financial, health, messages,
contacts, calendar, app activity, web browsing, device IDs, audio, files) is **not collected**.

| Data type | Collected | Shared | Processed ephemerally | Required or optional | Purpose |
|---|---|---|---|---|---|
| Photos and videos → **Photos** | Yes | No | **Yes** | Required | App functionality |
| App info and performance → none; use **Other user-generated content** for book titles and search text | Yes | No | No (suggestions cached up to 7 days) | Required | App functionality |

Notes for the form:
- "Shared" means sent to a third party. Google Books and Open Library receive only book titles/ISBNs from the server,
  with nothing identifying the user, so this is not sharing user data.
- **App access**: all functionality is available without special access (no login).
- **Ads**: No. **Target audience**: 18+ (or 13+); not designed for children. **Content rating**: questionnaire,
  reference app, no user interaction features, no user-generated content shared with others.
