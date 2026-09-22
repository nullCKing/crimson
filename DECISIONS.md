# Decisions

Every non-obvious choice, with the reason. Crimson's decisions come first; RetroGuide's follow,
newest last, and still govern the engine Crimson inherited.

## Crimson (2026-09-22)

### Scope and shape

- **A fork, not a branch.** The request was a new app with RetroGuide's bones and a different
  UI. RetroGuide's working tree (including its uncommitted On Demand/Browse work) was copied,
  renamed `com.retroguide` → `com.crimson`, and started a fresh git history; RetroGuide's own repo
  was not touched. Crimson has its own application id and signing key, so both install side by
  side.
- **The engine is kept whole.** Import, filter, numbering, the four EPG sources, the short-EPG
  fallback, the single ExoPlayer and the canvas guide grid were kept and still tested. Only the UI
  and the data it needed (profiles, catalogue metadata, progress, list) are new. The guide was
  re-skinned through `GuideTheme` values alone — no grid code changed.
- **No "On Demand" wording.** Films and series are "Movies" and "TV Shows", as the brief asked.

### Profiles

- **One profile = one Xtream login, with its own database and DataStore.** Two logins are usually
  two providers; nothing one imported means anything to the other. A shared database keyed by
  profile would have meant a profile column on every table and every query.
- **Databases stay open once opened** (`CrimsonDatabase.open` caches per profile). Closing one on
  a switch risks a query still in flight throwing on a closed database; a household has a handful
  of profiles.
- **Saving a profile signs in first**, so a profile that exists works, and the provider's own
  error text is shown when it refuses.
- **Avatars are drawn**, not shipped: eight colours × four faces on a Canvas, crisp at any size.

### Recommendations

- **An IMDb title index joined into the catalogue, not curated lists.** RetroGuide's 46 curated
  lists (2,717 titles) could only ever fill 46 rows. The title index (every film with ≥4,000
  votes and series with ≥2,000: ~31,000 titles, 1.5 MB) gives each cached title genres, rating
  and popularity, and every row becomes a query: "crime + thriller, most popular first",
  "rated ≥7.3 with 4k–60k votes, shuffled" (Hidden Gems). That is what makes the feed deep.
- **Applied as indexed UPDATEs in 500-title transactions**, streaming the asset, so neither the
  index nor the catalogue is ever in memory. Ascending-votes order means that when a provider's
  copy has no year, the most popular title of that name is applied last and wins.
- **Rows are data** (`FeedPlanner` → `RowSpec` → `CatalogSql`), unit-tested in `core`, resolved a
  few at a time as the viewer scrolls. Rows too short for an account are skipped silently, so the
  plan can list far more than any catalogue fills; the provider's own categories form the endless
  tail.
- **Foreign copies and adult content are flagged at import** (`isForeign`, `isAdult`) from the
  category and name, using the live filter's own foreign-marker list, and every feed query
  excludes them. Search still finds foreign copies, ranked below English ones.
- **One card per title**: queries fetch 3× the row size and `Mappers.dedupe` keeps the
  best-language copy (EN/US/UK label, then unlabelled, then anything else).
- **Titles already shown twice on a page drop to the back of later rows**, so scrolling keeps
  turning up something new instead of the same blockbusters in every genre row.
- **"Top 10" is honest about what it is**: the most-rated titles from the last two (films) or
  three (series) years that this account carries — a popularity chart, not a live one. No free,
  keyless trending API exists (TMDB and Trakt both need keys).

### Sports

- **ESPN's public scoreboard** (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`)
  because it is free, keyless, covers every major league and — the point — names the TV network
  per game. It is unofficial: the parser takes only the fields it needs, treats each as optional,
  and a failure shows "Scores are unavailable" rather than an error. Cached one minute per league.
- **A game opens a search, not a channel.** The request was "taken to a search output of the
  listed channel". `BroadcastSearch.termsFor` gives the national TV network first, then other
  networks, then streaming services providers re-broadcast (Prime, Peacock, ESPN+), then the
  teams; the search page falls through them until one finds a channel and says so.
- **Team streaming feeds are not channels.** ESPN lists "MLB.TV", "Brewers.TV" — anything ending
  in `.TV` or `+`, or "League Pass", is skipped as a search term unless nothing else is left.

### Live TV

- **American Cable by default**, with RetroGuide's Japanese and Korean packages as the other
  lineups, laid out as rows by the package's sections, plus "Local Channels" / "More from Japan"
  / "More from Korea" tails. "All Channels" is every provider category as a row.
- **Previews use the one player.** After the cursor rests 1.4 s on a channel it plays in the hero;
  Select then continues the same stream full screen with no re-tune. Leaving Live TV stops it. A
  setting turns previews off for accounts where the one connection is precious.
- **Browse by Country** groups every category by `WorldRegions.forCategory` (kept-country code,
  then foreign marker, then the name's own prefix). Categories RetroGuide's filter never imported
  are fetched on open (up to 2,000 channels, six categories cached) and played directly without
  entering the guide.
- **Up/Down zap through whatever the viewer tuned from** — a lineup row, a directory category,
  search results — not always the guide's list.

### Interaction and rendering

- **Focus is white; red is meaning.** A white ring and a lift on cards; white fill on buttons and
  chips. Red marks live, progress, the selected tab/chip and primary actions. A primary button is
  red at rest and white under focus: a white primary next to a white-focused secondary was
  indistinguishable in testing (the profile editor's Save/Cancel).
- **Rows pin their focused card at a fixed offset** (`PivotScroll`), vertically and horizontally,
  the way streaming apps do. The `BringIntoViewSpec` animation must be a spring: Compose
  recalculates the target every frame, and a tween restarted every frame crawled at ~1 px/frame.
- **Document pages reveal minimally** (`RevealScroll`). Android TV's own default spec is a 30%
  pivot, which on the title page scrolled the title off screen when Play took focus.
- **Initial focus is requested twice** (`InitialFocus`): once on the first frame and again after
  the route crossfade, because the outgoing page releases focus when it is disposed, after the
  new page has claimed it. The root is focusable only on Watching and Guide for the same reason.
- **Explicit vertical focus hand-off from lineup chips to channel cards** (`focusProperties { down = firstCard }`):
  Compose's geometric focus search on a horizontal chip row placed directly above a lazy column sent
  `Down` sideways to another chip rather than down into the channels. Explicit `focusProperties` routes
  `Down` straight to the first card.
- **Back on Home from the rows returns to the billboard first**, then leaves; the card the viewer
  left from is remembered per row (row id + title), because the same title appears in many rows.
- **No blurred shadows under focused cards.** Removing it took vertical scrolling on the emulator
  from 24 to 18 ms/frame and slow draw commands from 19 to 0. Backdrop scrims are drawn once over
  the crossfade instead of once per image, and the corner bloom is sized to its corner.
- **Text fields on a remote**: Up/Down leave the field; Left/Right leave it only at the text's
  edges (the caret position is tracked with a `TextFieldValue`), Select reopens the keyboard.
- **Channel logos fall back to the name in type** when missing or broken — providers' logo URLs
  are often dead, and a blank card is the one thing a channel card must not be.
- **Icons are drawn from Material path strings** (`CrimsonIcons`), not the extended icon artifact
  (megabytes for thirty icons).
- **Search draws its own keyboard**: the TV system keyboard covers the results it types for.

### Test data

- **The mock catalogue uses real titles from the title index** so the recommendation rows have
  something to match; names follow provider conventions, with French dubs and an adult category to
  prove the filters. Artwork is picsum.photos, seeded per title. A three-minute seekable MP4 with
  byte-range support stands in for every film and episode.

---

# RetroGuide (inherited)

## Environment

- **The APK is not built in the development container.** The container's egress policy returns 403
  for `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `services.gradle.org`,
  `plugins.gradle.org` and `repo.maven.apache.org`. Four routes were tried: direct download, the
  Ubuntu `android-sdk` packages, alternate Maven mirrors (Aliyun, Tencent, Sonatype), and GitHub.
  Only GitHub, PyPI and npm are reachable. Without maven.google.com there is no Android Gradle
  Plugin, Compose, Media3 or Room, so the Gradle build runs on the developer's own machine.
- **Kotlin compiler from a GitHub release instead of Gradle.** `kotlinc` 2.1.21 downloads from
  `github.com/JetBrains/kotlin/releases`, which the policy allows, and the distribution's
  `junit4` package supplies JUnit. That is enough to compile and run the `core` module, which has
  no Android or third-party dependencies. `tools/run-core-tests.sh` does this; `./gradlew :core:test`
  is the normal path once the build runs on a machine with network access to Google.
- **No Android emulator in the container.** `/dev/kvm` does not exist and `/proc/cpuinfo` reports
  no `vmx`/`svm` flags, so no AVD can start. On-device verification is done by
  `tools/verify-on-device.ps1`, run on the developer's Windows machine.
- **No real Xtream account.** `secrets/xtream.json` was never provided, so the discovery report,
  playback checks and import measurements run against the mock server and legal public test
  streams. Everything that needs the real provider is called out in `PROGRESS.md`.
- **Reference screenshots were not available.** `reference/` could not be reached, so the guide is
  built from the written descriptions in the build spec. The theme object exists partly so the
  look can be corrected in one place once the images are to hand.

## Architecture

- **As much logic as possible lives in a pure-Kotlin `core` module.** The spec only asks for the
  filter engine to be Android-free. Name cleaning, channel numbering, XMLTV time parsing, short-EPG
  decoding, programme categorisation, guide geometry and guide navigation are Android-free too.
  That is good design on its own, and in this environment it is also the difference between logic
  that is actually tested and logic that is only written.
- **Guide navigation is a pure state machine.** D-pad rules are the easiest part of a TV app to get
  subtly wrong and the hardest to verify from a screenshot. `GuideNavigator` turns every rule in
  spec section 5.3 into an assertion.
- **Base64 is implemented by hand in `ShortEpg`.** `java.util.Base64` needs API 26 and `minSdk` is
  25; `android.util.Base64` would pull an Android dependency into a module that is deliberately
  free of them.
- **XMLTV time parsing uses plain arithmetic, not `java.time`.** Keeps `core` free of API-level
  constraints and testable on any JVM. Howard Hinnant's `days_from_civil` is exact for every date
  this app will see.

## Filtering

- **Tokenising records whether a token was closed by punctuation.** This is what separates a
  country prefix from an English word. `DE| SPORT` has a hard boundary after `DE` and is German;
  `IN THE MIX` has a space and is a sentence. Without it, every category starting with "In", "It",
  "At" or "No" would be mistaken for India, Italy, Austria or Norway and skipped. Country codes of
  three characters or fewer therefore require a punctuation boundary; full country names do not.
- **Periods are resolved per token.** `L.A.` and `U.S.A.` are initialisms and collapse to `LA` and
  `USA`; `US.ESPN` splits, because its fragments are multi-letter. This is what lets
  `ST. PETERSBURG` still match as a two-word phrase.
- **`NETWORK` is not treated as a locals indicator, despite the spec listing it.** `USA NETWORK`,
  `CARTOON NETWORK` and `FOOD NETWORK` are national channels. The plural `NETWORKS` is kept, along
  with `LOCAL`, `LOCALS`, `AFFILIATE`, `DMA` and `OTA`.
- **The filter is deliberately asymmetric.** Wrongly calling a national channel "local" deletes
  something the user wanted; wrongly calling a local "national" leaves one extra row in the guide.
  So local verdicts need real evidence, and ambiguous city names are omitted from the "other
  market" lists entirely (Mobile, Jackson, Columbia, Charleston, Springfield, Madison).
- **`LA` and `NY` require corroboration.** Whole-token matching is not enough: `LA LIGA` and
  `LA CASA` are whole-token `LA`. They only count as markets when a call sign, a network name or a
  locals category agrees, and an explicit veto list covers the common Spanish-language phrases.
- **Word-like call signs are gated on the category.** `WAVE` (Louisville), `KING` (Seattle),
  `WISH` (Indianapolis) and `WOOD` (Grand Rapids) are real call signs and ordinary words. Outside a
  locals category the word reading wins, so `WAVE MUSIC` is not mistaken for a Louisville affiliate.
- **`AMERICA` is vetoed by `LATIN AMERICA`, `SOUTH AMERICA` and `AMERICA LATINA`; `KOREA` is vetoed
  by `NORTH KOREA`.** The spec's token lists are correct but incomplete on their own.
- **Recognising countries outside the allowlist is worth the extra list.** Telling "this is
  Germany" apart from "no idea what this is" is what lets a `DE | SPORT` category be skipped
  without downloading it, which is where the import saves most of its bandwidth. A category with no
  country marker at all must still be fetched, because its channel names may carry the country
  individually.

## Numbering

- **Channel numbers are never reclaimed.** Numbers are keyed on `stream_id` and, once handed out,
  survive a channel disappearing entirely, so a channel that comes back gets its old number. A
  viewer who has learned that 1042 is their channel is not served by dense numbering. Blocks are
  US 1000–4999, UK 5000–6999, JP 7000–8499, KR 8500–9999, with overflow from 10000.
- **New channels are numbered alphabetically.** Makes a fresh install's guide readable and makes
  two installs against the same provider produce identical numbering.

## Data layer

- **A streaming JSON reader was written rather than pulling in Moshi.** `android.util.JsonReader`
  is Android-only, and `core` has to stay Android-free so the filter is testable without an
  emulator and reusable by the offline discovery tool. Writing the parser also means the discovery
  report exercises the shipping code path rather than a second implementation of it.
- **Stream ids are `Long`, not `Int`.** Found by the ground-truth scoring: ids past
  `Int.MAX_VALUE` were clamping onto a single value and collapsing half the catalogue onto one
  key — a corruption that looks like working code. `JsonReader.nextInt` now returns 0 out of range
  rather than clamping, so the same mistake fails loudly next time.
- **Every imported channel stays in the database with its country and market.** That is what makes
  removing a filter a query rather than a re-import. Only adding one needs the network, and
  `CategoryEntity.imported` says which categories still need fetching.
- **Programmes are keyed on `channelKey`**, the provider's `epg_channel_id` where there is one and
  `sid:<stream id>` otherwise, so channels served only by the short-EPG fallback join the same way.
  Indexed on `(channelKey, startMs)`, because the windowed overlap query is the only one the guide
  makes and a full scan of a 450,000-row table per frame is not survivable on a Stick.
- **The window query is an overlap test, not containment.** `startMs < to AND endMs > from`. A
  three-hour film that began before the window opened still has to be drawn, with its left notch.
- **Blocking DAO variants exist alongside the suspending ones.** The streaming parsers hand over
  one record at a time through plain callbacks that cannot suspend, so flushing a batch *during* a
  category or a document needs a blocking write. Without it, peak memory would be bounded by the
  largest category a provider happens to have rather than by the batch size.
- **Room uses write-ahead logging.** The spec requires the guide to stay usable during an EPG
  refresh; WAL is what stops the writer blocking the readers.
- **`fallbackToDestructiveMigration`.** The database is a cache of the provider's data and is
  rebuildable by re-importing. Migrating a table the user has no unique data in would be work in
  exchange for nothing.

## Player

- **One `ExoPlayer`, created once, shared by full screen and the guide's preview.** Not an
  implementation detail: Xtream accounts are commonly sold with a single connection, so a second
  player would lock the user out of their own service. It also makes a channel change
  `setMediaItem` + `prepare` rather than a teardown.
- **Short live buffers** (600 ms for playback, 6 s maximum). The only thing a large buffer buys on
  a live stream is a longer wait after every channel change.
- **`STATE_ENDED` is treated as an error.** A live stream should never end; when one does, the
  source went away, and sitting on a frozen frame is worse than reconnecting.
- **Reconnect budget of 30 seconds with a doubling backoff** — about six attempts, frequent enough
  that a blip recovers unnoticed and spaced enough not to hammer a dead stream.
- **`FLAG_KEEP_SCREEN_ON` rather than `setWakeMode`**, which would need the `WAKE_LOCK` permission
  for no benefit on a mains-powered stick.
- **Playback sits behind `PlayerController`.** Captions are out of scope for this build and are the
  first thing anyone will want next; the interface is what makes that a change to one class.

## UI

- **The grid is drawn on a Canvas, not composed.** Nested lazy lists of variable-width cells give
  every cell its own layout and recomposition scope, and cell widths depend on programme durations
  so nothing can be reused between rows. Drawing makes the per-frame cost about forty rectangles
  and forty strings, with no view hierarchy and no recomposition when the highlight moves.
- **Outlined cell text is two draws, not eight.** A stroked pass then a filled pass, rather than
  drawing the string at eight offsets.
- **The guide cursor is anchored on a time, not on a programme id.** That is what makes Up and Down
  behave like a cable guide: moving down a row keeps you at the same moment in the evening even
  though the programme boundaries do not line up between rows.
- **Single activity.** The ExoPlayer surface lives in the composition; a separate guide activity
  would tear it down and rebuild it on the most common action a viewer takes.
- **Exclusion keywords are picked from a list rather than typed.** Free-text entry with a D-pad is
  miserable. The stored setting is plain text and accepts anything, so a keyboard can be added
  later without a data change.

## Signing and secrets

- **One release key, generated locally, never committed.** Android identifies an app by package
  name and certificate together, so changing the key means users must uninstall and lose their
  settings. `tools/make-keystore.{sh,ps1}` refuses to overwrite an existing keystore for that
  reason.
- **A missing keystore warns and falls back to the debug key** rather than failing the build. A
  developer who only wants to run the app should not have to create a signing key first.
- **Cleartext HTTP is allowed**, because most Xtream panels have no TLS at all and refusing it
  would make the app useless for its purpose. The trade-off is stated on the login screen rather
  than buried: on such a server the credentials travel in the clear.

## First build and first run on a device (2026-09-21)

Everything above was written blind. This is what the first compile, the first screenshot tests
and the first run on an Android TV emulator changed, and why.

- **Foojay toolchain resolver in `settings.gradle.kts`.** `core` pins `jvmToolchain(17)`; the
  build machine had JDK 21 and 23 but no 17, and Gradle refuses to build without a matching
  toolchain unless a download repository is configured. The convention plugin lets Gradle fetch
  one into `GRADLE_USER_HOME/jdks`, so the build works on any machine rather than only on one with
  the right JDK preinstalled. Every dependency version in `libs.versions.toml` resolved as written.
- **The release variant runs no JVM unit tests.** The only unit tests in `:app` are the Roborazzi
  screenshot tests, and `createComposeRule()` needs the `ComponentActivity` that
  `compose-ui-test-manifest` merges into the *debug* manifest. `testReleaseUnitTest` therefore
  fails on a missing activity and proves nothing; `build` and `:app:testDebugUnitTest` run the same
  seven tests once.
- **Room's journal mode is set through the builder, not with a PRAGMA in `onOpen`.** Android's
  `execSQL` throws for any statement that returns rows, and `PRAGMA journal_mode=WAL` returns one,
  so the first database open crashed. `setJournalMode(WRITE_AHEAD_LOGGING)` also matters on the
  target hardware: Room's `AUTOMATIC` mode falls back to TRUNCATE on a low-RAM device, which a 1 GB
  stick is.
- **The fill pass of the outlined cell text says `drawStyle = Fill` explicitly.** The measurer
  caches one paragraph for both passes, `AndroidTextPaint.setDrawStyle(null)` is a no-op, and the
  previous pass was the stroke — so every white title was drawn stroked too and unreadable. Seen
  in the very first screenshot test.
- **Login fields handle D-pad Up, Down and Select themselves.** `BasicTextField` reports Up and
  Down as consumed even in a single-line field, so once the on-screen keyboard is dismissed a
  remote can never leave the field. Select on the password field signs in, as the screen already
  promised; on the other fields it brings the keyboard back.
- **Focus starts on a Settings row, and the root only takes focus on Watching and the guide.**
  A Compose D-pad move searches the focused node's siblings and never descends into its children,
  so with the root `Box` holding focus nothing in Settings could be reached. The first row asks
  for focus once when it first appears; not again when scrolling brings it back into view.
- **The details dialog is modal.** The cursor used to keep moving under it.
- **`onRenderedFirstFrame` is recorded once per tune.** It fires again on every surface change —
  the preview window replacing the full-screen view, for one — and the extra lines were logged
  against a stale tune time, which would have skewed the time-to-first-frame measurement.
- **The filter round trip is timed inside the app**, from the toggle to the new channel list
  arriving, and logged. The script's stopwatch around the same step measures its own key delays
  and adb latency, not the query.
- **`verify-on-device.ps1` switches the on-screen keyboards off for the run** and restores them at
  the end. The Leanback keyboard opens as soon as a text field has focus and then swallows every
  D-pad press, so all three typed strings landed in the server field. `input text` injects key
  events and needs no keyboard. This is only about automation: a real remote types on that keyboard
  and its Next and Done keys drive the IME actions the fields already handle.
- **Screenshots are captured to `/sdcard` and pulled**, not redirected from `exec-out`. A
  PowerShell 5.1 `>` decodes a native command's output as text and re-encodes it, which turned every
  PNG into UTF-16 garbage.
- **The script leaves and reopens the guide before its tuning step.** Right skips whole
  programmes, so by then the window can be many hours ahead and Select would open the future-
  programme dialog again; reopening the guide lands on the current half hour deterministically.

## Retro chrome, mouse support, and the real provider's guide (2026-09-22)

The first session with a real Xtream account signed in (on the Android TV emulator), and the first
with a mouse in mind.

### Look

- **Every fill is a vertical gradient, every raised element has a bevel, every heading has a hard
  drop shadow.** That is what makes a 2000s cable guide look like one; flat colour reads as a web
  page. `ui/theme/RetroChrome.kt` draws these once for composed screens (`Modifier.retroPanel`,
  `RetroHeader`, `RetroButtonBar`, `RetroPage`) and `GuideGrid.kt` has a matching copy for the
  drawn grid (`drawRetroRect`). The two must agree; `GuideTheme` holds the numbers for both, and
  `GuideTheme.Flat` turns all of it off.
- **Cell text is a drop shadow now, not a stroke outline.** Still two draws, but the shadow is
  the more recognisable trait of the period and the stroke pass had the cached-paint trap
  described above. The measurer's paint state still has to be reset with an explicit `Fill`.
- **Scanlines are one rectangle with a repeating shader**, never hundreds of lines, and they
  are never drawn over full-screen video: a blended full-screen layer on every decoded frame is
  not something a Fire TV Stick can spare. Menu screens and the guide (including its small
  preview) get them.
- **The bottom button bar is the mouse's Back button.** A mouse has no Back, Menu, Play/Pause or
  Rewind. Rather than inventing gestures, every screen carries the row of coloured-key buttons a
  cable box had — and those are the remote's own functions, so a mouse user and a remote user
  can do exactly the same things. The buttons are not focus targets, so a D-pad never steps
  through them.
- **The guide lost some height to the button bar**: info panel 186 → 160 dp, preview 292×164 →
  224×126, rows 50 → 46 dp. Five rows plus the bar fit inside the safe area; nothing else moved.

### Mouse

- **Hover moves focus.** The app's highlight *is* focus, so a hovering pointer requests focus on
  what it is over and the yellow follows the mouse exactly as it follows the D-pad. There is no
  second highlight system. `ui/input/TvInput.kt` (`Modifier.tvInteractive`) is the one copy of
  "focusable + Select + click + hover" that every row, tile and button uses; the screens used to
  hand-roll it with small differences.
- **A click is a press and a release with no drag between**, measured against touch slop, so a
  finger scrolling a list does not "click" whatever row it lifts off. Right-clicks are left alone
  so the platform can keep mapping them to Back.
- **Callbacks are read through `rememberUpdatedState`** inside the pointer coroutine. Keying
  `pointerInput` on an inline lambda restarts the coroutine every recomposition, which dropped
  the press half of a click whenever hover-focus recomposed the row.
- **On the grid, hover moves the cursor and click acts.** `GuideGeometry.hitTest` is the
  inverse of `layoutRow` and lives in `core` with tests; `GuideNavigator.moveTo` clamps a pointer
  to the same half-hour floor a D-pad move obeys, so a mouse can never select the past. Touch has
  no hover, so a click moves and selects in one step. The wheel changes rows on the grid and
  channels while watching.
- **Star toggles and the mouse toolbar are click targets, not focus targets.** If they took
  focus the remote would have to step over them to reach anything. Play/Pause stars from the
  remote; the star buttons star from a mouse; both call the same view-model method.
- **The full-screen player shows a toolbar while the pointer moves** and hides it 3.5 s after it
  stops. Everything on it is a remote key the app already handles.
- **Number keys are direct channel entry.** The Fire remote has none, but keyboards and other
  remotes do. Digits accumulate for 1.8 s or until five of them — numbering overflows past 10000
  on a large catalogue, so four was wrong — then tune (while watching) or move the highlight (in
  the guide).

### The guide on a real account

- **Only 9.6% of channels carry an `epg_channel_id`** (1,069 of 11,130 on the account tested),
  so joining XMLTV to the channel list by id alone can never fill the grid. `XmltvImporter` now
  reads the `<channel>` elements and matches their `<display-name>`s (and the stem of the id,
  `SkyNews.uk` → `SkyNews`) to channel names through `core/epg/ChannelMatcher`, which reduces
  both sides to a key that ignores case, spacing, punctuation, country prefixes, quality tags and
  a leading group label (`TV| A&E ᴿᴬᵂ` → `AE`). A guide channel that already attached by id is
  still tried by name, because one guide channel usually serves several list channels.
- **A gzip body is detected by its magic number, not the header.** Some panels serve
  `xmltv.php` as a ready-made `.gz` with no `Content-Encoding`, and feeding gzip bytes to the XML
  parser looked exactly like an empty guide. `XtreamClient.readerFor` sniffs the first two
  bytes; this is the most likely reason the first import on the real account stored nothing.
- **The import writes a report.** `EpgReport` counts guide channels, matches by id and by name,
  programmes seen, kept, and dropped by reason, plus a sample of unmatched ids, and logs it as
  `RetroEpg`. One line of it is kept in settings and shown under "Refresh now", so the Settings
  screen can say *why* a grid is empty. A response that is not XML at all (an HTML error, a JSON
  `{"auth":0}`) is reported rather than parsed.
- **Most of a real list is not television channels.** Of 10,218 rows without guide data, the
  large majority were event slots (`US (ESPN+ 049) | NHL: BUF vs. PIT (2026-09-21 19:00:50)`)
  and 24/7 loops (`US| 24/7 JAMES BOND`, or `US| CITADEL ᴿᴬᵂ` in a `24/7 PRIME VIDEO`
  category). No feed anywhere has listings for those; the name *is* the listing. `core/epg/
  EventChannelEpg` turns a dated slot into a three-hour programme at the stamped time and a loop
  into an all-day programme, at query time in `GuideRepository`, only for channels with no
  stored data. The stamp has no zone, so it is read in the device's zone plus the manual guide
  offset — the best guess available, and the offset setting corrects it. Slots reading
  "NO EVENT" or dated 2098 are idle and get fillers. The short-EPG fallback skips these channels,
  since asking a provider for guide data on a PPV slot is a request per row for nothing.
- **`24/7` is stripped before name cleaning**, otherwise its slash split it into `24 7` and that
  became the short name of every loop channel. Small-caps and superscript decorations
  (`ᴿᴬᵂ`, `⁶⁰ᶠᵖˢ`) are all folded to ASCII now, not just `ᴴᴰ`; the tag list could not see them.
- **Numbers on this account run to five digits**, which the banner's number column and the
  channel-entry cap now allow.

## Guide data from more than one source (2026-09-22, later)

The provider's own XMLTV filled 888 of 11,130 channels. This is what was done about it, and what
was measured rather than assumed.

- **The public feeds are built in and on by default.** A viewer cannot be expected to know that
  the provider's Plex-derived channels are listed in `epg_ripper_PLEX1.xml.gz`; finding that out
  is research the app can do once, for everyone. `core/epg/EpgSource.kt` holds the list. It is a
  setting only so a metered connection can turn the downloads off, and turning it off deletes
  those rows as well, because a half-filled grid would look like a bug rather than a choice.
- **Every feed in the list was measured against the real account before being included**, and the
  list is ordered by yield per megabyte. These are the numbers from the app itself, on the
  account's 8,984 kept channels, of which the provider's own XMLTV filled 808:

  | Feed | Size | Channels filled |
  | --- | --- | --- |
  | Plex FAST channels | 5 MB | 186 |
  | US networks (`US2`) | 7 MB | 80 |
  | Pluto TV | 1 MB | 81 |
  | Samsung TV Plus | 1 MB | 78 |
  | Roku | 3 MB | 42 |
  | UK networks (`UK1`) | 3 MB | 38 |
  | Plex (full list) | 8 MB | 24 |
  | UK Freeview | 21 MB | 22 |
  | US sports | 1 MB | 13 |
  | South Korea | 1 MB | 10 |
  | Japan | 2 MB | 0 on this account, kept for others |
  | **Total** | **~50 MB** | **389 channels the provider had nothing for** |

  Channels with real listings went from 808 to 1,197, an increase of just under half. Re-scoring
  the feeds against the filled database afterwards found only 43 channels of headroom left, so
  the matching is getting essentially everything these feeds can give.
- **`US_LOCALS1` is deliberately excluded.** It is 55 MB — larger than every other feed put
  together — and filled two channels, because this provider names local affiliates by city rather
  than by call sign. Matching on call signs found inside the names was tried and is worse than
  useless: `WILL & GRACE` matches WILL-DT, `THE KING OF QUEENS` matches KING-DT and `DOCTOR WHO`
  matches WHO-DT, which would put a PBS schedule on a sitcom loop. Local affiliates need a call
  sign *and* a market to agree before they can be matched safely.
- **Substring matching between channel names was tried and rejected.** It reaches perhaps another
  270 channels and is wrong on most of them: `SKY CINEMA OSCARS` contains `CARS`, `BEIN SPORTS 4`
  contains `INSP`, and `FOX SPORTS WISCONSIN` matches plain `FOX SPORTS`, which would put the
  national feed's schedule on a regional one. Only whole-key equality is used.
- **The order of the sources is the priority, and the unique index enforces it.** The provider is
  always read first, then the feeds in list order, and a feed is only ever shown the channels that
  still have nothing — so the last feed parses against a handful of names instead of eleven
  thousand, and is skipped entirely when nothing is left to fill.
- **`programs` gained a unique index on (channelKey, startMs), which fixed a real bug.** The
  insert strategy had always been `IGNORE`, but with an auto-generated primary key there was
  nothing to conflict with, so every refresh appended the entire guide again. On the test device
  52,846 real programmes had become 217,548 rows and a 5 MB database had grown to 76 MB. A channel
  cannot show two things at one instant, so (channelKey, startMs) is a programme's natural
  identity; making it unique de-duplicates refreshes *and* is what stops a public feed overwriting
  the provider's own data.
- **Each row records which feed it came from**, so the Settings screen can say what each one
  contributed and so turning the feeds off removes exactly their rows.
- **A byte-order mark has to be consumed, not skipped.** The first run of the finished feature
  filled nothing at all from the highest-yield feed, reporting `Unexpected token (position: TEXT
  @1:2)` — which reads like a corrupt download and is not. `XmlPullParser` treats a leading U+FEFF
  as content before the prolog and abandons the document. The sniffer had been trimming the mark
  out of the *copy* it inspects while leaving it in the stream. Consuming that one character is
  the difference between 0 and 186 channels from Plex.
- **The feeds are fetched by their own client with no credentials.** `EpgFeedClient` shares
  nothing with `XtreamClient` except the gzip sniffing, and that is repeated rather than exported:
  one is about a provider's quirks, the other about ordinary files on a web server.

## Japan and Korea sports channels matching nothing (2026-09-22)

- **A provider that names a channel `J SPORTS 1 SPORTS` breaks the key.** Found on a real
  account: this provider's panel appends the category to the channel name it already carries
  (`JP| J SPORTS 1 SPORTS`, `JP| NHK EDUCATIONAL TV`), so `J Sports 1`'s key came out
  `JSPORTS1SPORTS` against the public Japan feed's `JSPORTS1` — a total mismatch, on all four
  J Sports channels, which is what actually carries NPB games. `NameCleaner.clean` now drops a
  trailing word that exactly repeats one already kept (`J Sports 1 Sports` → `J Sports 1`), which
  only fires on a literal repeat, so `Fox Sports` and `ESPN News` (one occurrence) are untouched.
- **This account's Korean channels are all prefixed `SKR|`, not `KR|`.** `SKR` was not in
  `COUNTRY_PREFIX`, so every Korean channel's *displayed* name carried it (`SKR MBC SPORTS TV`,
  `SKR KBS1 TV`) — a `ChannelMatcher.key` call still stripped it via `stripGroupPrefix` before the
  country-prefix list ever ran, so this was a display bug more than a matching one, but `SKR` is
  now recognised the same as `KR`/`KOR`/`KOREA` for both.
- **The Korean baseball (KBO) channels this account carries have no working match.**
  This account carries no channel with a name that reduces to a key any public Korea feed has
  under a baseball-carrying channel (`SPOTV`, `SPOTV2`, `KBS N Sports` are not in the lineup at
  all; the one sports channel present, `MBC SPORTS TV`, differs from the feed's `MBC SPORTS+` by
  a trailing `TV` that is not a repeat of anything earlier in the name, so the dedup above does not
  touch it and nothing safely can — that word is this provider's own choice, not noise). This is a
  content gap, not a bug: no feed can supply a schedule for a channel it does not carry.

### Listings made from the channel's own name, extended

After both the provider and the public feeds, about 4,300 channels still had nothing — and reading
them showed that most are not channels at all. The measured breakdown was 2,225 in PPV and sports-
package categories, 1,163 in streaming-service categories, 286 carrying a date in a format the
first version did not parse, and 28 prefixed `LIVE EVENT` or `ENDED`. So `EventChannelEpg` now
also reads:

- **`Thu 16 Oct 19:00` and `Mon 21 Sep 17:30 EDT`** — a day and month with no year. The year is
  inferred: this one, unless that would put the event more than a month in the past.
- **`LIVE EVENT 01 - 8pm WWE Monday Night RAW`, `NHL | 06 - 7:30pm Kraken at Flames`** — a time of
  day with no date at all, anchored to today.
- **`ENDED | ...`** — idle, like `NO EVENT`.
- **Show loops in streaming categories.** `US| LAW AND ORDER SPECIAL VICTIMS UNIT FHD` in a
  `CINEMANIA TV SHOWS` category is a channel that plays one series around the clock, and saying so
  is better than "No Information".
- **But a numbered slot is left alone.** `FITE TV 3`, `AMAZON UK EVENT 5`, `ESPN PLAY EVENTS 33`
  and `NETFLIX PREMIERE 6` are slot indices, not titles: a name qualifies as a title only if what
  precedes its trailing number is neither a slot word (`EVENT`, `PREMIERE`, `SERIES`, `REPLAY`…)
  nor nothing but brand names. `LAW AND ORDER 2` passes both tests and stays a title.
- A league name is **not** a sport prefix: `NHL: BUF vs. PIT` keeps its `NHL:`, while `live:` and
  `football:` are still stripped.


## Browse, On Demand and the curated lists (2026-09-22, later still)

- **Films and series became one On Demand screen.** They were two screens with the same layout,
  the same sidebar and the same dialogs, and nobody looking for something to watch decides first
  whether it is a film. The two old screens are gone; their details dialogs moved to
  `ui/vod/DetailsDialogs.kt` unchanged, because the dialogs were never the problem.
- **The on-demand catalogue is cached in the database.** It used to be fetched one category at a
  time, which is all the old screens needed. Search cannot work that way — a round trip per
  keystroke to a panel that takes a second to answer is not a search box — and neither can a
  curated row, which has to know what the viewer owns before it can show it. So the whole
  catalogue is streamed in once, in batches, holding one record at a time, exactly as the channel
  list is. Only what a poster and a search result need is stored; synopsis, cast and episode lists
  stay behind `get_vod_info` and `get_series_info` and are fetched when a title is opened.
- **Search uses `LIKE '%x%'` and scans the table.** It cannot use an index, and that is the right
  trade: the catalogue is tens of thousands of rows, a scan is a few milliseconds, and the
  alternative — an FTS table — doubles the storage and *still* cannot match inside a word, which
  is exactly what someone typing "bat" for Batman needs. Queries are debounced by 250 ms and need
  two characters, because one letter matches most of a catalogue and tells nobody anything.

### The curated lists

- **They are generated from IMDb's published datasets, not scraped from imdb.com.** IMDb
  publishes `title.basics` and `title.ratings` at <https://datasets.imdbws.com/> for personal and
  non-commercial use; that is the sanctioned way to get this data. Scraping the site is against
  their terms and the markup changes constantly, so a scraper would be both wrong and broken by
  next month. `tools/make-curated-lists.py` reads the datasets on a developer machine and writes
  `app/src/main/assets/curated_lists.txt`: 46 lists, 2,717 titles, 65 KB. The device never
  downloads a gigabyte of TSV, and the lists work offline with no API key and nothing to set up.
- **The vote floors are what keep the lists honest.** 25,000 votes for a film and 10,000 for a
  series, which leaves 7,286 films and 2,371 series from 1.7 million rated titles. Without a floor
  the "top rated" lists fill with obscure titles carrying nine votes and a 9.8 average.
- **A list is shown as the subset the viewer actually has.** The library is indexed once by title
  and every list resolved against that index — 2,700 hash lookups, not 46 scans of a
  twenty-thousand-title catalogue — and the index is dropped as soon as the rows exist, because it
  holds the whole catalogue and the rows hold only what is on screen. A row with fewer than four
  matches is not shown at all.
- **The year can only refuse a match, never require one.** Providers routinely label a film with
  its release year in one country and its premiere in another, so a difference of one year is
  accepted; two or more is refused, which is what keeps the 1961 *Parent Trap* off the 1998 one.
- **A title that ends in a number is indexed twice.** Nothing in `Blade Runner 2049` says whether
  the number is part of the title or a year a provider appended, so both readings are indexed and
  the year decides between them. A year after a full stop — `The.Dark.Knight.2008` — is packaging
  with no ambiguity, and is always stripped.

### Channel packages

- **A package is a lineup, and a lineup is a guide category.** `__pkg:us_cable` goes through the
  same code path as "all channels" or a provider group, so the guide, the favourites and the
  filter changes all work on it without knowing it exists. One function maps a category to its
  channels, because every place that does it has to agree — otherwise a filter change quietly
  drops the viewer back to the full list.
- **`American Cable` is about sixty channels and is meant to stay that way.** Broadcast, news, the
  general entertainment tier, kids, factual, music and a full sports tier: what an expanded basic
  package with sports actually carried. Adding "everything that might be interesting" is how it
  turns back into the nine thousand rows the package exists to escape.
- **Where a provider carries a channel several times the lowest number wins.** Numbering is
  alphabetical within a country block, so the first feed the import saw is almost always the main
  one; and a channel is never used for two lineup entries.

### What the first run against a real catalogue changed

Three things only a real account could have shown, all found by reading the cached data rather
than by looking at the screen:

- **The provider labels every title with a language and a dash**: `EN - Pulp Fiction (1994)`,
  `AR - Pulp Fiction`, `PL - PULP FICTION (1994)`. The prefix rule accepted `|` and `:` but not
  `-`, so every title kept its label and the IMDb Top 250 row matched five films out of 196,939.
  With the dash accepted it matches 85% of the curated films and 81% of the series. A dash is the
  riskiest separator to strip, so it is limited to a four-character single token followed by
  whitespace — `Spider-Man`, `X-Men` and `Alien - Covenant` all survive it.
- **The same film is carried once per language, all under one key.** `LibraryIndex` therefore
  takes a preference function, and the app prefers an `EN`/`US`/`UK` copy over a dub.
- **A cached key can go stale.** The keys are computed at import and stored, so improving the
  matching rules leaves a catalogue whose keys mean something slightly different from what the
  lists now ask for — and nothing on screen would say so. `CATALOG_KEY_VERSION` is stored
  alongside them and a mismatch rebuilds the catalogue by itself. Settings also has a manual
  "Rebuild the on-demand catalogue" for when a provider's own names change.
- **`get_vod_streams` with no category is not universally supported.** This panel closes the
  connection part way through a 196,000-title catalogue, so the importer falls back to asking
  category by category: more requests, but each response is one a loaded panel can finish.
