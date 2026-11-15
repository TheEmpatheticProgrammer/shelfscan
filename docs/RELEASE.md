# Releasing ShelfScan

Goal: a signed build on Google Play's **internal testing** track, so a few people (your PM partner first) install
ShelfScan from the Play Store link without Android Studio.

Internal testing has no prerequisites. Publishing to everyone later does: a **personal** developer account created
after 13 Nov 2023 must first run a **closed test with at least 12 testers opted in for 14 consecutive days**
([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)). Plan for that
before a public launch.

## 0. Decide where the server runs (blocker)

A released app talks to one fixed **HTTPS** address and testers aren't on your Wi-Fi, so the backend has to be
reachable from the internet. Release builds refuse plain HTTP.

| Option | How | Good | Watch out |
|---|---|---|---|
| **A. Your PC + Cloudflare Tunnel** | `cloudflared` gives the backend on port 8001 a stable `https://` name | Free, keeps your local Qwen model and gateway, minutes to set up | Works only while your PC is on; your upload speed limits photo scans |
| **B. Cloud backend + hosted model** | Deploy `backend/` (it has a Dockerfile) to Cloud Run / Fly.io / Render; point `LLM_*` at a hosted model | Always on, no home machine | Pay per use; photos go to the model provider (say so in the privacy policy) |
| **C. Cloud backend + your gateway via tunnel** | B for the backend, A for the gateway only | Always-on API, local model when your PC is up | Two moving parts |

For internal testing, **A** is the fastest. Whichever you pick:
- Set **`APP_TOKEN`** on the server (it's now public) and the same value as `appToken` in `release.properties`.
  A token inside an app can be extracted, so before a public launch also add rate limiting.
- Run the server with Redis (`REDIS_URL`), so repeated requests from testers are served from the cache.

## 1. One-time setup

1. **Upload key.** Use the `keytool` that ships with Android Studio (in its `jbr\bin` folder):
   ```powershell
   mkdir D:\keys
   & "<Android Studio folder>\jbr\bin\keytool.exe" -genkeypair -v -keystore D:\keys\shelfscan-upload.jks `
     -alias shelfscan-upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Back up the `.jks` file and its password somewhere safe (a password manager). With Play App Signing, Google holds
   the real signing key and a lost upload key can be reset, but it's slow.
2. **`android/release.properties`**: copy `android/release.properties.example`, fill in the key details and the HTTPS
   `serverUrl` from step 0. It's git-ignored: never commit it.
3. **Play Console**: create a developer account (one-time fee, identity verification), then **Create app** →
   ShelfScan, App, Free. Accept the developer policies.

## 2. Every release

1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle.kts`.
2. Build and check the release on your own phone first. R8 shrinking can break things debug builds hide:
   ```powershell
   cd D:\shelfscan\shelfscan\android
   .\gradlew.bat testDebugUnitTest bundleRelease
   .\gradlew.bat installRelease      # same build, straight onto the connected phone
   ```
   If `release.properties` is incomplete, the build stops and lists what's missing.
   Smoke test: scan a photo, scan a barcode, type a title, open Discover, open a book page, export the library.
3. Upload `android/app/build/outputs/bundle/release/app-release.aab`: **Testing → Internal testing → Create new
   release**. The first upload enrolls the app in **Play App Signing**.
4. **Testers**: in Internal testing, add an email list (your PM partner's Google account), save, and send them the
   **opt-in link**. They accept and then install from the Play Store.

## 3. Store listing and policy forms (first release only)

Play asks for these before the first rollout, internal testing included:

| Item | Where it is |
|---|---|
| App icon 512×512 | `docs/store/icon-512.png` |
| Feature graphic 1024×500 | `docs/store/feature-graphic-1024x500.png` (`docs/store/render_store_art.py` regenerates both) |
| Short and full description | `docs/store/listing.md` |
| Phone screenshots (2–8) | Take them on the release build: Library, Book page, Discover, Add books |
| Privacy policy URL | Review `docs/privacy-policy.md`, fill in the blanks, publish it (e.g. GitHub Pages) |
| Data safety form | Answers drafted at the bottom of `docs/privacy-policy.md` |
| Content rating, target audience, ads | Questionnaire: no ads; not designed for children |

## What the release build does differently

- Signed with the upload key; R8 shrinks code and resources (`proguard-rules.pro` keeps the serializers and the
  Retrofit interface).
- Talks only to `serverUrl` from `release.properties`, over HTTPS (cleartext traffic is disabled).
- `gradle.properties`' `shelfscan.serverUrl` still configures **debug** builds, so day-to-day development is unchanged.
