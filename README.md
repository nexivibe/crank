# CRANK

**The SSH Terminal Manager for Engineers Who Refuse to Close Tabs**

---

> *"How many SSH sessions do you have open?"*
> *"Yes."*

---

## What Is This

Crank is a desktop command center for engineers who have transcended the mortal plane of "one terminal per screen." While lesser tools max out at a handful of connections and call it a day, Crank was built for the person who looks at 100 simultaneous SSH sessions and thinks *"I could use a few more."*

This is not a tool for the cautious. This is not a tool for people who "close things when they're done with them." This is a tool for full-steam, pedal-to-the-floor **vibe infrastructure** — where every server in your fleet has a live heartbeat on your screen and you can feel the data rate of your empire pulsing in real time.

You are the air traffic controller. The servers are the planes. Crank is the radar.

Pop enough agents on enough machines with enough terminals and you don't have a deployment pipeline — you have an **orchestra**. And you're the conductor. With 100 batons.

## Why

Because somewhere out there, a crank engineer is SSHed into 47 machines across 3 continents, running a deployment with one hand and tailing logs with the other, and their terminal emulator just crashed because it wasn't built for this.

Crank was built for this.

- **100+ simultaneous SSH connections.** Not a suggestion. A design target.
- **Auto-reconnect with exponential backoff and jitter.** Your WiFi dropped for 2 seconds? Crank doesn't care. It's already reconnecting all 100 sessions with staggered retry timing so your bastion host doesn't think it's under attack.
- **Real-time activity monitoring.** Know which of your 100 terminals are actively streaming data, which ones went idle 30 seconds ago, and which ones are waiting for that deploy script to finish. At a glance. From a tree view. With color-coded status indicators.
- **Full VT100/xterm terminal emulation.** ncurses, 256-color, truecolor, alternate screen buffers, scroll regions — the whole circus. `htop` looks correct. `vim` works. Your custom TUI dashboard with the fancy Unicode box-drawing characters renders flawlessly.

## The Vibe

```
┌─────────────────────────────────────────────────────────┐
│ Settings                                                │
├──────────────┬──────────────────────────────────────────┤
│              │                                          │
│  prod-web-01 │  ubuntu@10.0.1.42:~$                    │
│  prod-web-02 │  CONTAINER ID  IMAGE         STATUS     │
│  prod-web-03 │  a1b2c3d4e5f6  nginx:latest  Up 4 days │
│  prod-db-01  │  f6e5d4c3b2a1  postgres:15   Up 4 days │
│  prod-db-02  │  1a2b3c4d5e6f  redis:7       Up 4 days │
│  staging-01  │                                          │
│  staging-02  │  ubuntu@10.0.1.42:~$ ▊                  │
│ ▸ monitoring │                                          │
│   grafana    │                                          │
│   prometheus │                                          │
│   loki       │                                          │
│ ▸ k8s-nodes  │                                          │
│   node-01    │                                          │
│   node-02    │                                          │
│   node-03    │                                          │
│   ...        │                                          │
│              │                                          │
├──────────────┴──────────────────────────────────────────┤
│ ● Connected | ubuntu@10.0.1.42 | ↓ 2.3 MB  ↑ 1.1 KB   │
│ Connections: 14    Active: 12 / 47                      │
└─────────────────────────────────────────────────────────┘
```

That's not a mockup. That's a Tuesday.

## The Master Plan

Here's the play that nobody else sees coming:

1. **Spin up your fleet.** Cloud, bare metal, your roommate's gaming PC — Crank doesn't discriminate. If it has an SSH port, it's part of the plan.

2. **Pop agents everywhere.** Claude Code on every machine. Aider on the dev boxes. Custom scripts on the build servers. Monitoring dashboards on the infra nodes. You're not running one agent — you're running a **distributed intelligence network** with you at the center.

3. **Coordinate in real time.** Crank shows you which sessions are active, which are idle, which are streaming data. You can feel the rhythm of your infrastructure. Agent on box 12 just finished the refactor? You see the activity spike. Box 37 went idle? Time to give it a new task.

4. **Scale without mercy.** Other people are limited by the number of terminal tabs they can keep track of. You are limited by nothing. Your left panel is a war room. Your right panel is the battlefield. The status bar is your intelligence feed.

This is vibe infrastructure. This is going full crank. This is what happens when an engineer decides that the bottleneck isn't compute — it's **visibility**.

## Features

### Connection Management
- **Settings > Connections** — Full CRUD dialog for SSH connection profiles
- Host, port, username, private key path (OpenSSH, PEM, PKCS8 — Ed25519, ECDSA, RSA)
- Known hosts policy (Trust All / Accept New / Strict)
- Keep-alive intervals, connection timeouts, compression
- Labels and color coding because when you have 100 connections, you need to vibe-code your infrastructure

### Session Organization
- Hierarchical tree view with folders
- Each session gets a UUID and a display name
- Folder nesting for organizing by project, environment, or level of chaos
- Color-coded status indicators: green (connected), yellow (connecting), orange (reconnecting), red (disconnected), gray (idle)

### Terminal Emulation
- Canvas-based rendering with proper monospace font detection
- VT100/xterm escape sequence state machine (9 parsing states, full CSI/SGR support)
- 256-color and truecolor (24-bit RGB)
- Bold, italic, underline, strikethrough, dim, inverse, hidden text attributes
- Alternate screen buffer (vim, htop, less — they all just work)
- Scroll regions, line insert/delete, character insert/delete
- Ctrl+A through Ctrl+Z, Alt+key, function keys F1-F12
- All the terminal modes: DECCKM, DECAWM, DECTCEM, bracketed paste

### Resilience
- **Startup connection queue** — All persisted sessions reconnect on launch with ~100ms jitter between each, because DDoSing your own bastion host is not the vibe
- **Exponential backoff** — Base 1s, max 60s, +/-25% jitter. Your connection will come back. Crank is more patient than you are.
- **Full error history** — Every failure recorded with timestamp, cause chain, and stack trace. Click the error in the status bar to see the full report. Copy it. Paste it. Fix it.

### Monitoring
- **Per-session data rate** — Bytes in/out, rolling 10-second rate window
- **Inactivity detection** — Configurable threshold to spot which sessions went quiet
- **Terminal status bar** — State, host, reconnect countdown, bytes, rate, active/idle
- **Global status bar** — Total connections and active session count

### Persistence
- Platform-native config storage:
  - Windows: `%APPDATA%/crank/state.json`
  - macOS: `~/Library/Application Support/crank/state.json`
  - Linux: `~/.config/crank/state.json`
- Window size, divider position, last selected session — all restored on restart
- Debounced saves so your SSD doesn't stage a revolt

## Tech Stack

| What | Why |
|------|-----|
| **Kotlin + JavaFX 17** | Cross-platform desktop UI that runs on Windows, macOS, and Linux without Electron eating 4 GB of RAM |
| **Apache MINA SSHD 2.12.1** | Pure Java SSH client. No native deps. No libssh2. No shelling out to `ssh`. |
| **BouncyCastle 1.77** | Modern key format support. Ed25519 on day one. |
| **Gson** | JSON persistence because YAML is a war crime |
| **Canvas rendering** | Direct pixel-level terminal drawing. No DOM. No WebView. No "it's basically a browser." |

## Building

### Prerequisites
- JDK 17+
- Maven (or use the included `mvnw` wrapper)

### Run from source
```bash
./mvnw clean compile javafx:run
```

### Build the fat JAR
```bash
./mvnw clean package -DskipTests
java -jar target/crank.jar
```

The fat JAR bundles JavaFX natives for Windows, macOS (Intel + Apple Silicon), and Linux. One JAR to rule them all. ~24 MB of pure, uncut terminal management.

### IntelliJ IDEA
Pre-configured run configurations included:
- **Crank - Run** — `clean compile javafx:run`
- **Crank - Build Fat JAR** — `clean package -DskipTests`

## The Philosophy

There's a moment in every engineer's career where they realize they need more terminals. Not in the "open another tab" sense. In the "I need to see everything, everywhere, all at once" sense.

Maybe you're deploying to 30 servers and need to watch the rollout in real time. Maybe you're debugging a distributed system and need logs from 12 services simultaneously. Maybe you're coordinating a fleet of AI agents across a dozen machines and you need to see which ones are cranking and which ones are stalling. Maybe you're just the kind of person who keeps a terminal open to every machine they've ever SSHed into, because you never know.

Whatever your reason, you've hit the ceiling of every other tool. Your browser-based terminal can't handle it. Your tiling window manager is running out of pixels. Your tmux session has so many panes that `Ctrl-B` followed by a direction key has become a game of chance.

Other engineers look at their infrastructure through a keyhole. You kicked the door off its hinges.

Crank doesn't judge. Crank doesn't ask why. Crank just connects.

## FAQ

**Q: Is 100 connections really necessary?**
A: The question isn't whether it's necessary. The question is whether your infrastructure deserves to be seen.

**Q: Will this work with my SSH keys?**
A: OpenSSH format, PEM, PKCS8 — Ed25519, ECDSA, RSA. If your key was generated after 1995, we support it. Passphrase-protected keys are on the roadmap.

**Q: What about password authentication?**
A: This is a tool for crank engineers. We use keys.

**Q: Does it support mouse mode?**
A: Not yet. Keyboard-first. You type faster than you click anyway.

**Q: Can I use this for production?**
A: You can use this *in* production. On all of your production servers. Simultaneously. That's literally the point.

**Q: How much RAM does it use?**
A: Less than one Chrome tab. You're welcome.

**Q: Is there a web version?**
A: No. This is a native desktop application. It starts in under a second. It doesn't need a bundler. It doesn't have `node_modules`. It doesn't require `npm install` and 47 seconds of your life you'll never get back. It's a JAR file. You double-click it. It works.

**Q: What if I only have 3 servers?**
A: Then you have room to grow. Crank believes in you.

**Q: Why "Crank"?**
A: Because you crank the handle. You turn it up. You go full send. You don't stop. You don't slow down. You crank.

## License

Do whatever you want with it. Go full crank.

---

*Built for engineers who think "too many terminals" is a challenge, not a problem.*

*Powered by the mass hallucination that one person can manage an entire data center from a single window. And being right about it.*
