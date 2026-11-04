# YPN Browser — Android (Kotlin)

Mazeen's personal Android browser: a WebView browser with a built-in free
VPN and a signature dual-network download accelerator. **Personal use
only** — distributed as an APK via GitHub releases (repo
`sufii429/ypn-browser`), no Play Store, no backend, no accounts.

**Package / applicationId:** `app.ypn.browser`
**Version:** 1.1.2 · minSdk 26 · targetSdk 35 · Kotlin 2.0.21 · Compose BOM 2024.12.01
(Toolchain mirrors the Textoo Android app on purpose.)

## v1 — complete

YPN Browser 1.0 is the finished v1, all of it verified on Mazeen's
phone (the one outstanding check is the dual-network split test,
which needs a day when his home Wi-Fi is back):

- **Browser + themes:** tabbed WebView browsing (the first tab's
  WebView is pre-warmed at app start, and a tab's first load shows a
  real loading state — no more blank black screen), address bar with
  URL/search resolution, bookmarks and history, global Desktop mode,
  pop-up blocking, and the four mockup themes — selectable in the
  Menu, which is the single settings home.
- **Built-in VPN:** free VPN Gate relays over MS-SSTP (genuine TLS,
  TCP 443) via the embedded Open SSTP Client core, with the public
  `vpn` / `vpn` login, a pinned DNS resolver (1.1.1.1, carried inside
  the tunnel) for relays that push no DNS, two-layer failover
  (same-server retries, then the next servers), and a memory of
  servers that have worked before — listed first and preferred by
  Quick Connect.
- **Downloads:** a chunked download manager (pause/resume that
  survives restarts) plus the signature dual-network accelerator —
  one file pulled over Wi-Fi and mobile data at the same time, with
  per-network bytes measured and shown. A Menu toggle turns the
  accelerator off.
- **Install & updates:** every build is signed with the project's
  permanent debug keystore, so new builds install straight over the
  old one and app data survives. The latest build is always at the
  permanent link:
  https://github.com/sufii429/ypn-browser/releases/download/build-latest/ypn-browser.apk

**Known limits (honest ones):**

- Some VPN Gate relays don't offer MS-SSTP at all, or are offline —
  the public server list carries no SSTP flag, so a dead relay is
  only discovered by trying it. Failover and the worked-server
  memory are the mitigations.
- Servers that don't accept HTTP ranges make downloads
  single-stream, and there resume restarts the file from the
  beginning.
- Accelerated downloads travel outside the VPN tunnel **by design**
  (their sockets bind to the physical networks); browsing stays in
  the tunnel. Accelerated downloads also consume mobile data.
- VPN Gate is run by volunteers whose stated logging policy is
  about two weeks — fine for casual browsing and unblocking, not
  for banking or other sensitive accounts.

**1.0.1:** download-engine robustness, from the first real
dual-network test (a Wi-Fi network whose backhaul was dead left its
workers hanging while every byte arrived over mobile data). A chunk
a worker can't move is now handed back for the healthy network to
pick up instead of failing the whole download; the run only fails
if no network path makes progress for two minutes. The Downloads
screen also shows which network paths the app can see ("Paths —
Wi-Fi: ready · Mobile data: ready") while an accelerated download
is active.

**1.0.2:** the network pool no longer depends on the system's
requestNetwork callbacks, which on Mazeen's phone delivered nothing
at all for a whole download (both paths showed "not found" while
traffic flowed over the default Wi-Fi route). Handles are now also
discovered from the phone's live network list — once when a download
starts and again every second while it runs — so dual-network
engages even when the callbacks never deliver, a lost handle is
only given up when no equivalent network exists, and an unbalanced
pool release can no longer tear down a live registration. When the
phone rejects a network request, the Paths line now reports that
error instead of staying silent.

**1.0.3:** the reseeding in 1.0.2 worked (Wi-Fi showed "ready"),
and the new error reporting immediately named the remaining blocker:
every requestNetwork call was being refused with "SecurityException:
app.ypn.browser was not granted either of these permissions:
android.permission.CHANGE_NETWORK_STATE, …" — the manifest never
declared CHANGE_NETWORK_STATE, which Android requires before an app
may ask the system to bring up a second network (the cellular one)
next to Wi-Fi. The permission is now declared (normal, auto-granted,
no runtime prompt); the error note also keeps 120 characters so a
full system message fits.

**1.0.4:** file names now keep their real extension. The old
URLUtil.guessFileName replaced it with an extension derived from
the server's Content-Type, so a 10 GB movie hosted on a generic
server (application/octet-stream) was saved as "Aadarsha.bin"
instead of ".mkv". Names are now taken from the Content-Disposition
header or the URL path as-is; a MIME extension is only appended
when a name has no extension at all. Existing downloads can also
be renamed while paused, failed or completed (Downloads → Rename),
so a movie already saved as .bin becomes .mkv — file and progress
moved, no re-download.

**1.0.5:** when a network path fails, the Paths line now says why.
Both pool handles can be "ready" while one path still carries
nothing (a host bound to one IP can refuse the second network, or
its connections hang); the line now appends each path's last
failure — "Data error: HTTP 403" for a server refusal, or the
connection exception — and clears it as soon as that path writes
bytes again. Workers stuck on a dead path also stop stalling
chunks: after three chunk sequences without a single byte while
the other path is demonstrably working, a worker switches to the
healthy path for the rest of the download.

**1.1.0:** Pawxy-inspired UI polish, no functional changes. A "VPN"
chip now sits at the right end of the Home search bar and of the
browsing address bar — accent-filled while the tunnel is on — and
opens a bottom-sheet server list with the tunnel status, one
Connect/Disconnect button, "Worked before" servers on top and the
full list below (the Home VPN card still opens the full VPN
screen). Home's quick tiles are bigger Speed Dial tiles, and the
Menu is grouped Pawxy-style — small caps labels over rounded cards
(Appearance / Downloads / Browsing / Library), with a Downloads
row that carries the live active-downloads badge. All colours come
from the active theme, so all four themes render the new shapes.

**1.1.1:** literal pass against the approved mockup, from Mazeen's
screenshots of 1.1.0. The status-bar and navigation-bar icons now
follow the active theme (dark icons on Daylight, light icons on the
three dark themes — before, they vanished on dark backgrounds). The
Home quick tiles are the mockup's taller Speed Dial tiles, the
Menu's Appearance group picks themes by accent swatches, the
acceleration icon sits at the top of its row, and the Home/Menu
footers say 1.1.

**1.1.2:** a real launcher icon, replacing the mockup-era shield.
Mazeen's chosen artwork, traced as a vector adaptive icon: on a
deep-navy field, Wi-Fi arcs and five ascending mobile-signal bars
both feed one curved download arrow — the dual-network USP as a
glyph. The Home footer now carries the name's meaning as its
tagline: "YPN · Your Personal Network — two networks, one fast
download." (YPN = Your Personal Network, Mazeen's own expansion.)

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
  (one WebView per tab — the first pre-warmed at app start since
  Phase 4, the rest created lazily — destroyed on close) with a
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
- **Phase 4 — polish & release (done; v1.0.0).** The first-navigation
  blank-screen fix (first tab's WebView pre-warmed at app start,
  theme-surface background behind every page, and a loading overlay
  with the target host and real progress until the first page
  commits); the user-approved placement map (Home slimmed to address
  bar + compact VPN card + active-download card only while one runs
  + quick tiles; themes and settings live only in the Menu);
  worked-server memory for the VPN (servers that reached ON are
  remembered, listed first with a "✓ worked" marker, and preferred
  by Quick Connect); and the version-1.0.0 release itself.

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
