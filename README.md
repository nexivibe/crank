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

Here's the thing nobody tells you about managing infrastructure: **you should be able to walk away.** Close your laptop. Go to lunch. Let your cat walk across the keyboard. Come back and everything is exactly where you left it. Every session reconnected. Every `screen` session resumed. Every terminal buffer intact. Crank + `screen` means your work survives you. Your laptop crashing is an inconvenience, not a catastrophe. Set `exec screen -xRR %UUID%` as your initial command and every session gets its own named `screen` that auto-resumes on reconnect. You don't lose work. You don't lose context. You don't lose your mind.

## Why

Because somewhere out there, a crank engineer is SSHed into 47 machines across 3 continents, running a deployment with one hand and tailing logs with the other, and their terminal emulator just crashed because it wasn't built for this.

Crank was built for this.

- **100+ simultaneous SSH connections.** Not a suggestion. A design target.
- **Auto-reconnect with exponential backoff and jitter.** Your WiFi dropped for 2 seconds? Crank doesn't care. It's already reconnecting all 100 sessions with staggered retry timing so your bastion host doesn't think it's under attack.
- **Real-time activity monitoring.** Know which of your 100 terminals are actively streaming data, which ones went idle 30 seconds ago, and which ones are waiting for that deploy script to finish. At a glance. From a tree view. With color-coded status indicators.
- **Full VT100/xterm terminal emulation.** ncurses, 256-color, truecolor, alternate screen buffers, scroll regions — the whole circus. `htop` looks correct. `vim` works. Your custom TUI dashboard with the fancy Unicode box-drawing characters renders flawlessly.

## The Vibe

```
┌──────────────────────────────────────────────────────────────┐
│ Crank [42/47 Active]                                         │
│ Settings                                                     │
├───────────────────┬──────────────────────────────────────────┤
│                   │ Mission: Watching canary deploy rollout   │
│ ▸ prod ●(5/5)    ├──────────────────────────────────────────┤
│   ● web-01 █████ │  ubuntu@10.0.1.42:~$                     │
│   ● web-02 ███░░ │  CONTAINER ID  IMAGE         STATUS      │
│   ● web-03 █░░░░ │  a1b2c3d4e5f6  nginx:latest  Up 4 days  │
│   ● db-01  ░░░░░ │  f6e5d4c3b2a1  postgres:15   Up 4 days  │
│   ● db-02  ░░░░░ │  1a2b3c4d5e6f  redis:7       Up 4 days  │
│ ▸ staging ●(2/2) │                                           │
│   ● stg-01 ██░░░ │  ubuntu@10.0.1.42:~$ ▊                   │
│   ● stg-02 █░░░░ │                                           │
│ ▸ monitoring     │                                           │
│        ◉(3/3)   │                                           │
│   ● grafana ░░░░░│                                           │
│   ● prom   █████ │                                           │
│   ● loki   ███░░ │                                           │
│ ▸ k8s-nodes      │                                           │
│        ●(8/8)   │                                           │
│   ...            │                                           │
│                   │                                           │
├───────────────────┴──────────────────────────────────────────┤
│ ● Connected | ubuntu@10.0.1.42 | ↓ 2.3 MB  ↑ 1.1 KB        │
│ Connections: 14    Active: 42 / 47                           │
└──────────────────────────────────────────────────────────────┘
```

That's not a mockup. That's a Tuesday. And those bandwidth meters? They're pulsing. You can *see* which box is doing the heavy lifting without reading a single label.

## The Master Plan

Here's the play that nobody else sees coming:

1. **Spin up your fleet.** Cloud, bare metal, your roommate's gaming PC — Crank doesn't discriminate. If it has an SSH port, it's part of the plan.

2. **Set `exec screen -xRR %UUID%` as your initial command.** Now every Crank session is backed by a named `screen` on the remote box. Your processes don't die when you disconnect. Your logs don't stop. Your deploys don't abort. The remote keeps cranking whether you're watching or not.

3. **Pop agents everywhere.** Claude Code on every machine. Aider on the dev boxes. Custom scripts on the build servers. Monitoring dashboards on the infra nodes. You're not running one agent — you're running a **distributed intelligence network** with you at the center.

4. **Walk away.** Close your laptop. Go home. Sleep. Crank auto-reconnects with exponential backoff. `screen -xRR %UUID%` reattaches to the exact session. Your terminal buffer is right where you left it. That deploy you started at 3pm? It finished at 3:07pm. You're seeing the output at 9am the next morning. Nothing was lost. You just blinked.

5. **Coordinate in real time.** Crank shows you which sessions are active, which are idle, which are streaming data. The bandwidth meters pulse in real time. Agent on box 12 just finished the refactor? The meter drops to `░░░░░`. Box 37 is burning CPU? `█████`. You can feel the rhythm of your infrastructure without reading a single line of output.

6. **Scale without mercy.** Other people are limited by the number of terminal tabs they can keep track of. You are limited by nothing. Your left panel is a war room. Your right panel is the battlefield. The status bar is your intelligence feed. Focus Mode puts the idle sessions at the top so the ones that need attention find *you*.

This is vibe infrastructure. This is going full crank. This is what happens when an engineer decides that the bottleneck isn't compute — it's **visibility**.

## Features

### Connection Management
- **Settings > Connections** — Full CRUD dialog for SSH connection profiles
- Host, port, username, private key path (OpenSSH, PEM, PKCS8 — Ed25519, ECDSA, RSA)
- Known hosts policy (Trust All / Accept New / Strict)
- Keep-alive intervals, connection timeouts, compression
- **Initial Command** — Run a command automatically when the session connects (and on every reconnect). Supports template variables:
  - `%UUID%` — The session's unique ID (perfect for naming `screen`/`tmux` sessions)
  - `%NAME%` — The connection's display name
  - Example: `exec screen -xRR %UUID%` — Creates or reattaches a `screen` session named after the Crank session ID. Walk away, come back, pick up exactly where you left off. Your processes keep running. Your logs keep tailing. Your deploys keep deploying. You just weren't watching for a bit.
- Labels and color coding because when you have 100 connections, you need to vibe-code your infrastructure

### Session Organization
- Hierarchical tree view with folders and drag-and-drop
- Each session gets a UUID, display name, and a **5-block bandwidth meter** showing relative activity at a glance
- Drag sessions between folders or back to root — reorganize your fleet on the fly
- Folder nesting for organizing by project, environment, or level of chaos
- **Folder status aggregation** — Each folder shows a colored dot and count: green `(5/5)` = all connected, orange `(3/5)` = some, red `(0/5)` = none
- Color-coded status indicators: green (connected), yellow (connecting), orange (reconnecting), red (disconnected), gray (idle)
- **Mission notes** — Editable text field above the terminal for each session. Remind yourself why this terminal exists. "Tailing prod API logs." "Watching the canary deploy." "Don't touch this one." Persisted across restarts.

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
- **Per-session data rate** — Bytes in/out, rolling 5-second rate window that decays fast so you see what's happening *now*
- **Bandwidth meter** — Every session in the tree shows 5 fill-blocks (`█████` / `█░░░░` / `░░░░░`) relative to the hottest session. Spot the busiest terminal in a wall of 100 without reading a single number.
- **Inactivity detection** — Configurable threshold to spot which sessions went quiet
- **Terminal status bar** — State, host, reconnect countdown, bytes, rate, active/idle
- **Dynamic window title** — `Crank [47/100 Active]` in your taskbar. Know the state of your fleet without even switching to the window.
- **Global status bar** — Total connections and active session count

### Focus Mode
Hit the **Focus Mode** toggle and the left panel flips from tree navigation to a **bandwidth-sorted session list**. Idle sessions float to the top, sorted by name. Active sessions sink to the bottom with their data rates displayed. This is how you find the needle in a 100-session haystack — the one terminal that went quiet when it shouldn't have, or the one that's screaming data when it should be idle. Click any entry to jump straight to that session.

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

**Q: What happens when my laptop crashes?**
A: Nothing. That's the point. If you set your initial command to `exec screen -xRR %UUID%`, every session runs inside a named `screen` on the remote machine. Your laptop dies, you restart Crank, it reconnects all 100 sessions, each one reattaches to its `screen`, and you're staring at the exact same terminal output as before. The remote never stopped. The logs never stopped tailing. The deploy never stopped deploying. You just stopped watching for a minute.

**Q: What's the initial command for?**
A: It runs automatically every time a session connects — including reconnects. The killer combo is `exec screen -xRR %UUID%`. That tells the remote shell to replace itself with a `screen` session named after Crank's session UUID. If the `screen` exists, it reattaches. If it doesn't, it creates one. You get persistent, named, resumable sessions on every box in your fleet for free. You can also use `%NAME%` if you want human-readable screen names. Or just run `cd /var/log && tail -f syslog` if you don't need persistence and just want every connection to start tailing logs immediately.

**Q: What are the bandwidth meters?**
A: Every session in the tree shows 5 blocks: `█████` means it's the hottest session you have. `░░░░░` means it's idle. They're relative — the busiest session gets 5 bars, everyone else is scaled against it. When you have 47 sessions open, you don't read labels. You read shapes. The one with full blocks? That's where the action is. The one that just dropped to empty? Something finished. Or something broke.

**Q: What is Focus Mode?**
A: Focus Mode flips the left panel from a tree into a bandwidth-sorted list. Idle sessions at the top, sorted by name. Active sessions at the bottom with live data rates. It's triage mode. When something in your fleet goes wrong, you don't scroll through a tree looking for the silent terminal. You hit Focus Mode and the problem is right there at the top — the session that should be streaming data but isn't. Click it, investigate, fix it, move on.

**Q: Can I organize sessions while I'm working?**
A: Drag and drop. Grab a session, drop it in a folder. Drop it on empty space to move it to root. Reorganize your fleet like you're rearranging furniture. The folders show aggregated status too — one glance at a folder tells you if all its sessions are connected, some, or none.

**Q: What are mission notes?**
A: A text field above the terminal where you write what this session is *for*. "Watching canary deploy." "Running load test — DO NOT CLOSE." "Jeff's weird test box, ask before touching." It's saved to disk. It survives restarts. Future-you will thank present-you when you come back to 47 open sessions on Monday morning and don't remember what half of them were doing.

**Q: Why "Crank"?**
A: Because you crank the handle. You turn it up. You go full send. You don't stop. You don't slow down. You crank.

**Q: I read this whole README and I still don't understand why someone would need 100 SSH sessions.**
A: You'll understand when you get there. And when you do, Crank will be waiting.

**Q: Is this just a terminal multiplexer?**
A: A terminal multiplexer shows you one machine at a time. Crank shows you your entire fleet at a glance — connection state, bandwidth, activity, organization — and lets you jump between any of them instantly. It's the difference between driving one car and running an air traffic control tower. Both involve steering. Only one involves *scale*.

## License

MIT License. Do whatever you want with it. Go full crank. See [LICENSE](LICENSE) for the fine print.

---

*Built for engineers who think "too many terminals" is a challenge, not a problem.*

*Powered by the mass hallucination that one person can manage an entire data center from a single window. And being right about it.*

*Set up your fleet. Set your initial commands. Walk away. Come back. Everything is still running. That's not a feature — that's a lifestyle.*
