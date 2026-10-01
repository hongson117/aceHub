# AceHub (`aceHub.apk`)

> **High-Performance Headless AceStream Hub & Universal LAN Stream Proxy for Android TV / TV Box**

[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()
[![Release: aceHub.apk](https://img.shields.io/badge/Release-aceHub.apk-blue.svg)](https://github.com)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV%20%7C%20AOSP-green.svg)](https://developer.android.com)
[![Min SDK: 24](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0+)-informational.svg)](https://developer.android.com)
[![Stream Port: 8000](https://img.shields.io/badge/Stream%20Port-8000-orange.svg)]()
[![Pass-Through: 0 Transcode](https://img.shields.io/badge/Pass--Through-0%20Transcode-success.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

<p align="center">
  <img src="docs/images/acehub_showcase.jpg" alt="AceHub Smart Home Streaming Ecosystem" width="100%" style="border-radius: 12px;" />
</p>

---

## 1. Tổng Quan Dự Án (Overview)

**AceHub** là giải pháp trạm phát trực tiếp mã nguồn mở (Headless Stream Hub & Universal Proxy), biến các thiết bị Android TV Box phổ thông (đặc biệt là các dòng Box chỉ có **2GB RAM** như **FPT Play Box**, Mi Box, Tanix, RockTek G2...) thành một **Trạm chủ phát luồng AceStream 24/7** mạnh mẽ cho toàn bộ mạng nội bộ (LAN).

### Điểm Vượt Trội Của Kiến Trúc "Hub Only":
* **0 Video Decoding (Giải Phóng 100% GPU/RAM):** Hoàn toàn lược bỏ trình phát video (ExoPlayer/WebView) khỏi Box chạy Hub. Box đóng vai trò thuần túy là Proxy nạp và cấp luồng, giữ mức chiếm RAM dưới **150MB** và CPU chỉ **2% – 5%**, giúp thiết bị hoạt động mát mẻ, ổn định liên tục.
* **0 Transcode & 0 Redirects (Pass-Through Nguyên Bản):** Luồng video và âm thanh từ mạng P2P BitTorrent được truyền thẳng (Direct Pass-Through) tới các thiết bị đầu cuối với **độ trễ phản hồi ban đầu (TTFB) siêu tốc dưới 50ms**.
* **Đồng Bộ Chuẩn Cổng 8000 Duy Nhất:** Thống nhất cổng phát sóng `8000` trên toàn bộ hệ thống (Samsung Smart TV, Apple TV, Android TV, VLC).
* **Bảo Lưu Trạng Thái Khi Khởi Động Lại (Reboot Persistence):** Tự động ghi nhớ kênh vừa xem và luồng phát mặc định vào bộ nhớ. Mỗi khi máy Box hoặc hệ thống khởi động lại, dịch vụ nền tự động khởi động và bơm sẵn luồng cũ mà không làm thay đổi đường dẫn phát.
* **Cơ Chế Giữ Nóng Swarm 24/7 (Always-Hot Dummy Reader):** Tự động duy trì nạp dữ liệu nền tốc độ thấp để giữ kết nối với các Peer/Seed trong Swarm, giúp mọi thiết bị bật TV lên là xem ngay lập tức không cần chờ đợi nạp P2P.

---

## 2. Mô Hình Mạng & Kiến Trúc Đa Thiết Bị

```mermaid
flowchart TD
    subgraph AceHub["Trạm Chủ AceHub (Android TV Box / FPT Play Box 2GB / Mini PC)"]
        Dashboard["Giao Diện TV D-Pad Dashboard\n(Hiển thị IP, Peers, Tốc độ, Console Log)"]
        Service["Android Foreground Service\n(Tự khởi động cùng hệ thống BOOT_COMPLETED)"]
        Proxy["HTTP Stream Proxy Server\n(Lắng nghe cổng 8000 | TTFB < 50ms)"]
        Keepalive["Always-Hot Dummy Reader\n(Giữ ấm P2P Swarm 24/7)"]
        Engine["AceStream Engine\n(AIDL Service hoặc Linux Console Daemon)"]

        Dashboard --> Service
        Service --> Proxy
        Proxy --> Keepalive
        Proxy --> Engine
    end

    subgraph Clients["Thiết Bị Phát Trong Mạng LAN (Đọc Trực Tiếp Cổng 8000)"]
        Samsung["Samsung Smart TV (Tizen)\nNative AVPlay Phần Cứng"] -->|HTTP GET :8000/live| Proxy
        AppleTV["Apple TV 4K (tvOS)\nAVPlayer Native / VFilm"] -->|HTTP GET :8000/live| Proxy
        AndroidTV["TV Phòng Khách\nAceSport TV / ExoPlayer"] -->|HTTP GET :8000/live| Proxy
        VLC["Máy Tính / Điện Thoại\nVLC Media Player / Browser"] -->|HTTP GET :8000/live| Proxy
    end

    Engine <--> Swarm["Mạng P2P BitTorrent Quốc Tế\n(Peers / Seeders)"]
```

---

## 3. Hệ Thống API & Danh Mục Đường Dẫn (Port 8000)

| Đường Dẫn (Endpoint) | Giao Thức | Mô Tả Chức Năng | Định Dạng Trả Về |
| :--- | :---: | :--- | :--- |
| `/live` | `GET` | **Đường dẫn phát trực tiếp chuẩn duy nhất** (Tự động phát kênh đang được kích hoạt/bảo lưu) | `video/mp2t` (MPEG-TS nguyên bản) |
| `/ace/getstream?infohash=<hash>` | `GET` | Phát luồng theo mã băm infohash (Tương thích chuẩn VLC và Player) | `video/mp2t` (MPEG-TS nguyên bản) |
| `/status` | `GET` | Báo cáo chẩn đoán trạng thái trạm: kênh hiện tại, số peers, tốc độ KB/s, dung lượng đã tải, số client đang xem | `application/json` |
| `/` hoặc `/dashboard` | `GET` | Bảng điều khiển Web Dashboard trực quan, xem được từ bất kỳ máy tính/điện thoại nào | `text/html; charset=utf-8` |
| `/log` hoặc `/log.txt` | `GET` | Nhật ký thời gian thực (Real-time Diagnostic Log) chuẩn đoán sự cố | `text/plain; charset=utf-8` |
| `/prewarm?id=<hash>` | `GET` | Kích hoạt nạp trước luồng P2P và lưu làm kênh mặc định khi khởi động lại | `application/json` |
| `/config` | `GET` | Cập nhật tham số cấu hình trạm (`default_channel`, `always_hot`) | `application/json` |

---

## 4. Hướng Dẫn Sử Dụng Đường Dẫn Cho Các Client

Giả sử IP của Box chạy AceHub là `192.168.1.173` (hoặc laptop `192.168.1.59`):

### 1. Dành Cho Samsung Smart TV (Tizen Native AVPlay)
Cài đặt đường dẫn luồng phát:
```text
http://192.168.1.173:8000/live
```
Hoặc chỉ định infohash cụ thể:
```text
http://192.168.1.173:8000/ace/getstream?infohash=73d24aeff6515abb236ea8a3e77d89fe0b04b665
```

### 2. Dành Cho Apple TV (tvOS VFilm / AVPlayer)
```text
http://192.168.1.173:8000/live
```

### 3. Dành Cho VLC Media Player (PC / Mac / Mobile)
Mở VLC → **Media** → **Open Network Stream** (Ctrl + N):
```text
http://192.168.1.173:8000/live
```

---

## 5. Tải Về & Cài Đặt (Deployment Options)

### Phương Án A: Cài đặt trực tiếp file APK (Android TV Box / Phone)
Tải bản phát hành chính thức **`aceHub.apk`** từ mục [Releases](https://github.com/hongson117/aceHub/releases) của repository, sau đó cài đặt qua USB hoặc ADB:
```bash
adb connect <IP_ANDROID_BOX>
adb install -r aceHub.apk
```

> **Đặc quyền kiến trúc Clean Core 100% (Từ phiên bản v1.0.2):**
> * **Bản cài siêu nhẹ (~5.5 MB):** 100% mã nguồn mở (MIT), tuân thủ tuyệt đối quy định phân phối của GitHub.
> * **Tự động chuẩn bị Engine Linux Headless:** Khi khởi chạy lần đầu trên máy mới, ứng dụng tự động tải gói Engine Linux ARM nguyên bản (`ace-engine-armv7.zip`) từ Release Asset chính thức (có máy chủ dự phòng CDN châu Á). Tiến trình tải và giải nén được hiển thị trực quan theo thời gian thực trên TV.
> * **100% Không Quảng Cáo (Zero Ads):** Bản Engine Linux là daemon điều phối ngầm (Console Daemon), hoàn toàn không chứa Android GUI hay AdMob SDK như bản app thông thường.
> * **Bảo lưu và khởi động tức thì:** Nếu thiết bị đã có sẵn Engine (hoặc nâng cấp từ bản trước), AceHub tái sử dụng ngay lập tức mà không cần tải lại, mở cổng phát sóng chỉ sau 0.5 giây.

Sau khi cài đặt:
1. Mở ứng dụng **AceHub** trên màn hình Android TV.
2. Thiết bị sẽ tự động chuẩn bị Engine (lần đầu mất ~10 giây tải qua mạng; các lần sau bật lên là chạy ngay).
3. Khi màn hình báo `🟢 ĐANG HOẠT ĐỘNG`, bấm nút **Ẩn chạy ngầm (Home)** trên remote để trạm tiếp tục phát sóng 24/7 mà không làm phiền màn hình tivi.

---

### Phương Án B: Triển Khai Docker Trên Home Server & NAS (Ubuntu / Synology / Proxmox / Unraid)

Dành cho người dùng có sẵn **Home Server, Mini PC (Intel N100/i3/i5), NAS hoặc VPS**:
* **Tích Hợp API Chuẩn (Standard API Engine):** Giao tiếp trực tiếp với AceStream Engine qua cổng API nội bộ để khởi tạo và truyền dẫn luồng HTTP nguyên bản (0-transcode).
* **Đổi kênh siêu mượt (Instant Channel Switch):** Tự động thu hồi session cũ sạch sẽ khi chuyển kênh, kết hợp bộ đếm đệm 5 giây chống rớt luồng khi TV đổi audio track hoặc seek.
* **Tải kép thông minh (Multiplexing):** Nhiều TV cùng xem một trận đấu chỉ tốn đúng 1 luồng P2P duy nhất.

#### 1. Chạy nhanh bằng Docker Compose:
Tạo file `docker-compose.yml`:
```yaml
version: '3.8'

services:
  acehub:
    image: ghcr.io/hongson117/acehub:latest
    container_name: acehub
    restart: unless-stopped
    network_mode: host
    environment:
      - ACE_PROXY_PORT=8000
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```
Khởi chạy dịch vụ:
```bash
docker compose up -d
```

#### 2. Hoặc chạy nhanh bằng lệnh Docker Run:
```bash
docker run -d \
  --name acehub \
  --restart unless-stopped \
  --net=host \
  ghcr.io/hongson117/acehub:latest
```

Sau khi chạy xong, máy chủ của bạn ngay lập tức mở cổng `8000`:
* **Bảng điều khiển (Web Dashboard):** `http://<IP_HOME_SERVER>:8000/`
* **Xem luồng trực tiếp:** `http://<IP_HOME_SERVER>:8000/live?id=<CONTENT_ID>`
* **Kiểm tra trạng thái JSON:** `http://<IP_HOME_SERVER>:8000/stat`

---


## 6. Biên Dịch Từ Mã Nguồn (Build from Source)

### Yêu Cầu Môi Trường
* **JDK:** OpenJDK 17 trở lên
* **Android SDK:** Compile SDK 35, Build-Tools 34.0.0+
* **Gradle:** 8.9+ (Đã tích hợp sẵn `gradlew`)

### Lệnh Biên Dịch
```bash
# Clone repository
git clone https://github.com/<your-username>/aceHub.git
cd aceHub

# Cấp quyền chạy cho gradlew (Linux / macOS)
chmod +x gradlew

# Biên dịch bản Release APK chính thức
./gradlew assembleRelease
# Hoặc trên Windows:
.\gradlew.bat assembleRelease
```
Tệp APK kết quả sẽ được tạo tại:
`app/build/outputs/apk/release/aceHub.apk`

---

## 7. Tuân Thủ Quy Chuẩn Mã Nguồn Mở (Clean-Room Standard)

Dự án này được xây dựng với mục tiêu tuân thủ 100% tiêu chuẩn phân phối mã nguồn mở trên GitHub:
1. **0 Proprietary Blob:** Không chứa mã nguồn vi phạm bản quyền hay các tệp nhị phân đóng kín.
2. **Standard Android CI/CD:** Tích hợp sẵn kịch bản GitHub Actions (`.github/workflows/build-apk.yml`) tự động kiểm tra cú pháp và build `aceHub.apk` trực tiếp trên đám mây khi gắn thẻ phiên bản (`git tag v1.0.0`).
3. **Giấy phép MIT:** Tự do sử dụng, chỉnh sửa và triển khai cho các dự án cá nhân hoặc cộng đồng.

---

## 8. Giấy Phép (License)

Dự án được phân phối dưới giấy phép **[MIT License](LICENSE)**. Xem tệp `LICENSE` để biết thêm chi tiết.

---

> **Important Legal Disclaimer / Tuyên Bố Pháp Lý:**  
> 
> **English:**  
> AceHub is an open-source, neutral network stream proxy and gateway utility designed for local area networks (LAN). **AceHub does not provide, host, store, scrape, or distribute any video streams, media files, or copyrighted television channels.** The application acts solely as a standard HTTP socket pipe between local clients and protocol engines via official/standard APIs. Users are solely responsible for obtaining and verifying the legality, distribution rights, and origin of any stream identifiers (infohashes / content IDs) they choose to process with this software. Third-party components and engines are used subject to their respective licenses and terms of service; AceHub does not emulate unauthorized access levels or circumvent any technological protection measures (TPM).
> 
> **Tiếng Việt:**  
> AceHub là công cụ quản lý và chuyển tiếp luồng mạng nội bộ (LAN Stream Proxy & Gateway) mã nguồn mở. Phần mềm hoạt động như một cầu nối giao thức mạng thuần túy, **không lưu trữ, không thu thập (scrape) và không cung cấp sẵn bất kỳ danh sách kênh hay quyền truy cập nội dung có bản quyền nào**. Việc phát và sử dụng luồng phát phụ thuộc hoàn toàn vào nguồn do người dùng tự cung cấp và tuân thủ các giấy phép dịch vụ của bên thứ ba. AceHub không can thiệp, không giả lập quyền truy cập trái phép và không vô hiệu hóa bất kỳ biện pháp công nghệ bảo vệ quyền nào.
