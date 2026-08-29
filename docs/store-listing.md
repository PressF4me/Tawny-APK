# Play Store listing

**The finished, submission-ready copy and artwork live outside this repository,
in `~/Documents/Tawny ship/`.** That pack is what gets pasted into Play Console.

This file used to hold a second copy of the listing text, and it drifted badly:
by the time anyone read it again it specified an ink-and-saffron palette that
had not been in the app for two design generations (it was actually describing
the abandoned iOS paw-print icon), a "Fraunces" wordmark that is not in this
repository, and the role names "Watcher"/"Handheld" which the app has never
shown to a user. Rather than maintain the same words in two places, it now
points at the one place they are maintained.

| What | Where |
|---|---|
| App name, short + full description | `~/Documents/Tawny ship/listing/` |
| Category, tags, contact, declarations | `~/Documents/Tawny ship/listing/category-and-contact.md` |
| Data safety answers | `~/Documents/Tawny ship/listing/data-safety-answers.md` |
| Content rating answers | `~/Documents/Tawny ship/listing/content-rating-answers.md` |
| Reviewer / app-access instructions | `~/Documents/Tawny ship/listing/review-notes.md` |
| Icon, feature graphic, screenshots | `~/Documents/Tawny ship/graphics/` |
| Privacy policy page + hosting steps | `~/Documents/Tawny ship/privacy-policy/` |
| Upload-key and release-build steps | `~/Documents/Tawny ship/build/KEYSTORE.md` |
| Ordered submission runbook | `~/Documents/Tawny ship/README.md` |

## The one thing worth keeping here: the real palette

Taken from `android/app/src/main/res/values/colors.xml`, which is the source of
truth and is mirrored by `public/style.css` and `object Hue` in `MainActivity.kt`.

| Role | Light | Dark |
|---|---|---|
| Background | `#FBF4F1` | `#191410` |
| Panel | `#FFFFFF` | `#241D16` |
| Text | `#33323D` | `#EFE5D5` |
| Accent | `#D24B6D` berry | `#C79B64` brass |
| Secondary | `#5B93B8` sky | `#A6B489` sage |

Brand mark: a tawny owlet, `#DCA55E`→`#B26F31`, on a twilight gradient
`#6FA8CE`→`#3B6A92`. Source: `public/icon.svg`; rasters via `tools/gen-icons.sh`.

Fonts bundled in the app: **Ma Shan Zheng** (the "Tawny" wordmark) and **Mukta**
Regular/SemiBold.
