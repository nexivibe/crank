# Terminal Emulation Tradeoff Analysis

Crank uses a custom pure-Kotlin terminal emulator built on JavaFX Canvas rendering.
This document compares it against GNOME Terminal (Ubuntu's default, built on VTE/libvte)
and identifies what's working, what's broken, and what's possible to fix.

## Current Architecture

- **Rendering**: JavaFX Canvas, per-cell drawing
- **Buffer**: Custom `TerminalBuffer` with main + alternate screen, `Array<Array<Cell>>` grid
- **Parser**: Custom `VT100Parser` state machine
- **Scrollback**: `MutableList<Array<Cell>>` (ArrayList), 10,000 line cap
- **No external terminal library** (no JediTerm, no RichTextFX)

## Scrollback / History Issues

The scrollback mechanism works at a basic level: `TerminalBuffer.scrollUp()` pushes lines
into the scrollback list, and `TerminalWidget` handles mouse wheel navigation through history.

**Known bugs and limitations:**

1. **O(n) performance on overflow** — `scrollback.removeAt(0)` is an ArrayList operation
   that shifts all 10,000 elements every time a line scrolls off the top. Gets slower over
   time. Should be an `ArrayDeque` for O(1) removal from both ends.

2. **No scrollbar-to-live snap** — When scrolled into history and new data arrives,
   `scrollOffset` is adjusted to keep the viewport stable, but there's no mechanism to snap
   back to live when the user starts typing.

3. **Resize destroys content** — `TerminalBuffer.resize()` does a naive copy-truncate with
   no text reflow. Lines that wrapped are not re-wrapped to the new width; content is
   silently lost if the terminal narrows.

## Feature Gap Analysis vs GNOME Terminal

### Critical Gaps (visible breakage in common tools)

| Feature | Current Status | Impact |
|---------|---------------|--------|
| **Mouse reporting** | Not implemented at all | vim mouse mode, tmux mouse, htop, mc, ranger — all broken. Modes 1000-1003 + SGR encoding needed. |
| **Wide character support (CJK/emoji)** | Not implemented | `Cell` stores one `Char` per one cell. CJK/emoji should occupy 2 cells but don't — everything to their right misaligns. Fundamental `Cell` model issue. |
| **Text reflow on resize** | Not implemented | `resize()` just copy-truncates. Content lost when narrowing. GNOME Terminal and JediTerm both do full reflow. |
| **Thread safety** | None | SSH reader thread calls `onData` which is marshalled to FX thread via `Platform.runLater`, but the buffer itself has no synchronization. Background parsing for non-visible sessions could race. |

### Significant Gaps (noticeable UX degradation)

| Feature | Current Status | Impact |
|---------|---------------|--------|
| **Cursor styles** | Block only | vim/neovim, fish shell, and many tools switch to underline/bar cursor via DECSCUSR (CSI q). Silently ignored. |
| **URL detection / OSC 8 hyperlinks** | None | Modern `ls`, `grep`, `gcc` emit OSC 8 hyperlinks. GNOME Terminal underlines and makes them clickable. |
| **Search in scrollback** | None | Ctrl+Shift+F in GNOME Terminal. Essential for finding output in long sessions. |
| **Synchronized output (mode 2026)** | Not handled | Causes visible tearing during rapid screen updates (progress bars, builds). |
| **Origin mode (DEC mode 6)** | Not handled | Affects cursor positioning relative to scroll region. Some TUI apps rely on this. |
| **DEC mode coverage** | 7 modes handled | GNOME Terminal handles 30+, JediTerm handles 25+. Missing: modes 3, 4, 5, 6, 12, 40, 45, 1000-1015, 2026. |

### Moderate Gaps (nice-to-have for parity)

| Feature | Current Status | Difficulty |
|---------|---------------|------------|
| **Bell/notification** | Hook exists but not connected to UI | Easy — connect callback to system notification or visual flash |
| **OSC 52 clipboard** | Not implemented | Moderate — important for tmux remote clipboard integration |
| **OSC 10/11 color query** | Not implemented | Moderate — tools use this to detect dark/light theme |
| **Blinking cursor** | Not implemented | Easy — timer-based toggle in render loop |
| **Scrollback data structure** | ArrayList with removeAt(0) | Easy — swap to ArrayDeque |

### Not a Gap (working correctly or irrelevant)

| Feature | Status |
|---------|--------|
| 24-bit true color | Fully working (256-color palette + RGB) |
| Bracketed paste mode | Correct (mode 2004) |
| Alternate screen buffer | Correct (modes 47/1047/1048/1049) |
| SGR attributes | Complete (bold, dim, italic, underline, blink, inverse, hidden, strikethrough) |
| Font ligatures | Neither GNOME Terminal nor JediTerm supports this — not a competitive gap |
| Sixel/image protocol | GNOME Terminal only recently added it (off by default) — very niche |

## Comparison Matrix

| Feature | GNOME Terminal | JediTerm/JediTermFX | Crank Custom |
|---------|---------------|---------------------|--------------|
| Scrollback (default) | 10,000 lines | 5,000 lines | 10,000 lines |
| Scrollback data structure | Ring buffer + LZ4 | CyclicBuffer (ArrayDeque) | ArrayList (O(n) remove) |
| Mouse reporting | All modes + SGR | All modes + SGR | None |
| Wide characters (CJK) | Full | Full (wcwidth) | None |
| Emoji | Full | Partial | None |
| Combining characters | Full | Partial | None |
| 24-bit true color | Yes | Yes | Yes |
| 256-color palette | Yes | Yes | Yes |
| Bracketed paste | Yes | Yes | Yes |
| Font ligatures | No | No | No |
| URL detection | Regex + OSC 8 | Regex + OSC 8 | None |
| Search in scrollback | Yes | App-level | None |
| Sixel images | Yes (opt-in) | No | No |
| Bell/notification | Yes | Callback | Callback only |
| Alternate screen | Yes | Yes | Yes |
| Text reflow on resize | Yes | Yes | No (truncates) |
| Cursor styles (6 variants) | All | All | Block only |
| OSC 0/1/2 (title) | Yes | Yes | Yes |
| OSC 7 (cwd) | Yes | Stub | No |
| OSC 8 (hyperlinks) | Yes | Yes | No |
| OSC 10/11 (color query) | Yes | Yes | No |
| OSC 52 (clipboard) | Yes | No | No |
| DEC private modes | ~30+ | ~25+ | 7 |
| vttest compliance | High | High | Low |
| Thread safety | Yes | Yes (ReentrantLock) | No |

## What IS and ISN'T Possible With the Current Architecture

### Possible (extend current code)

- **Mouse reporting** — Add mode tracking to VT100Parser, encode mouse events in TerminalWidget.
  Requires: state machine for mode tracking, SGR/X10/URXVT encoding, deciding when to handle
  locally (selection/scrollback) vs. forwarding to remote.
- **Cursor styles** — Render underline/bar in `drawCell()`, parse CSI q (DECSCUSR).
- **More DEC modes** — Extend the `setDecMode()` switch statement.
- **Bell notification** — Wire up the existing `onBell` callback to system notification or visual flash.
- **Search overlay** — Scan `scrollback` list with regex, build a UI overlay for results.
- **OSC extensions** — Extend `processOsc()` for OSC 7, 8, 10, 11, 52.
- **Scrollback perf** — Swap ArrayList to ArrayDeque.
- **Synchronized output (mode 2026)** — Buffer rendering between begin/end markers.

### Possible but architecturally hard

- **Wide characters** — Requires redesigning `Cell` to support double-width markers (a
  "continuation cell" concept). Affects every buffer operation: write, erase, cursor movement,
  selection, copy.
- **Text reflow** — Requires tracking soft-wrap vs hard-wrap per line, then re-wrapping on
  resize. Touches buffer, scrollback, cursor tracking, and selection.

### Not realistically possible without a rewrite

- **Full vttest compliance** — The custom parser handles common cases but will fail edge cases
  (complex DCS strings, proper C1 control handling, character set switching). JediTerm has been
  battle-tested against vttest for a decade in JetBrains IDEs.

## Alternative: JediTermFX

[JediTermFX](https://github.com/techsenger/jeditermfx) is a pure JavaFX port of JediTerm.

- **Maven**: `com.techsenger.jeditermfx:jeditermfx-core` + `com.techsenger.jeditermfx:jeditermfx-ui`
- **Rendering**: JavaFX Canvas (same approach as Crank's current custom widget)
- **License**: Dual LGPLv3 / Apache 2.0 (Apache 2.0 is compatible)
- **JPMS**: Yes (works with Java module system)
- **No Swing dependency**: Pure JavaFX — no SwingNode interop needed

### What JediTermFX provides over the custom implementation

- All mouse reporting modes + SGR encoding
- Wide character support (wcwidth algorithm)
- Text reflow on resize
- All 6 cursor styles
- OSC 8 hyperlinks with hover styling
- 25+ DEC private modes
- vttest-passing xterm emulation
- Thread-safe buffer management (ReentrantLock)

### What you lose with JediTermFX

- Full control over the rendering pipeline
- Direct access to the cell buffer for activity tracking / bandwidth monitoring
- Ability to customize every rendering detail (selection colors, cursor appearance, etc.)
- The current custom code is well-understood and simple to debug

### Effort comparison

- **Fixing the custom emulator to match JediTerm**: Estimated 8-12 weeks of focused terminal
  emulation work, with long-tail edge cases that JediTerm has already fixed over a decade.
- **Integrating JediTermFX**: Estimated 1-2 weeks to adapt the SSH data flow, activity tracking,
  and UI wiring to JediTermFX's API.

### Recommendation

JediTermFX is the pragmatic choice for reaching production-quality terminal emulation quickly.
The custom approach makes sense only if terminal rendering customization is a core differentiator
for the product, or if the dependency is unacceptable for other reasons.

The hybrid approach is also viable: keep the custom emulator for basic sessions and iterate on
the critical gaps (mouse reporting, cursor styles, scrollback perf) incrementally, accepting
that full vttest compliance and wide character support will remain gaps.
