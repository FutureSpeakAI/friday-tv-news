# Friday News: an Agent Friday™ TV add-on

**The day's news on your TV, read aloud by Friday.** Headlines from public news outlets, grouped so you can see how several outlets tell the same story, with a reader, autoplay, a morning briefing, a kids filter and your local weather.

Friday News is an add-on for [Agent Friday for TV](https://github.com/FutureSpeakAI/agent-friday-tv). It's its own small app, built on Friday's [add-on contract v1](https://github.com/FutureSpeakAI/agent-friday-tv/blob/main/docs/ADDON-CONTRACT.md). Once it's installed and switched on, it appears on Friday's rail, and Friday opens it when you say things like *"show me the news"*, *"play the tech headlines"* or *"good morning"*.

## Install

The easy way is the Agent Friday TV setup wizard: answer **yes** when it offers *Friday News*. It installs the add-on and switches it on in Friday.

By hand, with a TV you've already set up:

```bash
adb install -r friday-tv-news.apk
adb shell content call --uri content://ai.futurespeak.friday.tv.keys --method addon --arg enable:ai.futurespeak.friday.tv.news
```

Or switch it on from the TV: *Agent Friday > Settings > Family > Friday News (add-on): on* (needs the grown-up PIN).

**Kids mode:** News has its own kids filter, but it appears in kids mode only after a grown-up switches on *Friday News: OK for kids* in the same place.

## What it does

- **Sections:** Top stories, U.S., World, Business, Science and Technology, from 32 public feeds:
  - **U.S.:** NPR, PBS NewsHour, The New York Times, ABC News, CBS News, The Guardian
  - **World:** BBC News, NPR, Al Jazeera, The Guardian, DW, PBS NewsHour
  - **Business:** NPR, BBC News, CNBC, MarketWatch, The New York Times, PBS NewsHour
  - **Science:** NPR, BBC News, Science News, NASA, New Scientist, Quanta Magazine, The Guardian
  - **Technology:** Ars Technica, The Verge, BBC News, NPR, WIRED, MIT Technology Review, TechCrunch
- **Story groups:** when several outlets cover the same story, News shows it once and lets you compare their takes.
- **Listen:** Friday reads the stories in her own voice when the owner shares it, or in the TV's voice otherwise.
- **Ask Friday about this:** hands the story to Friday, who explains the background and where the outlets differ.
- **Make a podcast:** hands the story to Friday Studio. Studio asks before it makes or spends anything.
- **Weather:** shown only if the owner chose a place during setup. Then Open-Meteo gets that place's coordinates, and nothing else.
- **Local news:** there are no built-in local feeds, so News works the same anywhere. Local and regional sections are reserved in the code for a later version.

The feed list is fixed in [`Sources.java`](android/news/src/main/java/ai/futurespeak/friday/tv/news/Sources.java), and News fetches only from those hosts. To suggest an outlet, open an issue with its public RSS feed.

## Privacy

- **No telemetry, no ads, no third-party SDKs.**
- **No keys, ever.** Friday holds the keys. News asks Friday for her voice and gets audio back. A test fails the build if News code mentions a key.
- **What leaves the TV:** requests to the news outlets' public feeds and article pages, and the weather request (if a place is set). Story text goes to the AI provider only when you choose *Ask Friday* or *Make a podcast*, and then through Friday.
- News stores what you've read and saved on the TV only.

## Third-party AI services

*Ask Friday* and Friday's voice use the AI services the TV's owner connected with their own keys (for example Anthropic and ElevenLabs). Each has its own terms and privacy policy, which you agree to with them directly. AI summaries can be wrong; the outlet's own article is the source.

## Build from source

You need Java 17 and the Android SDK.

```bash
cd android
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `android/news/build/outputs/apk/release/`. Without a keystore, it's signed with your computer's debug key; see `android/signing.gradle` to use your own. Never commit a keystore.

`android/addon-sdk` is a copy of the SDK from agent-friday-tv at contract v1. Don't edit it here. Change it upstream, then refresh it with `python scripts/sync_sdk.py ../agent-friday-tv`.

## Troubleshooting

- **News isn't on Friday's rail.** It's installed but not switched on. Use the `enable` command above, or Friday's Settings.
- **"Asking Friday needs Agent Friday on this TV."** Friday isn't installed, or News isn't switched on in it.
- **No weather.** No place was chosen. Run the setup wizard's Keys step again and give a town.
- **A feed is empty.** Outlets change their feeds; News skips a failing feed and shows the rest. Please open an issue.

## License

[MIT](LICENSE), Copyright (c) 2026 FutureSpeak.AI. Third-party parts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Security: [SECURITY.md](SECURITY.md).

News headlines, summaries and images belong to their publishers. Friday News links to and quotes them as a reader would, and doesn't republish them.

Agent Friday™ and FutureSpeak.AI™ are trademarks of FutureSpeak.AI. Other names belong to their owners.
