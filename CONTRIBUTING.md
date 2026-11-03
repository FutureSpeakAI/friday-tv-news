# Contributing to Friday News

Thanks for helping. Feed suggestions, fixes and TV reports are all welcome.

- **Tests:** `cd android && ./gradlew testReleaseUnitTest`. CI runs the same and a secret scan.
- **New outlets:** open an issue or a pull request that adds the feed to `Sources.java`, with its public RSS URL and the hosts its articles live on. Only outlets that publish a public feed; no scraping.
- **Stay an add-on:** News never holds keys and never adds third-party runtime libraries. Anything shared with Friday goes through the [add-on contract](https://github.com/FutureSpeakAI/agent-friday-tv/blob/main/docs/ADDON-CONTRACT.md); `AddonGuardTest` checks it.
- **Don't edit `android/addon-sdk` here.** Change it in agent-friday-tv, then sync it.
- **New pictures, sounds, fonts or other binaries** go in `ASSET-SOURCES.json` with their SHA-256, origin and license. `python scripts/publish_guard.py` (also run by CI) fails on anything unlisted, on anything generated for a household (stories, designed voices, reports, recordings, a child's answers), and on voice ids that aren't ElevenLabs' public premade voices.
- **Never commit secrets or personal data:** keys, keystores, real device addresses, logs, or screenshots of a real household.

By contributing, you agree that your contribution is licensed under this repository's MIT License.
