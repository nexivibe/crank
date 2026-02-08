# Vertical File Synchronization Strategy

## Executive Summary

Crank already holds the hardest piece of the puzzle: **persistent, authenticated, multiplexed SSH
sessions** via Apache MINA SSHD. Adding file synchronization is a natural vertical extension that
requires **one Maven dependency** (`sshd-sftp`) and zero new TCP connections. Every terminal session
in Crank already maintains a `ClientSession` that can spawn SFTP channels on demand.

This document evaluates the path from "zero file transfer" to "competitive sync solution" -- what
the JVM ecosystem offers, what must be built, and what the investment looks like.

---

## Current Position

| Asset | Status |
|-------|--------|
| Apache MINA SSHD 2.12.1 (`sshd-core`, `sshd-common`) | Already in pom.xml |
| Long-lived `ClientSession` per terminal | Already managed by `SshSessionWorker` |
| Key-based auth (Ed25519, ECDSA, RSA, PEM, OpenSSH, PKCS8) | Already working |
| BouncyCastle 1.77 crypto provider | Already integrated |
| Auto-reconnect with exponential backoff | Already implemented |
| 100+ concurrent session management | Already architected |

**What Crank does NOT have today:** No `sshd-sftp` dependency, no `sshd-scp` dependency, no file
transfer code of any kind.

---

## Adding SCP and SFTP: The Easy Win

### SFTP (Recommended Primary Transport)

One dependency unlocks the full SFTP subsystem:

```xml
<dependency>
    <groupId>org.apache.sshd</groupId>
    <artifactId>sshd-sftp</artifactId>
    <version>${sshd.version}</version>  <!-- 2.12.1, same as sshd-core -->
</dependency>
```

SFTP channels multiplex over the existing `ClientSession`. No new TCP connection, no new SSH
handshake, no new authentication. The channel rides the same encrypted tunnel as the terminal:

```kotlin
// Reuse the terminal's existing session
val session: ClientSession = sshSessionWorker.clientSession
val sftp = SftpClientFactory.instance().createSftpClient(session)

// Full filesystem operations
sftp.stat("/remote/path")
sftp.readDir("/remote/dir")
sftp.newInputStream("/remote/file.txt")
sftp.newOutputStream("/remote/file.txt")

// Or mount as a Java NIO FileSystem
val remoteFs = SftpFileSystemProvider.createFileSystem(session)
val remotePath = remoteFs.getPath("/remote/file.txt")
Files.copy(localPath, remotePath, StandardCopyOption.REPLACE_EXISTING)
```

**SFTP capabilities:**
- Full directory listing, traversal, creation, removal
- Random-access read/write (seek to offset)
- File stat (size, mtime, permissions)
- Rename, symlink, readlink
- SFTP protocol versions 3-6 with auto-negotiation
- Java NIO `FileSystem` integration (use `Files.walk()`, `Files.copy()`, etc.)

### SCP (Secondary, Simpler Use Cases)

```xml
<dependency>
    <groupId>org.apache.sshd</groupId>
    <artifactId>sshd-scp</artifactId>
    <version>${sshd.version}</version>
</dependency>
```

```kotlin
val scp = ScpClientCreator.instance().createScpClient(session)
scp.upload("/local/file.txt", "/remote/path/", ScpClient.Option.PreserveAttributes)
scp.download("/remote/file.txt", "/local/path/")
```

SCP also supports `ScpTransferEventListener` for progress tracking and
`ScpRemote2RemoteTransferHelper` for server-to-server copies without routing through the client.

### SFTP vs SCP for Crank

| Criterion | SFTP | SCP |
|-----------|------|-----|
| Directory listing | Yes | No |
| Random access (seek) | Yes | No |
| Resume interrupted transfer | Yes (via seek + write) | No |
| NIO FileSystem integration | Yes | No |
| Progress monitoring | Via streams | `ScpTransferEventListener` |
| Remote-to-remote copy | No | Yes (`ScpRemote2RemoteTransferHelper`) |
| Protocol status | Active standard | Deprecated by OpenSSH (9.0+ uses SFTP internally) |
| Delta/partial transfer | No (full file) | No (full file) |

**Verdict:** SFTP is the primary transport. SCP is deprecated upstream and offers a subset of
functionality. Use SFTP for everything; consider SCP only for the remote-to-remote transfer helper
if cross-server copy becomes a feature.

---

## Comparison with Existing Sync Tools

### rsync

rsync is the gold standard for file synchronization. Its killer feature is the **rolling checksum
delta transfer algorithm**: for a 1GB file with a 1KB change, rsync transfers ~one block (not 1GB).

**How rsync works:**
1. Receiver splits its copy into fixed blocks, computes weak (Adler32-variant) + strong (MD5) checksums per block
2. Sender scans its file with a sliding window matching against those checksums
3. Only non-matching literal data + block references are transmitted
4. Receiver reconstructs the file from its blocks + literal data

**rsync advantages over raw SFTP:**
- Block-level delta transfer (massive bandwidth savings for large, slightly-modified files)
- `--delete` for removing files no longer in source
- `--partial` for resuming interrupted transfers
- `--checksum` mode for integrity verification beyond mtime/size
- Mature, battle-tested, universally available on servers

**rsync disadvantages as a vertical integration target:**
- Requires `rsync` binary on the remote server (usually present, but not guaranteed)
- Not a library -- it is a standalone process invoked via `rsync -e ssh`
- No Java API (rsync4j exists but is a JNI wrapper around the native binary, violating Crank's pure-JVM principle)
- Protocol is complex (rsync protocol v30) and tightly coupled to its own transport

### Where Crank Can Compete

rsync is CLI-only and session-less. It establishes a new SSH connection per invocation, authenticates,
transfers, and disconnects. Crank's advantage is **persistent sessions**:

| Capability | rsync | Crank Vertical |
|------------|-------|----------------|
| Connection reuse | No (new SSH per run) | Yes (rides existing terminal session) |
| Concurrent multi-host sync | Manual scripting | Built-in (one click per session) |
| Visual progress across hosts | No | Yes (bandwidth meters, activity tracking) |
| Bidirectional sync | No (unidirectional only) | Yes (with conflict resolution UI) |
| Continuous watch mode | Requires inotifywait + scripting | Built-in (NIO WatchService) |
| GUI conflict resolution | No | Yes (JavaFX dialogs) |
| Session-aware sync | No | Yes (sync state persists with session) |
| Delta transfer | Yes (block-level) | Requires custom implementation |

**The honest gap:** Delta transfer. rsync's algorithm is the single feature that makes it
irreplaceable for large-file synchronization. Without it, Crank's sync is comparable to
`rsync --whole-file` (skip unchanged files, transfer changed files in full).

---

## Java Library Landscape

### SSH/SFTP Libraries

| Library | Artifacts | Version | Maintained | SFTP | SCP | License | Fit for Crank |
|---------|-----------|---------|------------|------|-----|---------|---------------|
| **Apache MINA SSHD** | `sshd-core`, `sshd-sftp`, `sshd-scp` | 2.12.1 (2.17.1 latest) | Yes (Apache Foundation) | v3-6 | Yes | Apache 2.0 | **Already in use** |
| JSch (mwiede fork) | `com.github.mwiede:jsch` | 2.27.7 | Yes | Yes | Yes | BSD | No -- would duplicate MINA |
| SSHJ | `com.hierynomus:sshj` | 0.38.0 | Yes | v0-3 | Yes | Apache 2.0 | No -- would duplicate MINA |
| Maverick Synergy | `com.sshtools:maverick-synergy-client` | Active | Yes | Yes | Yes | LGPL/Commercial | No -- license complexity |
| Trilead SSH-2 | `com.trilead:trilead-ssh2` | 1.0.0-build222 | No (defunct) | Yes | Yes | BSD | No -- abandoned |

**Decision:** Stay with MINA SSHD. Adding `sshd-sftp` (and optionally `sshd-scp`) is the only
dependency change needed. No reason to introduce a second SSH library.

### Rsync / Delta Transfer Libraries

| Library | What It Does | Delta Transfer | Transport | License | Pure JVM | Viable |
|---------|-------------|----------------|-----------|---------|----------|--------|
| **Jarsync** | Rolling checksum algorithm primitives | Block-level (algorithm only) | None (library) | LGPL-like | Yes | **Algorithm source -- extract and modernize** |
| **Yajsync** | Full rsync client/server (protocol v30) | Block-level | Own TCP protocol | **GPL-3.0** | Yes | **GPL license is a blocker** |
| **Fizzed Jsync** | File-level sync over SFTP | File-level only (no deltas) | SSH/SFTP (via JSch) | Apache 2.0 | Yes | **Architecture reference, but uses JSch** |
| **rsync4j** | JNI wrapper around native rsync | Block-level (native) | Native rsync | GPL-3.0 | **No (JNI)** | No -- violates pure-JVM |

### What to Leverage

1. **MINA SSHD `sshd-sftp`** -- Transport layer. One dependency, full SFTP, connection reuse. This
   is the foundation.

2. **Jarsync's algorithm** -- The rolling checksum + delta logic is ~500 lines of code. The library
   is archived but the algorithm is well-documented (Tridgell & Mackerras, 1996). Reimplement in
   Kotlin rather than taking a dependency on an unmaintained library. The core is:
   - `RollingChecksum` (Adler32 variant, ~30 lines)
   - `StrongChecksum` (MD5 or SHA-256, standard library)
   - `SignatureGenerator` (split file into blocks, compute checksums, ~50 lines)
   - `DeltaComputer` (sliding window match, emit literal data + block references, ~100 lines)
   - `DeltaApplicator` (reconstruct file from basis + delta, ~50 lines)

3. **Fizzed Jsync's design** -- Reference architecture for file-level comparison and sync
   orchestration. Its API design (fluent builder, direction control, ignore patterns) is worth
   studying even though we cannot use it directly (it depends on JSch, not MINA SSHD).

---

## The Delta Transfer Problem

The critical question: **Is block-level delta transfer achievable without requiring anything on the
remote server?**

### Approach A: SFTP-Only Delta (No Remote Tooling)

SFTP supports random-access reads. This enables a **client-side delta strategy**:

1. **Download remote file checksums:** Read the remote file in blocks via SFTP `seek + read`,
   compute rolling + strong checksums locally for each block. This requires downloading the full
   remote file once in block-sized chunks, but the checksums can be cached.

2. **Compare against local file:** Run the rolling checksum sliding window over the local file,
   matching against the remote block signatures.

3. **Upload only delta data:** Use SFTP `seek + write` to overwrite only the changed blocks on the
   remote file.

**Tradeoff:** The first sync still downloads all remote block data to compute signatures. Subsequent
syncs can use cached signatures and only download blocks that _might_ have changed (based on
mtime/size changes). This is less efficient than true rsync (where the receiver computes checksums
locally) but requires **zero remote-side tooling**.

### Approach B: SSH Exec Checksum Helper (Lightweight Remote Tooling)

Use SSH exec channels (already supported by MINA SSHD) to run checksum commands on the remote:

```kotlin
// Check if remote has standard tools
val channel = session.createExecChannel("command -v sha256sum && command -v dd")

// Fast: get file checksum without downloading
val channel = session.createExecChannel("sha256sum /remote/file.txt")

// Block-level: compute checksums for 64KB blocks
val channel = session.createExecChannel("""
    dd if=/remote/file.txt bs=65536 2>/dev/null |
    while IFS= read -r -d '' -n 65536 block; do
        echo "$block" | sha256sum
    done
""")
```

**Better approach:** Deploy a tiny shell function or script that computes rolling checksums. Most
servers have Python available:

```python
# ~20 lines of Python to compute block signatures
import hashlib, sys, struct
block_size = 65536
with open(sys.argv[1], 'rb') as f:
    while block := f.read(block_size):
        weak = sum(block) & 0xFFFFFFFF  # simplified rolling checksum
        strong = hashlib.md5(block).hexdigest()
        sys.stdout.write(f"{weak},{strong}\n")
```

**Tradeoff:** Requires standard tools on the remote (`sha256sum`, `python3`, etc.). But these are
available on >99% of Linux servers that Crank would connect to.

### Approach C: Hybrid (Recommended)

1. **Try remote checksumming first** (Approach B) -- fast, bandwidth-efficient
2. **Fall back to SFTP-only** (Approach A) if remote tools are unavailable
3. **Skip delta entirely** for small files (<1MB) -- just transfer them in full via SFTP

This gives rsync-competitive performance for the common case while degrading gracefully.

---

## Investment Analysis: Path to Competitive

### Phase 1: File Browser + Basic Transfer (MVP)

**Investment:** 1-2 weeks

**What it delivers:**
- Add `sshd-sftp` dependency
- SFTP file browser panel in Crank UI (tree view of remote filesystem)
- Upload / download individual files and directories
- Progress tracking via existing bandwidth meters
- Drag-and-drop between local file manager and Crank

**Competitive position:** Equivalent to WinSCP / FileZilla but integrated into the terminal
session -- no separate connection, no separate authentication.

**Why this matters:** Every SSH terminal user needs to transfer files sometimes. Having it built-in
with zero additional setup is a significant UX win over launching a separate SFTP client.

### Phase 2: File-Level Sync

**Investment:** 2-3 weeks (cumulative: 3-5 weeks)

**What it delivers:**
- Sync mappings: local directory <-> remote directory per session
- File-level comparison (mtime + size) to skip unchanged files
- Unidirectional sync (push local to remote, or pull remote to local)
- Ignore patterns (`.gitignore`-style)
- Sync status per session in the tree view
- Sync history / log

**Competitive position:** Equivalent to `rsync --whole-file` or Fizzed Jsync. Handles 80% of sync
use cases. Loses to rsync only on large files with small changes.

### Phase 3: Bidirectional Sync + Conflict Resolution

**Investment:** 2-3 weeks (cumulative: 5-8 weeks)

**What it delivers:**
- Three-way comparison (local vs baseline vs remote) for bidirectional sync
- Conflict detection and resolution UI in JavaFX
- Resolution strategies: newest-wins, local-wins, remote-wins, manual
- Baseline state persistence (JSON, alongside session state)
- Atomic sync operations (temp file + rename to prevent corruption)

**Competitive position:** Surpasses rsync (which is unidirectional only). Comparable to Unison
(OCaml-based bidirectional sync tool) but with a GUI and integrated into the SSH session.

### Phase 4: Delta Transfer Engine

**Investment:** 3-4 weeks (cumulative: 8-12 weeks)

**What it delivers:**
- Rolling checksum (Adler32 variant) + strong hash (SHA-256) engine in Kotlin
- Block signature generation and delta computation
- Hybrid approach: SSH exec for remote checksums, SFTP fallback
- Configurable block size (auto-tuned based on file size)
- Delta transfer for files >1MB that have changed

**Competitive position:** Matches rsync's core efficiency advantage. Combined with bidirectional
sync and GUI, this surpasses rsync for interactive use cases.

### Phase 5: Continuous Watch + Real-Time Sync

**Investment:** 2-3 weeks (cumulative: 10-15 weeks)

**What it delivers:**
- Java NIO `WatchService` for local filesystem monitoring
- Remote polling (periodic `stat` via SFTP) for remote change detection
- Automatic incremental sync on file changes
- Debounced sync (avoid thrashing during rapid edits)
- Configurable watch patterns and exclusions

**Competitive position:** Equivalent to `lsyncd` or `watchman + rsync` but fully integrated,
cross-platform, and GUI-driven. This is the feature that makes Crank a **development workflow
tool**, not just a terminal manager.

---

## Competitive Landscape Summary

| Tool | File Transfer | Delta Sync | Bidirectional | GUI | Session Reuse | Pure JVM | Watch Mode |
|------|--------------|------------|---------------|-----|---------------|----------|------------|
| **rsync** | Yes | Yes (block) | No | No | No | No | No (needs inotify) |
| **Unison** | Yes | Yes (block) | Yes | Basic | No | No (OCaml) | No |
| **lsyncd** | Yes | Via rsync | No | No | No | No | Yes |
| **WinSCP** | Yes | No | No | Yes | Own session | No | Yes (basic) |
| **FileZilla** | Yes | No | No | Yes | Own session | No | No |
| **Fizzed Jsync** | Yes | No (file-level) | No | No | No (own JSch) | Yes | No |
| **Crank Phase 1** | Yes | No | No | Yes | **Yes** | Yes | No |
| **Crank Phase 3** | Yes | No (file-level) | **Yes** | **Yes** | **Yes** | Yes | No |
| **Crank Phase 5** | Yes | **Yes (block)** | **Yes** | **Yes** | **Yes** | Yes | **Yes** |

Crank at Phase 5 would be the **only tool** that combines block-level delta transfer, bidirectional
sync, GUI-based conflict resolution, persistent SSH session reuse, pure JVM portability, and
real-time watch mode.

---

## Architecture: Vertical Sync Engine

```
+------------------------------------------------------------------+
|                        Crank UI (JavaFX)                          |
|  [Sync Panel] [File Browser] [Conflict Dialog] [Progress Meter]  |
+------------------------------------------------------------------+
|                     Sync Orchestrator                             |
|  - Bidirectional three-way merge                                  |
|  - Conflict detection and resolution                              |
|  - Sync mapping management (local dir <-> remote dir)             |
|  - Ignore patterns (.crankignore)                                 |
+------------------------------------------------------------------+
|                    Delta Transfer Engine                          |
|  - Rolling checksum (Adler32 variant)                             |
|  - Strong hash (SHA-256)                                          |
|  - Block signature generation                                     |
|  - Delta computation (sliding window)                             |
|  - Delta application (reconstruct from basis + delta)             |
+------------------------------------------------------------------+
|                   File Metadata Index                             |
|  - Per-sync-mapping baseline state                                |
|  - mtime, size, checksum cache                                    |
|  - JSON persistence alongside session state                       |
+------------------------------------------------------------------+
|                  Transport Layer (MINA SSHD)                      |
|  - SftpClient (directory listing, read, write, stat, seek)        |
|  - SSH Exec Channel (remote checksum commands)                    |
|  - Connection reuse (rides existing ClientSession)                |
+------------------------------------------------------------------+
|               Existing Crank Infrastructure                       |
|  - SshSessionWorker (managed ClientSession)                       |
|  - Auto-reconnect (sync resumes after reconnection)               |
|  - Activity tracking (sync progress in bandwidth meters)          |
+------------------------------------------------------------------+
```

### Key Design Decisions

1. **SFTP over SCP.** SCP is deprecated by OpenSSH. SFTP provides directory operations, random
   access, and NIO FileSystem integration. SCP adds nothing that SFTP cannot do, except
   remote-to-remote copy (a niche feature).

2. **Connection reuse is non-negotiable.** The entire value proposition is that sync rides the
   existing terminal session. No new connections, no new authentication, no new host key
   verification.

3. **Delta transfer is optional but strategic.** File-level sync (Phases 1-3) covers most use cases.
   Delta transfer (Phase 4) is the differentiator that makes Crank competitive with rsync for
   large-file workflows.

4. **Build the rolling checksum in Kotlin, do not take a dependency.** Jarsync is archived. Yajsync
   is GPL. The algorithm is ~500 lines and well-documented. Own it.

5. **Hybrid remote checksumming.** Try SSH exec first (fast, no download), fall back to SFTP block
   reads (universal, no remote tooling). Degrade gracefully.

6. **Bidirectional sync is the moat.** rsync is unidirectional. Unison does bidirectional but has no
   GUI and is not JVM. Crank can own the "bidirectional sync with visual conflict resolution over
   persistent SSH sessions" niche.

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| SFTP subsystem disabled on some servers | Sync unavailable for those connections | Fall back to SCP; detect and inform user |
| Delta transfer complexity (bugs, edge cases) | Data corruption | Atomic writes (temp + rename), checksums before/after, extensive testing |
| Bidirectional conflict resolution UX complexity | User confusion | Default to safe strategies (prompt on conflict), clear visual diff |
| Performance at scale (100+ sessions syncing) | Resource exhaustion | Throttle concurrent SFTP channels, queue sync operations, configurable parallelism |
| MINA SSHD 3.0 breaking changes | Migration effort | Stay on 2.x stable branch; 3.0 is milestone/pre-release, no urgency to adopt |
| Large file handling (multi-GB) | Memory pressure | Stream-based processing, never buffer entire files in memory, configurable block sizes |

---

## Conclusion

Adding file synchronization to Crank is a **high-leverage vertical integration**. The hardest part
(persistent SSH session management) is already done. The SFTP transport is one Maven dependency away.
The investment to reach rsync-competitive capability is 8-12 weeks, with useful milestones at every
phase.

The strategic positioning is clear: Crank becomes the only tool that combines multi-session SSH
terminal management with integrated, bidirectional, delta-efficient file synchronization -- all in a
single pure-JVM application with a visual interface. No other tool in the market occupies this
intersection.

**Recommended starting point:** Add `sshd-sftp` to pom.xml, create a `sync/` package under
`ape.crank`, and build Phase 1 (file browser + basic transfer). Ship it. Then iterate toward delta
sync based on user demand.
