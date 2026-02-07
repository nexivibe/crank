# Crank - SSH Terminal Manager

## Project Vision
Crank is a multi-connection SSH terminal manager built for power users ("crank engineers") who need
to manage 100+ simultaneous SSH sessions across different machines. Pure JVM (Kotlin + JavaFX),
no native dependencies, multi-platform (Windows, macOS, Linux).

## Tech Stack
- **Language**: Kotlin (JVM)
- **UI Framework**: JavaFX 17+ with FXML
- **Build**: Maven with maven-wrapper
- **SSH Client**: Apache MINA SSHD (pure Java, supports modern key formats: Ed25519, ECDSA, RSA, PEM, OpenSSH, PKCS8)
- **Terminal Emulation**: JediTerm (JetBrains' pure-Java terminal emulator, full VT100/xterm/ncurses support)
- **Persistence**: JSON files in platform-appropriate config directories
- **Java Target**: 17+ (module system via module-info.java)

## Architecture

### Package Structure
```
ape.crank
├── app/            # Application entry point, main window
├── ssh/            # SSH connection management, reconnection logic
├── terminal/       # Terminal emulation, activity tracking
├── session/        # Session lifecycle, persistence
├── connection/     # Connection configuration models
├── ui/             # UI components (tree view, dialogs, menu)
└── config/         # App configuration, platform paths
```

### Core Features

#### Two-Pane UI
- **Left Panel**: Hierarchical TreeView of terminal sessions organized by project folders
  - Each terminal node shows: UUID, display name, connection status indicator, bandwidth meter (5 blocks)
  - Bandwidth meter: 5 fill blocks relative to the max bandwidth across all sessions (0 = idle, 5 = most active)
  - "New Terminal" button that requires selecting a connection configuration
  - Drag-and-drop organization into folders (drag sessions onto folders or empty space for root)
  - Folder nodes show aggregated status: colored circle (green=all connected, orange=some, red=none, gray=empty) + count `(3/5)`
- **Right Panel**: Full terminal emulator for the currently selected session
  - Complete VT100/xterm emulation (ncurses, colors, mouse, etc.)
  - Editable mission line above terminal for per-session notes (auto-persisted)

#### Connection Management
- **Menu Bar** > **Settings** > **Connections** opens a management dialog
- Connection list with Add / Edit / Remove operations
- Removing a connection with open terminals prompts for confirmation (will close those terminals)
- Connection settings:
  - **Host** (required)
  - **Port** (default 22, overridable)
  - **Username** (required)
  - **Private Key Path** (required, with format auto-detection: OpenSSH, PEM, PKCS8)
  - **Known Hosts Policy** (strict / accept-new / trust-all)
  - **Keep-Alive Interval** (seconds, default 30)
  - **Connection Timeout** (seconds, default 30)
  - **Compression** (enabled/disabled)
  - **Initial Command** (template string executed on connect; supports `%UUID%` for session ID, `%NAME%` for connection name)
  - **Preferred Algorithms** (key exchange, cipher, MAC)
  - **Environment Variables** (key=value pairs to send)
  - **Proxy** (SOCKS5 / HTTP, optional)
  - **Label/Color** (for visual identification in the tree)

#### Session Persistence
- All connections and terminal sessions serialized to JSON
- Platform-appropriate config directory:
  - Linux: `~/.config/crank/`
  - macOS: `~/Library/Application Support/crank/`
  - Windows: `%APPDATA%/crank/`
- On startup, the app restores the full session tree and reconnects all terminals

#### Connection Management (Runtime)
- **Startup Queue**: Sessions connect with ~100ms jitter between each to avoid thundering herd
- **Auto-Reconnect**: Exponential back-off with jitter on disconnect
  - Base delay: 1s, max delay: 60s, jitter: +/- 25%
  - Reconnect attempts are unlimited
- **Connection Pool**: Manages up to 100+ concurrent SSH sessions efficiently

#### Dynamic Window Title
- Window title updates every 500ms: `"Crank [X/Y Active]"` (or just `"Crank"` when no sessions)

#### Focus Mode
- Toggle button in left panel toolbar
- Bandwidth-sorted ListView replaces the tree as the primary navigation
- Sorted ascending: idle sessions at top (sorted by name), active sessions below
- Each entry shows: status circle, session name, data rate (idle / B/s / KB/s / MB/s)
- Clicking an entry selects that session

#### Terminal Activity Tracking
- **Rate of Change Monitoring**: Tracks bytes/characters received per terminal per 5-second sliding window
- **Inactivity Detection**: Configurable threshold (e.g., 30s) to flag terminals as "inactive"
  - Filters out SSH keep-alive / heartbeat packets (trivial data)
  - Visual indicator in the tree view (dim/gray for inactive, bright for active)
- **Bandwidth Meter**: 5-block visual meter per session in tree, relative to max across all sessions
- **Last Activity Timestamp**: Displayed per terminal, shows when last meaningful data was received
- **Screen Change Detection**: Monitors terminal buffer diffs, not just raw byte count

## Build & Run
```bash
./mvnw clean compile           # Compile
./mvnw javafx:run              # Run the application
./mvnw test                    # Run tests
./mvnw package                 # Package
```

## Module System
The project uses Java module system (module-info.java at `src/main/kotlin/module-info.java`).
All new packages must be opened/exported appropriately.

## Key Design Decisions
1. **No interactive auth**: All connections use key-based auth only. No password prompts.
2. **Pure JVM**: No JNI, no native libraries. Everything runs on standard JVM.
3. **Resilient connections**: Every connection auto-reconnects. The app is designed for always-on usage.
4. **Observable state**: Every terminal's activity state is observable and trackable.
5. **Thread safety**: SSH I/O on background threads, UI updates on JavaFX Application Thread.

## Ralph Wiggum Roles
When iterating on this project, the following expert perspectives should be applied:
1. **Crank Vibe Software Engineer** - Move fast, ship features, pragmatic architecture
2. **Security Expert** - SSH key handling, credential storage, connection security, no plaintext secrets
3. **QA Expert (User Focus)** - UX flows, edge cases, error states, accessibility, 100-connection scale testing
4. **Professional Software Designer** - UI/UX design, visual hierarchy, information density, interaction patterns
