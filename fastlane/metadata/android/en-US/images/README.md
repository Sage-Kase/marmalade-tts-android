# Store images — what to drop here

This folder holds the visual assets for the Google Play and F-Droid
listings, using the **fastlane / Triple-T `metadata/android` layout**
(F-Droid reads the same structure). The files are in version control.
Only `en-US` has images; the other locales fall back to them on both
Play and F-Droid.

Screenshots show the app's own screens with no personal data and no
status bar. PNG or JPEG, 24-bit, no transparency.

## Required / expected files

| File (relative to this folder) | Used by | Spec |
|---|---|---|
| `icon.png` | Play, F-Droid | App icon. 512×512 px, 32-bit PNG. F-Droid also pulls the launcher icon from the APK, but a clean 512² here is recommended. |
| `featureGraphic.png` | Play (required), F-Droid | 1024×500 px. Shown at the top of the Play listing. No transparency. |
| `phoneScreenshots/NN-name.png` | Play (min 2), F-Droid | Phone screenshots; the two-digit prefix sets the display order (`01-speak.png`, `02-reader.png`, …). 320–3840 px, long side at most twice the short side. Play's promotion slots need at least 4 portrait shots at 9:16, 1080×1920 or larger. |

## Optional / recommended

| File | Used by | Spec |
|---|---|---|
| `promoGraphic.png` | Play (legacy) | 180×120 px. Optional; many listings skip it. |
| `tvBanner.png` | Play (TV only) | 1280×720 px. Only if you ever ship an Android TV build — not needed now. |
| `sevenInchScreenshots/*.png` | Play | 7" tablet screenshots, if you support tablets. |
| `tenInchScreenshots/*.png` | Play | 10" tablet screenshots, if you support tablets. |

## Notes

- The mascot art lives in `../../../../../../assets/` (repo `assets/`)
  if you want to build the feature graphic or icon from it.
- Keep the feature graphic text-light: Play crops and overlays it.
- F-Droid will also display `full_description.txt`,
  `short_description.txt`, and the `changelogs/` entries from the parent
  folder — no extra image is required beyond icon + screenshots.
