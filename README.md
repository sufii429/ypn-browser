# YPN Browser — Android (Kotlin)

Mazeen's personal Android browser: a WebView browser with a built-in free
VPN and a signature dual-network download accelerator. **Personal use
only** — distributed as an APK via GitHub releases (repo
`sufii429/ypn-browser`), no Play Store, no backend, no accounts.

**Package / applicationId:** `app.ypn.browser`
**Version:** 0.4.0 · minSdk 26 · targetSdk 35 · Kotlin 2.0.21 · Compose BOM 2024.12.01
(Toolchain mirrors the Textoo Android app on purpose.)

## Themes

Four selectable themes, taken from the approved design mockups
(artifact `browser-app-design-mockups`). The choice is saved on the
phone (SharedPreferences) and re-skins the whole app instantly.

| | Theme | Background | Surface | Accent | Wi-Fi / Data |
|---|---|---|---|---|---|
| A | Midnight Cyan | `#0B1220` | `#14233D` | `#22D3EE` | `#22D3EE` / `#34D399` |
| B | Ember | `#161110` | `#251B16` | `#FB923C` | `#FB923C` / `#FACC15` |
| C | Royal Purple | `#150A2E` | `#23134A` | `#A78BFA` | `#A78BFA` / `#F472B6` |
| D | Daylight | `#F2F4F7` | `#FFFFFF` | `#2563EB` | `#2563EB` / `#0D9488` |

## Phase plan

- **Phase 0 — skeleton (done).** Gradle project, theme system,
  and a static home screen ported from the mockups: VPN status card
  (ON · Japan · Tokyo · TCP 443 · Disconnect), dual-network download
  card with the split Wi-Fi/Data bar and combined-speed line,
  quick-access tiles, bottom navigation with a Downloads badge.
  Everything on screen is a placeholder sample — no browser engine,
  no VPN, no downloads yet.
- **Phase 1 — browser core (done).** A real WebView browser: tabs
  (one WebView per tab, created lazily, destroyed on close) with a
  tab-list screen; address bar that opens URLs or runs a Google
  search; bookmarks (star in the toolbar, list in Menu) and history
  (recorded automatically, clear-all in Menu) — both persisted on the
  phone; a global Desktop mode toggle (desktop user-agent, persisted);
  pop-up blocking (no multiple windows, JS windows refused); loading
  progress bar; system Back walks page history first. Home screen
  search bar and quick tiles browse for real, and its tab count is
  live. (File downloads toasted "Downloads arrive in Phase 3" until
  Phase 3 wired the listener to the real download manager.)
- **Phase 2 — built-in VPN (done).** Free **VPN Gate** servers over
  **MS-SSTP** (genuine TLS on TCP 443). Decided by Mazeen's 2026-11-03
  phone tests on mobile data: WireGuard is UDP-blocked by his carrier
  and plain OpenVPN-over-TCP gets connection-reset, while MS-SSTP
  connected and opened a Pakistan-blocked site. Engine: the
  open-source **Open SSTP Client** core (kittoku, MIT, Kotlin — built
  for SoftEther servers, which is what VPN Gate servers are), embedded
  in-app in its original `kittoku.osc` packages (license:
  `third_party/Open-SSTP-Client-LICENSE`). The VPN screen shows the
  live VPN Gate relay list (fetched from vpngate.net, cached on the
  phone, sorted by score; every server is reached as
  `<hostname>.opengw.net` on TCP 443 with the public `vpn` / `vpn`
  login built in). Tapping a server connects; the home card mirrors
  the tunnel state with a working Connect / Disconnect. Drops are
  handled twice over: the engine retries the same server, and if the
  service still dies YPN walks down the list to the next servers
  (bounded attempts). One honest limit: OSC reports failure only by
  ending the service, so failover reacts to service stop, not to a
  specific error message.
- **Phase 3 — downloads + accelerator (done).** A real download
  manager: browser download links and pasted URLs enqueue downloads
  that run under a foreground service, survive app restarts (as
  paused, with progress), and land in the app's own Downloads folder
  (opened via FileProvider, no storage permission). Servers that
  accept HTTP ranges get multi-part downloads — the file is split
  into ~2 MB chunks written in parallel into one preallocated `.part`
  file, with per-chunk counters, so pause/resume continues exactly
  where it stopped. Servers without ranges fall back to a single
  stream; there, pause keeps the partial bytes but resume restarts
  the stream from the beginning (no mid-file continuation exists
  without ranges). The signature accelerator: chunk workers bind
  their connections to the physical Wi-Fi and cellular networks
  (cellular is requested explicitly, since Wi-Fi is normally the
  default route), so one file is pulled over **both networks at
  once** and the UI's split bar and speeds come from bytes actually
  counted per network. Sockets bound to a physical network bypass the
  system VPN, so accelerated downloads travel outside the SSTP tunnel
  **by design**; with the accelerator off (Menu toggle), downloads
  use the default route like everything else. If only one network is
  available, downloads simply run on it and the UI says so. Note the
  toggle's flip side: accelerated downloads consume mobile data.
- **Phase 4 — polish & release.** GitHub Actions build, release APK,
  on-phone verification.

## Third-party components

- **Open SSTP Client** by KOBAYASHI Ittoku (kittoku) —
  https://github.com/kittoku/Open-SSTP-Client — MIT License
  (full text: `third_party/Open-SSTP-Client-LICENSE`). Its core
  (everything except its own UI) ships in this app under
  `app/src/main/java/kittoku/osc/`, with two adaptations: resource
  references point at this app's `R` class, and the
  untrusted-certificate notification is informational only (upstream
  offers to save the certificate through its settings UI, which YPN
  does not embed).

## Notes

- Like Textoo, this project carries no `gradlew` script or
  `gradle-wrapper.jar` (binary files can't travel the text-only push
  route) — builds run on GitHub Actions, which provisions Gradle 8.9
  from `gradle/wrapper/gradle-wrapper.properties`.
- Android allows only one VPN at a time: if another VPN app is
  connected, YPN's VPN waits; the download accelerator is unaffected.
- Volunteer VPN Gate servers log for ~2 weeks per their stated policy —
  fine for casual browsing/unblocking, not for sensitive accounts.
