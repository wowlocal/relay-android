# Relay for Android — languages

Decided in polish 2b (QA-06). Relay for Android is **English only**, like Relay for iOS
(`relay-calls-integration/ios-device/docs/design/localization.md`).

## Why

Telegram-Android bundles English in `values/strings.xml` (11,707 strings) and gets every other language from
Telegram's servers as a language pack (`langpack.getLangPack`, Settings → Language). Relay never talks to Telegram,
and the bridge answers `langpack.getLanguages` with an empty stub, so the packs never arrive.

What the repository does contain are Telegram's small bundled subsets (`values-<lang>/strings.xml`, turned into
`assets/localization_<lang>` by `TelegramStringsTask`), used before a pack loads:

| Language | Strings | Share of English |
|---|---|---|
| it | 1,516 | 13% |
| de | 1,359 | 12% |
| es | 1,298 | 11% |
| pt-BR | 1,234 | 11% |
| ar | 1,227 | 10% |
| ko | 1,121 | 10% |
| nl | 1,068 | 9% |
| uk | 269 | 2% |
| ru | 257 | 2% |

Offering these gave the QA's half-translated UI ("Alle Chats" next to English Settings rows, the tab bar and every
Relay screen in English, "Bild" left in a preview after switching back).

Options looked at:

- **Telegram's full translations** (translations.telegram.org). Contributed by volunteers through Telegram's platform;
  no licence for reuse outside Telegram's apps was found, as iOS concluded in 4C. Not bundled. The `values-<lang>`
  subsets above are part of the GPL source tree and could be used, but they are too small.
- **Translating the kept surfaces ourselves** (Relay's ~170 strings plus the Telegram strings reachable from Relay's
  screens, several thousand per language). Not done in polish 2b; it needs a translation workflow.

## What the app does

- `LocaleController` (Relay only): the language list holds English alone (`relayKeepOnlyEnglish`), language packs
  are never requested (`loadRemoteLanguages` returns), and English is applied as an override at start, so neither
  the device language, a per-app language (Android 13+) nor a `language` value left by an older build changes it.
  A device language change re-applies English (Telegram's override path).
- No Language row in Settings, no Language or translation results in Settings search, and `LanguageSelectActivity`
  / `RestrictedLanguagesSelectActivity` are in `RelayFeatures.DENIED_FRAGMENTS`.
- Android resources were already English only: upstream packages with `localeFilters = ["zz"]`.
- Not RTL: with English forced, `LocaleController.isRTL` stays false.

Dates and times use English formats (Telegram derives its formatters from the app language).

## Adding a language later

1. Translate `values/strings.xml` into `values-<lang>/strings.xml`: Relay's strings (`Relay*`) and the Telegram
   strings Relay keeps, with Relay's wording (no "Telegram"). `TelegramStringsTask` turns the file into
   `localization_<lang>`; missing keys fall back to English per key.
2. Add the language to the list in `relayKeepOnlyEnglish` (and stop forcing English in the constructor for it).
3. Show the Settings Language row again and take `LanguageSelectActivity` out of `DENIED_FRAGMENTS`; hide its
   translation section (`RelayFeatures.TRANSLATE`).
4. For Arabic or Hebrew, check RTL (`LocaleController.isRTL`, Relay's own screens).
5. `Tools/relay-e2e/test_polish2b.py` switches the app to each language and back; extend its word lists.
