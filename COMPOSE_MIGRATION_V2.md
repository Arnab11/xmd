# XMD: Compose Migration — Plan v2

Old doc (`COMPOSE_MIGRATION.md`) claimed Phase 6 (NavHost, Fragment
deletions, ChallengeActivity rewrite) was done. **It was not** — verified
against actual git HEAD (`30cfefc`) and the zip. That doc describes work
that was planned/drafted but never landed in code. Kept as historical
reference only — do not trust its "done" claims without re-verifying
against the actual files first.

**Actual current state (verified):**
- Settings screens (Phase 1), Bookmarks/History (Phase 2), Downloads/Queue
  (Phase 3), Shortcuts (Phase 4), Browser chrome pieces — SniffedMediaSheet,
  AddressBarSuggestions, DnsSettingsDialog, LinkContextMenu, TabsListOverlay
  (Phase 5 steps 1-6) — all genuinely done, composables exist and are wired.
- Still XML/Fragment-based, confirmed by grep, nothing Compose yet:
  - `SettingsActivity.kt` — still `supportFragmentManager` + `addToBackStack`,
    no NavHost
  - `MainActivity.kt`'s `showAddDownloadDialog`, `showAddTorrentDialog`,
    the `dialog_quality_picker.xml` inflate, `TorrentFileAdapter.kt`
  - `ChallengeActivity.kt` — plain `setContentView(R.layout.activity_challenge)`
  - `BrowserFragment.kt` (1694 lines) + `fragment_browser.xml` — still has
    9 real XML view types alongside its 5 existing ComposeView hosts
    (EditText, FrameLayout, ImageButton, ImageView, LinearLayout,
    ProgressBar, TextView, View, SwipeRefreshLayout, MaterialCardView,
    FAB×2, LinearProgressIndicator — full list, minus the ComposeViews
    already there)
  - `DownloadsFragment.kt` — already a ComposeView wrapper internally, but
    still lives on `fragmentContainer`'s manual Fragment
    add/hide/show — never became a NavHost route

**Ground rule for every phase below:** no local Android SDK/Gradle in this
environment — every phase ends with a manual cross-reference check (no
dangling references to anything renamed/deleted) in place of a real
compile, same as the old doc did. Flag this in the phase's own summary,
don't skip it.

**Sizing rule:** each phase below is scoped to fit in one Claude session —
one feature area, not "convert file X" as a sub-step of something bigger.
Do not combine phases even if both look small; each gets its own session so
a build/verify checkpoint exists between them.

---

## Phase A — MainActivity's Add-Download/Add-Torrent dialogs

**Scope:** convert `showAddDownloadDialog()`, `showAddTorrentDialog()`, and
the `dialog_quality_picker.xml` inflate (all in `MainActivity.kt`) plus
`TorrentFileAdapter.kt` (94 lines) to Compose `AlertDialog`s, following the
same pattern Phase 3/4 already used for Downloads'/Shortcuts' dialogs in
this codebase (state as `by mutableStateOf`, hosted in `mainDialogHost`
— the `ComposeView` already added in the old doc's Phase 5 Step 4 for
`DnsSettingsDialog`, just add more branches to it).

**Do:**
1. Read `MainActivity.kt` lines ~850-1250 (`showAddDownloadDialog`,
   `showAddTorrentDialog`) and the `dialog_quality_picker` inflate site in
   full before writing anything.
2. Read `TorrentFileAdapter.kt` in full.
3. Design state shape (what needs to be `mutableStateOf`, what stays a
   plain param/lambda) — confirm with user before writing code per their
   standard workflow (AskUserQuestion first).
4. Build the three dialogs as composables, add as new branches in
   `mainDialogHost`.
5. Delete `TorrentFileAdapter.kt` once its RecyclerView usage is fully
   replaced by a Compose list inside the new dialog.

**Dead after this phase (do not delete mid-phase, note only):**
```
app/src/main/res/layout/dialog_add_download.xml
app/src/main/res/layout/dialog_add_torrent.xml
app/src/main/res/layout/dialog_quality_picker.xml
app/src/main/res/layout/item_torrent_file.xml
```

**Out of scope:** anything else in `MainActivity.kt` — toolbar, header
search, bottom nav, fragment transactions, other dialogs it owns.

---

## Phase B — ChallengeActivity rewrite

**Scope:** smallest phase, good warm-up/checkpoint. `ChallengeActivity.kt`
(188 lines) — chrome (header + status line) to Compose, WebView stays
wrapped in `AndroidView` (non-negotiable, Compose has no native WebView).

**Do:**
1. Read `ChallengeActivity.kt` in full — note the polling/JS-bridge/timeout
   logic, this must not change, only the View↔Compose plumbing around it.
2. Convert `AppCompatActivity` + `setContentView(R.layout.activity_challenge)`
   → `ComponentActivity` + `setContent {}`.
3. WebView instance via `remember { WebView(context) }` wrapped in
   `AndroidView`, replacing the `lateinit var` field.
4. Status line becomes `mutableStateOf` instead of `TextView.text` write.

**Dead after this phase:**
```
app/src/main/res/layout/activity_challenge.xml
```

---

## Phase C — SettingsActivity → NavHost + retire Settings/About Fragments

**Scope:** the actual "Phase 6" work the old doc only drafted. Bigger than
A/B — this is its own full session, don't combine with anything else.

**Do:**
1. Read `SettingsActivity.kt` in full (`openCategory`, `addToBackStack`,
   `EXTRA_OPEN_CATEGORY`/`CATEGORY_YOUTUBE` deep link, website
   import/export SAF launcher) before writing anything.
2. Read all 7 Fragments being retired in full:
   `SettingsRootFragment.kt`, `SettingsAppearanceFragment.kt`,
   `SettingsConnectionsFragment.kt`, `SettingsDownloadsFragment.kt`,
   `SettingsBrowserFragment.kt`, `SettingsYoutubeFragment.kt`,
   `AboutFragment.kt` — each is a thin `ComposeView` wrapper around an
   existing `*Screen.kt` composable (those composables are NOT touched,
   only their Fragment hosts are retired).
3. Rewrite `SettingsActivity.kt` as `ComponentActivity` + Compose root:
   self-drawn header (back button + title) + `navigation-compose` `NavHost`,
   one route per category. Title tracks
   `currentBackStackEntryAsState()`. Deep-link handling via
   `LaunchedEffect` on first composition.
4. Website import/export stays Activity-owned, passed into the Browser
   route as plain lambdas (no more `Fragment.Callbacks` interface needed).
5. Confirm each `*Screen.kt` composable's existing signature works
   unchanged as a route body before assuming it does.
6. Delete all 7 Fragment files + `activity_settings.xml` only after the
   NavHost route wiring is confirmed to cover every one of them.

**Cross-reference check after:** grep for any remaining
`SettingsRootFragment`/`SettingsAppearanceFragment`/etc. references
anywhere in `app/src/main/java` — should be zero.

**Dead after this phase:**
```
app/src/main/java/com/invictus/xmd/ui/SettingsRootFragment.kt
app/src/main/java/com/invictus/xmd/ui/SettingsAppearanceFragment.kt
app/src/main/java/com/invictus/xmd/ui/SettingsConnectionsFragment.kt
app/src/main/java/com/invictus/xmd/ui/SettingsDownloadsFragment.kt
app/src/main/java/com/invictus/xmd/ui/SettingsBrowserFragment.kt
app/src/main/java/com/invictus/xmd/ui/SettingsYoutubeFragment.kt
app/src/main/java/com/invictus/xmd/ui/AboutFragment.kt
app/src/main/res/layout/activity_settings.xml
```

**Out of scope:** `MainActivity.kt`, `BrowserFragment.kt`,
`DownloadsFragment.kt` — untouched this phase.

---

## Phase D — MainActivity overlay NavHost for History/Bookmarks + retire their Fragments

**Scope:** depends on Phase C being done first (reuses the same
navigation-compose dependency/pattern). `HistoryFragment.kt` and
`BookmarkFragment.kt` are both already thin `ComposeView` wrappers
(confirm zero `findViewById`/`lateinit var` before retiring, same check
the old doc used).

**Do:**
1. Read `MainActivity.kt`'s current `openHistoryScreen()`/
   `openBookmarksScreen()` and however they currently push
   `HistoryFragment`/`BookmarkFragment` via `supportFragmentManager` +
   `addToBackStack`.
2. Add a second `ComposeView` (`overlayNavHost`) as a new sibling in
   `activity_main.xml`, `GONE` unless a route is pushed, layered above
   `fragmentContainer` and `mainDialogHost`.
3. Small `NavHost` with routes `EMPTY` (never visible, common root)/
   `HISTORY`/`BOOKMARKS`. `popUpTo(EMPTY)` on navigate, not a fixed
   `startDestination` — either screen can be opened first from Browser's
   overflow menu.
4. System back pops the overlay's stack first before falling through to
   existing Fragment-backstack/Browser-tab checks — confirm this ordering
   explicitly, don't assume.
5. Inline the old `HistoryFragment.Callbacks.openInBrowser`/
   `BookmarkFragment.Callbacks.openBookmarkInBrowser` hand-off logic (pop
   overlay, `browser?.openUrl()`, switch to Browser tab) into each route's
   `onTap` lambda.
6. Delete `HistoryFragment.kt`/`BookmarkFragment.kt` +
   `fragment_history.xml`/`fragment_bookmarks.xml` only after confirming
   both had zero `findViewById`/`lateinit var` (pure ComposeView wrappers).

**Cross-reference check after:** grep for `HistoryFragment`/
`BookmarkFragment` — should only remain in `MainActivity`'s
`implements ... HistoryFragment.Callbacks, BookmarkFragment.Callbacks`
signature, which must also be cleaned up (remove the now-dead interface
implementations).

**Dead after this phase:**
```
app/src/main/java/com/invictus/xmd/ui/HistoryFragment.kt
app/src/main/java/com/invictus/xmd/ui/BookmarkFragment.kt
app/src/main/res/layout/fragment_history.xml
app/src/main/res/layout/fragment_bookmarks.xml
```

**Out of scope:** `fragmentContainer`'s Downloads/Browser mechanics —
untouched, per the old doc's own reasoning (hand-tuned IME/swipe/badge
code, high risk, no benefit this phase).

---

## Phase E — BrowserFragment chrome, part 1: toolbar + address bar row

**Scope:** first real cut into `BrowserFragment.kt`. Do NOT attempt the
whole file in one phase — 1694 lines, WebView pool, tabs, DNS, sniffer all
live here. This phase is toolbar + address bar row ONLY.

**Do:**
1. Read `BrowserFragment.kt` in full first, even though only a slice of it
   is in scope this phase — the WebView pool / tab state it reads from
   (`BrowserViewModel`, already extracted per the old doc's Phase 5 Step 1)
   needs to be understood before touching anything that reads it.
2. Identify exactly which XML views in `fragment_browser.xml` belong to
   the toolbar/address-bar row (likely the `EditText`, some `ImageButton`s,
   `LinearProgressIndicator` — confirm by reading the XML, don't assume
   from this list).
3. Convert that row to a composable, hosted the same way
   `AddressBarSuggestions` already is (own `ComposeView`, anchored
   position) — do not route through `browserDialogHost` (that's for
   Dialog-window popups only, per existing pattern).
4. Wire WebView progress/URL/title state (already on `BrowserViewModel`)
   into the new composable via `collectAsState`/plain reads — confirm
   whether `BrowserViewModel`'s tab state is Flow-backed yet or still
   plain `mutableListOf`/`var` before assuming `collectAsState` works;
   convert to `StateFlow` first if needed, same treatment Phase 2/3 gave
   Bookmark/History/Queue repositories.

**Out of scope:** WebView itself, tabs tray trigger, DNS settings trigger,
sniffer trigger, speed dial — those stay wired to the existing chrome for
now, only their host row's rendering changes.

**Not deletable this phase** — do not remove any XML yet, this phase only
adds a ComposeView row; old row's visibility may need to stay togglable
until Phase F confirms nothing else depends on it.

---

## Phase F — BrowserFragment chrome, part 2: remaining chrome + WebView container

**Scope:** everything left in `fragment_browser.xml`'s XML view list after
Phase E — tab-switcher trigger button, FABs, any remaining `LinearLayout`/
`MaterialCardView` chrome, `SwipeRefreshLayout` wrapping the WebView.

**Do:**
1. Re-read `fragment_browser.xml` fresh (do not reuse Phase E's read —
   Phase E likely changed it) to get the current accurate remaining-XML
   list before planning this phase's scope.
2. `SwipeRefreshLayout` + `WebView` container: WebView itself MUST stay
   `AndroidView`-wrapped (non-negotiable per every prior phase's note) —
   only the surrounding chrome (FABs, any remaining buttons) becomes
   Compose.
3. Convert remaining triggers to Compose, keeping their existing lambda
   wiring into `BrowserFragment`'s existing functions
   (`showTabsOverlay()`, `showDnsSettingsDialog()` equivalent, etc.) — this
   phase is chrome-only, not a rewrite of what those functions do.
4. Once every XML view in `fragment_browser.xml` is gone except the
   `AndroidView`-wrapped WebView container itself, confirm via grep that
   no dangling `findViewById` calls remain in `BrowserFragment.kt` for
   anything just removed.

**Dead after this phase (confirm via grep first, same pattern every prior
phase used):**
```
app/src/main/res/layout/fragment_browser.xml   (only if fully replaced —
  otherwise keep whatever minimal XML shell still hosts the AndroidView
  WebView container)
```

**Out of scope:** the WebView pool itself (`webViews`/`webViewStates`
maps), tab-switching logic, DNS client, sniffer — none of that logic
changes, only its trigger UI.

---

## Phase G — DownloadsFragment → NavHost route (optional, lowest priority)

**Scope:** fold `DownloadsFragment` (already a ComposeView wrapper
internally) into `fragmentContainer`'s Fragment mechanics being retired —
lowest priority since, per the old doc's own note, this means rebuilding
hand-tuned nav-bar/IME/swipe-gesture/badge code for a tab that already
works. Only do this phase if there's a concrete reason (e.g. Phase F makes
BrowserFragment a NavHost-compatible shape too and folding both in one go
becomes worthwhile) — otherwise leave `DownloadsFragment` exactly where it
is indefinitely.

**Do not start this phase without re-confirming it's still wanted** —
re-check with user before beginning, since it's explicitly marked
optional/deferred.

---

## Order

A → B → C → D → E → F → (G optional, revisit later)

A and B have no dependency on each other or on C/D — could run in either
order, but keep A before B if only picking one to do first (A retires more
dead XML, higher value for the same "small" size).

C must come before D (D reuses C's NavHost pattern).

E must come before F (F needs E's chrome extraction done first to know
what's actually left).

---

## After every phase (non-negotiable, every time)

1. Cross-reference grep: no dangling references anywhere in
   `app/src/main/java` to anything just renamed/deleted.
2. List newly-dead XML/Kotlin — do NOT delete same-session unless the
   phase's own section above explicitly said to. Default is: note it,
   delete it explicitly (git rm + commit) only once user confirms, same
   "note dead code, don't delete mid-phase" caution the old doc used
   throughout.
3. Flag "not independently verified — no local Android SDK/Gradle" in the
   summary, same as every phase in the old doc did.
4. Report scope drift if any was found (a file assumed to belong to one
   screen turned out to be owned by another) — this happened multiple
   times in the old doc (Phase 3's downloads dialogs, Phase 4's shortcuts
   living in BrowserFragment) and is worth catching early each time.
