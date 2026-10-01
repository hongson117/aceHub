# AceHub AI Support Guide & Knowledge Base

> **Target Audience:** Large Language Models (ChatGPT, Gemini, Claude) and automated support agents acting as first-line technical guides for AceHub users.  
> **Source of Truth:** Repository code at Release `v1.0.6` (`MainActivity.kt`, `G2StreamProxyServer.kt`, `G2ConfigManager.kt`, `EmbeddedAceRuntime.kt`).

---

## 1. System Purpose & Core Philosophy

AceHub (`aceHub.apk`) is an open-source, headless AceStream gateway and LAN proxy designed specifically for low-spec Android TV Boxes (such as FPT Play Box, Tanix, Mi Box, RockTek G2 with 2GB RAM).

### Key Architectural Tenets:
1. **0 Video Decoding on Box:** AceHub does NOT render or decode video. ExoPlayer / video surfaces are omitted completely. The TV Box purely acts as a network stream proxy (RAM usage < 150MB, CPU 2%–5%).
2. **0 Transcoding / Direct Pass-Through:** Video and audio streams are forwarded bit-for-bit in their native MPEG-TS container (`video/mp2t`) with TTFB < 50ms.
3. **Unified Single Endpoint:** All downstream devices in the local network (Samsung Tizen, Apple TV, PC, phone) connect to one single LAN address: `http://<BOX_IP>:8000/live`.
4. **Clean-Room & Content Neutrality:**
   - AceHub ships with **0 pre-loaded channels** and **0 content scraping mechanisms**.
   - AceHub does NOT sell, curate, or distribute any media.
   - The default test benchmark is strictly the open-source animated short *Big Buck Bunny* (Blender Foundation, CC BY 3.0, infohash: `dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c`).
   - Users are solely responsible for providing their own legal Content IDs / infohashes.

---

## 2. Normal Startup Sequence

When a user boots their TV Box or launches AceHub:
```text
[Device Power On] 
       │
       ▼
[BootReceiver / Manual App Launch]
       │
       ▼
[MainActivity / G2ForegroundService starts]
       │
       ├── Reads persistent configuration (G2ConfigManager)
       ├── Starts HTTP Stream Proxy Server on Port 8000
       └── Initializes EmbeddedAceRuntime
             │
             ├── Check 1: Is AceStream Engine already present in sandbox?
             │     ├── NO: Downloads clean headless Engine (ace-engine-armv7.zip)
             │     │       from official Release Asset -> Verifies SHA-256 -> Extracts
             │     └── YES: Verifies binaries
             │
             ├── Check 2: Starts Engine Daemon process in background (ports 6878 / 62062)
             │
             └── Check 3: Wait for Engine API handshake
                   │
                   ▼
         [Engine Status: Sẵn sàng (Port 6878/62062)]
         [Header Badge: 🟢 ĐANG HOẠT ĐỘNG]
```

---

## 3. UI Controls & Remote Interaction (Verified Source Code)

The AceHub UI is optimized for Android TV D-Pad remote control navigation.

| UI Element ID | UI Display Text / State | Type | Function & AI Instruction |
| :--- | :--- | :---: | :--- |
| `tvHubStatus` | `🟢 ĐANG HOẠT ĐỘNG` / `⏳ ĐANG TẢI...` | Badge | Primary status indicator. Tells whether the Hub server is alive. |
| `tvEngineStatus`| `Engine: Sẵn sàng (Port 6878/62062)` | TextView | Tells whether the background P2P engine is ready to receive streams. |
| `tvIpAddress` | `Địa chỉ phát: http://192.168.x.x:8000/live` | TextView | The exact URL user enters on client devices (VLC, TV, phone). |
| `btnToggleAutoBoot` | `⚡ Tự khởi động khi bật nguồn: ĐANG BẬT` / `ĐANG TẮT` | Button | Toggles `BOOT_COMPLETED` receiver. Default: `ĐANG BẬT`. |
| `btnToggle247` | `🟢 Chế độ 24/7: ĐANG BẬT (Không ngủ)` / `ĐANG TẮT` | Button | Keeps CPU awake with a `WakeLock`. Default: `ĐANG BẬT`. |
| `btnToggleWatchdog` | `🛡️ Tự Restart khi nghẽn: ĐANG BẬT` / `ĐANG TẮT` | Button | Auto-recovers Engine if stalled for > 15s. Default: `ĐANG BẬT`. |
| `btnToggleHubPower` | `🛑 TẮT TRẠM (Giải phóng FPT Box)` / `▶️ BẬT LẠI TRẠM PHÁT` | Button | Gracefully terminates all background services and frees RAM. |
| `etTestInfohash` | Hint: `Nhập Infohash (hoặc để trống để test mặc định)` | EditText | Input field for 40-character Content ID/Infohash. |
| `btnTestStream` | `⚡ Thử luồng mặc định / Infohash` | Button | Triggers 3-tier downlink probe. If field empty, tests Big Buck Bunny. |
| `btnRestartHub` | `🔄 Khởi động lại ngay (Force Restart)` | Button | Hard-restarts both the background Engine and Proxy server. |
| `btnHideBackground`| `🔽 Chạy ẩn (Về màn hình Home)` | Button | Minimizes AceHub to TV home screen while keeping proxy running. |
| `btnClearLog` | `🧹 Xóa nhật ký` | Button | Clears the real-time diagnostic log viewer. |
| `tvLog` | Console lines (Monospace) | Log View | Realtime diagnostic output for troubleshooting. |

---

## 4. UI States & Exact Wording Dictionary

When interpreting user reports or screenshot images, match the exact on-screen wording:

### Status Badges (`tvHubStatus`)
- **`⏳ ĐANG TẢI ENGINE`**: First-time run or clean install. App is downloading the headless Linux engine (~40MB).  
  *User Action:* **Do nothing, keep internet connected, wait ~15-30s.**
- **`📦 ĐANG GIẢI NÉN`**: Engine archive downloaded; extracting files into app sandbox.  
  *User Action:* **Wait 5-10s.**
- **`⚙️ ĐANG KHỞI CHẠY`**: Engine binary is starting and opening API sockets.  
  *User Action:* **Wait a few seconds.**
- **`🟢 ĐANG HOẠT ĐỘNG`**: AceHub proxy server is running on port 8000 and listening for requests.  
  *User Action:* **Proceed to testing or streaming.**
- **`🔴 LỖI ENGINE`**: Engine failed to start (port conflict, corrupted files, or OS kill).  
  *User Action:* **Click `🔄 Khởi động lại ngay (Force Restart)`. If persists, inspect log.**

### Engine Status (`tvEngineStatus`)
- **`Engine: Sẵn sàng (Port 6878/62062)`**: Normal healthy state. Engine is ready.
- **`Đang tải Engine Linux sạch: X%`**: Download progress indicator.
- **`Đang giải nén Engine Linux sạch...`**: Extraction in progress.
- **`Đang chuẩn bị môi trường Engine Linux...`**: Setting up sandbox environment shims.
- **`No compatible AceStream package found on G2`**: Device architecture unsupported or download blocked.

### Stream Statistics (`tvStreamStats`)
- **`Chưa có kênh nào được kích hoạt`**: Initial state before testing or opening any stream.
- **`Đang phát: [Tên/Mã] (Infohash: ...)`**: Active channel info.
- **`Tốc độ: 0 KB/s | Peers: 0`**: Connected to swarm, but no data incoming yet, or channel is dead.
- **`Tốc độ: > 500 KB/s | Peers: > 0`**: Healthy incoming downlink; stream data is flowing.

---

## 5. Test & Probe Flows

### A. Default Benchmark Test (Zero-Config Test)
1. User leaves `etTestInfohash` completely empty.
2. User clicks `⚡ Thử luồng mặc định / Infohash`.
3. AceHub executes a 3-tier downlink probe using infohash `dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c`:
   - **Tier 1 (Handshake):** Verifies Engine answers and generates playback URL.
   - **Tier 2 (Downlink Data):** Reads `>= 64 KiB` (65,536 bytes) of real stream payload.
   - **Tier 3 (Container Sync):** Validates MPEG-TS sync byte (`0x47`).
4. If successful, UI logs: `[Probe] Hoàn tất 3 mức! Kênh đã sẵn sàng phát tại /live`.

### B. Custom Content ID Test
1. User enters a 40-character hex infohash into `etTestInfohash`.
2. User clicks `⚡ Thử luồng mặc định / Infohash`.
3. AceHub runs the same 3-tier probe on the custom hash.
4. If successful, stream is prewarmed, saved to persistent config, and becomes available at `/live`.

---

## 6. Verified HTTP Endpoints (Port 8000)

All endpoints run on port 8000 of the Android TV Box (`http://<BOX_IP>:8000`):

| Endpoint | Method | Format | Description & Verification |
| :--- | :---: | :---: | :--- |
| `/` or `/dashboard` | `GET` | HTML | Visual web dashboard showing status, controls, and active channel. |
| `/live` | `GET` | `video/mp2t` | **Universal Direct Pass-Through stream**. Automatically delivers active/last-tested channel. |
| `/ace/getstream` | `GET` | `video/mp2t` | Parameterized stream: `?infohash=<hash>` or `?id=<hash>`. |
| `/status` or `/proxy/health` | `GET` | JSON | Machine-readable status: `status`, `channel`, `peers`, `speed_kbps`, `downloaded_bytes`, `clients`. |
| `/log` or `/log.txt` | `GET` | Text | Real-time diagnostic server log. |
| `/probe` | `GET` | JSON | Diagnostic downlink probe: `?id=<hash>&type=infohash&timeout=8000`. |
| `/prewarm` | `GET` | JSON | Prewarms swarm and saves channel as default reboot persistent channel. |
| `/config` | `GET` | JSON | Queries or updates configuration (`default_channel`, `always_hot`). |
| `/stop` | `GET` | JSON | Stops all active AceStream playback sessions. |

*Security Constraint:* Management endpoints (`/config`, `/stop`, `/prewarm`) are restricted to LAN (RFC1918) and Loopback. External WAN requests are rejected with HTTP 403 Forbidden.

---

## 7. Failure Classification Matrix

When diagnosing user problems, classify the symptom into one of these 7 categories:

| Category | Root Cause | Primary Symptom | Remediation |
| :--- | :--- | :--- | :--- |
| **1. Engine Failure** | Daemon crash, port conflict, blocked download | Badge is `🔴 LỖI ENGINE` or Engine stuck at `ĐANG KHỞI CHẠY` | Click `🔄 Khởi động lại ngay (Force Restart)`. Check storage space. |
| **2. Internet / Gateway** | Box has no internet or strict firewall | Cannot download Engine (stuck at 0%) or probe fails Tier 1 | Check Box WiFi/Ethernet connection. Test other apps on Box (e.g. YouTube). |
| **3. P2P Swarm / Source** | Dead channel, 0 seeds, bad hash | Default benchmark works, but user hash shows `Peers: 0` or 0 KB/s | Source is dead or mistyped. Advise user to test another Content ID. |
| **4. Local LAN / WiFi** | Client device on different subnet or AP isolation | VLC on laptop/phone says `Connection refused` or `Host unreachable` | Ensure client and Box are on same WiFi/router. Verify Box IP address. |
| **5. AceHub HTTP Server** | Port 8000 blocked by OS or service stopped | Badge is not `🟢 ĐANG HOẠT ĐỘNG` or `/status` does not respond | Click `▶️ BẬT LẠI TRẠM PHÁT` or reboot Box. |
| **6. Client / Target Player** | TV player cannot handle raw network stream | Stream works in VLC on PC, but fails on Samsung / Apple TV app | Problem is 100% in client player app. Configure player buffer or container settings. |
| **7. Codec / Container** | TV lacks hardware decoder for stream audio/video | Audio plays but no video, or black screen | Client device lacks AC3/E-AC3 or HEVC hardware decode. Switch client player mode. |

---

## 8. Strict Diagnostic Sequence (1 to 9)

An AI assistant MUST follow this diagnostic sequence in strict order without jumping ahead:

```mermaid
flowchart TD
    Step1["1. AceHub Process Running? (App open, Service active)"] --> Step2["2. Engine Status Sẵn sàng? (Port 6878/62062)"]
    Step2 --> Step3["3. Test Default Benchmark? (Big Buck Bunny probe succeeds)"]
    Step3 --> Step4["4. User Content ID Syntax Valid? (40 hex chars)"]
    Step4 --> Step5["5. Swarm Has Peers? (Peers > 0)"]
    Step5 --> Step6["6. Media Downlink Arriving? (Speed > 0 KB/s)"]
    Step6 --> Step7["7. /live Endpoint Reachable in LAN? (Curl or Browser)"]
    Step7 --> Step8["8. VLC Playback Succeeds? (Reference Gold Standard)"]
    Step8 --> Step9["9. Target Player (Samsung Tizen / Apple TV) Configuration"]
```

1. **Check AceHub Process:** Is AceHub open and showing `🟢 ĐANG HOẠT ĐỘNG`?
2. **Check Engine Status:** Does Engine show `Sẵn sàng`?
3. **Verify Reference Stream:** Did the default test stream (empty input + click Test) succeed?
4. **Verify User Hash:** Is the user's Content ID a valid 40-character hexadecimal string?
5. **Verify Swarm Peers:** Does the UI show `Peers > 0`?
6. **Verify Data Downlink:** Does the UI show `Tốc độ > 0 KB/s` and `Đã nhận media`?
7. **Verify Network Endpoint:** Can a browser open `http://<BOX_IP>:8000/status`?
8. **Verify via Reference Player (VLC):** Does `http://<BOX_IP>:8000/live` play in VLC on PC or mobile?
9. **Diagnose Target Player:** If VLC plays, AceHub is proven healthy. Isolate player settings on the client TV.

---

## 9. AI SUPPORT PROTOCOL (Rules for Assisting Beginners)

When interacting with a user who says *"Tôi chưa biết gì, hãy hướng dẫn tôi..."*, the AI **MUST** adhere to these 8 binding rules:

* **Rule 1 (Atomic Instructions):** Give the user at most **1 to 2 simple actions** per response. Never dump a long multi-paragraph checklist on a novice.
* **Rule 2 (No Technical Jargon):** Do NOT explain BitTorrent internals, peer-to-peer protocols, MPEG-TS packet headers, or Linux daemons unless explicitly requested.
* **Rule 3 (Confirm Before Advancing):** Always ask the user to confirm the current state on their screen before moving to the next step (e.g., *"Khi nào thấy chữ 🟢 ĐANG HOẠT ĐỘNG, hãy nhắn lại cho tôi"*).
* **Rule 4 (Screenshot-First Diagnosis):** If the user uploads a photo of their TV screen, read the exact status badge, IP address, and engine text directly from the photo.
* **Rule 5 (Default Stream Benchmark):** Never assume a custom Content ID is broken until the default test stream has been proven to work.
* **Rule 6 (VLC as Ground Truth):** If `/live` plays on VLC (PC or phone), AceHub, Engine, and the stream are officially certified as **100% working**. Any subsequent failure on a Samsung or Apple TV belongs strictly to the client app.
* **Rule 7 (No Premature Terminal/ADB):** Never instruct a beginner to use ADB, command prompt, curl, or terminal if the on-screen TV remote buttons can perform the action.
* **Rule 8 (Advanced Isolation):** Reserve ADB, Docker, API, and log inspection strictly for advanced users or after basic UI steps have failed.

---

## 10. AI Response Walkthroughs (Acceptance Scenarios)

### Scenario A: Novice Says "Tôi không biết gì, hãy hướng dẫn tôi xem TV"
> **AI Plan:**
> 1. Welcome calmly. Explain AceHub simply: it turns their Box into a TV stream station for the house.
> 2. Ask them to install `aceHub.apk` on their Box and open it.
> 3. Tell them to tell you what status they see on screen.

### Scenario B: User Uploads Image Showing `⏳ ĐANG TẢI ENGINE`
> **AI Plan:**
> 1. Acknowledge image.
> 2. State: *"AceHub đang tự động chuẩn bị thành phần Engine cho máy của bạn (lần đầu mất khoảng 15-30 giây). Bạn không cần bấm nút nào cả, chỉ cần giữ kết nối mạng và chờ đến khi màn hình hiện chữ 'Sẵn sàng' nhé!"*

### Scenario C: AceHub is Green, Default Test Works, but User Channel Has 0 Peers
> **AI Plan:**
> 1. Note that AceHub itself is working perfectly because the test sample ran.
> 2. Explain: *"Mã kênh bạn vừa nhập hiện không có ai chia sẻ (0 peers) hoặc mã đã hết hạn. Bạn hãy kiểm tra lại mã xem có gõ thiếu ký tự nào không, hoặc thử với một mã kênh khác nhé."*

### Scenario D: Stream Plays in VLC on PC, but Samsung TV Shows Black Screen
> **AI Plan:**
> 1. Note: *"Vì VLC trên máy tính đã xem được luồng rất mượt, điều này khẳng định AceHub và Box của bạn đang hoạt động 100% bình thường."*
> 2. Direct focus to TV: *"Vấn đề nằm ở ứng dụng trên Samsung TV chưa hỗ trợ tốt định dạng luồng hoặc đường truyền WiFi tới TV bị nghẽn. Hãy kiểm tra lại phần mềm phát trên TV..."*
