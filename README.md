# FML Collection

Android app for my game collection on [fixmylifeco.com](https://www.fixmylifeco.com):

- **Collection**: opens on the digital collection (`/archive`).
- **Inventory**: every NFC-tagged item and what's missing (`/items`).
- **Create**: new tagged item (`/pack`), maintenance codes, cartridge label stickers, label press, box maker, organizer, game index, saves, supplies.
- **NFC**: reading is always on while the app is open. Hold a tag to the phone and its page opens. Tags also open the app when it's closed. **Write a link…** puts the current item's short `/i/<id>` link on a tag (NTAG213/215/216). The `/pack` page has a **Write to NFC tag** button too.
- **Listing links**: share a Mercari / Yahoo Auctions / eBay listing or a Google Maps place to the app, and it goes into the item's listing link.
- Downloads from the label and box tools are saved to `Downloads/FML Collection`, with Share / Open. Photo pickers can use the camera.
- **Self-updating**: every push to `main` builds a signed APK and publishes it as release `v<run number>`. The app checks the latest release on start (and from Create → Check for updates) and installs it.

## Install

Download `fml-collection.apk` from the [latest release](https://github.com/fixmylifedesigns/fml-collection/releases/latest) on the phone and open it (allow installs from your browser once). After that, updates come from inside the app.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds `:app:assembleRelease` with Gradle 8.9 / AGP 8.5 and signs it with `app/release.keystore.b64`, so every release installs over the previous one. If a build fails, the workflow opens an issue with the end of the log.

No third-party libraries: plain Android framework (WebView, NFC reader mode, PackageInstaller).
