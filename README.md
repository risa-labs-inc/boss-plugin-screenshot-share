# boss-plugin-screenshot-share

Capture a screen region, annotate it (pen / rectangle / arrow / text), and send it to a
teammate in one of your BOSS organisations. Built from `boss-plugins/docs/plugin-template`.

## What's here vs. what's in BossConsole

Plugins only get read-only `select`/`rpc` access to Supabase and no Storage/realtime — see
`BossConsole/AGENTS.md` ("Nothing on PluginContext exposes the Supabase access token"). Sending a
screenshot to another user therefore needed new backend objects, added in
`BossConsole/supabase/migrations/20260824000000_screenshot_shares.sql`:

- `screenshot_shares` table — the annotated PNG lives inline as `bytea` (there's no Storage upload
  path available to a plugin), capped at 8MB, auto-expiring after 14 days.
- `share_screenshot`, `list_received_screenshots`, `list_sent_screenshots`, `get_screenshot_image`,
  `list_shareable_recipients` — SECURITY DEFINER RPCs, mirroring the `secret_shares` convention.
- Recipient scope: only users who share an **active** organisation membership with the sender.
  There's no "any BOSS user" directory today.

That migration needs to be applied to the BOSS Supabase project before this plugin's send/inbox
features will work (`supabase db push` or the normal BossConsole migration deploy path). Nothing in
this plugin's own build depends on it directly — the RPC calls will just fail at runtime with
"Not authenticated" / a Postgres "function does not exist" error until it's deployed.

## Delivery model

No realtime channel is exposed to plugins, so the inbox panel **polls**
`list_received_screenshots` every ~25s (`ScreenshotShareViewModel`) and shows an unread badge. This
is deliberately simple — good enough for teammates checking in periodically, not instant push.

## Two API shapes assumed but not verified

`boss-plugin-api` is a git submodule pointing at a private repo and wasn't checked out in this
workspace, so two call sites are typed against the best available documentation
(`boss-plugins/docs/plugin-api.md`) rather than the real interface:

1. `SupabaseDataProvider.rpc(function: String, parameters: String): Result<String>` in
   `ScreenshotShareApi.kt` — assumed to take/return JSON text.
2. `NotificationProvider.showToast(message: String)` in `ScreenshotShareViewModel.kt` — the doc
   only confirms the method name.

Build against the real `boss-plugin-api` jar first and fix these two call sites if the compiler
disagrees — everything else in the plugin only touches `java.awt`/`javax.imageio`/Compose
Foundation, which don't have this risk.

## Build & deploy locally

Same as any BOSS plugin (`docs/creating-a-plugin.md`):

```bash
./gradlew clean buildPluginJar        # needs a sibling ../boss-plugin-api checkout, or CI=true
cp build/libs/boss-plugin-screenshot-share-0.1.0.jar ~/.boss_debug/plugins/
rm -rf ~/.boss_debug/plugin-cache/ai.rever.boss.plugin.dynamic.screenshotshare
# restart the dev-mode BOSS host
```

## Not yet done (follow-ups, not started here)

- This directory is plain files, not yet its own git repo / `boss-plugins` submodule
  (`risa-labs-inc/boss-plugin-screenshot-share`) — creating a new GitHub repo wasn't done without
  asking first, since it's an external, shared-state action.
- The Supabase migration hasn't been applied to any environment.
- No CI workflow (`.github/workflows/build.yml`) — copy the one from `docs/plugin-template` once
  this has its own repo.
- v1 has no "reply" / "forward" / save-to-disk from the viewer window, and text annotations are
  fixed-size (no font-size control).
