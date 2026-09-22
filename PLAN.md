# Plan

Crimson: a streaming-service front end for Xtream Codes accounts on Android TV and Fire TV, built
on RetroGuide's engine. `PROGRESS.md` says where each item stands.

## Done (2026-09-22)

1. Fork and rename; fresh history.
2. Profiles with per-profile storage.
3. Design system and every page: profiles, Home/TV Shows/Movies, title pages, search, My List,
   Live TV, Browse by Country, Sports, player controls, guide re-skin, settings.
4. Recommendation engine: IMDb title index, catalogue enrichment, feed planner and SQL.
5. ESPN sports with game → channel search.
6. Mock server with a real-title VOD catalogue; device verification script for the new flow.
7. Screenshot tests for every main screen; full build green.

## Next, in order

1. **Run on a real Fire TV Stick against a real account**: `.\tools\verify-on-device.ps1 -Device
   <ip>:5555 -Reset`. Import time and memory for a large catalogue, frame times while scrolling
   rows, preview behaviour on a one-connection account.
2. Profile the row scrolling on that device if it is not smooth: likely candidates are poster
   decode size (Coil sizes from layout, but check), the spotlight recomposition, and the backdrop
   crossfade.
3. Trailers: `youtube_trailer` is parsed but not used.
4. Subtitles and audio tracks — still the intended next player feature (`PlayerController` is an
   interface for exactly this).
5. Kids profiles (filter the feeds by genre and age rating).
