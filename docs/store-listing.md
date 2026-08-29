# Play Store listing

**The listing text and every console answer now live in
[`play-submission-runbook.md`](play-submission-runbook.md), in this repository.**
Only the artwork stays in the local pack `~/Documents/Tawny ship/`, because it is
binary — and the icon regenerates from `public/icon.svg` anyway.

This file used to hold a second copy of the listing text, and it drifted badly:
by the time anyone read it again it specified an ink-and-saffron palette that
had not been in the app for two design generations (it was actually describing
the abandoned iOS paw-print icon), a "Fraunces" wordmark that is not in this
repository, and the role names "Watcher"/"Handheld" which the app has never
shown to a user. Rather than maintain the same words in two places, it now
points at the one place they are maintained.

| What | Where |
|---|---|
| App name, short + full description | `play-submission-runbook.md` § step 5 |
| Category, tags, contact, declarations | `play-submission-runbook.md` §§ 5–6 |
| Data safety answers | `play-submission-runbook.md` § step 6 |
| Content rating answers | `play-submission-runbook.md` § step 6 |
| Reviewer / app-access instructions | `play-submission-runbook.md` § step 6 |
| Upload-key and release-build steps | `play-submission-runbook.md` §§ 1, 3 |
| Ordered submission runbook | `play-submission-runbook.md` |
| Privacy policy page | `../rendezvous/privacy.js`, served at `GET /privacy` |
| Icon, feature graphic, screenshots | `~/Documents/Tawny ship/graphics/` (binary; not in git) |

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
