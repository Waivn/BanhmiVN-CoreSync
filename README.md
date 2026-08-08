# BanhmiVN-CoreSync

Plugin Paper/Spigot đồng bộ **BanhmiVN.fun** với máy chủ Minecraft:
giftcode một lần, rank **LuckPerms**, point **PlayerPoints**, claim blocks
**GriefPrevention**, bind item, và telemetry server realtime.

```
Minecraft Server ──► BanhmiVN.fun Backend (FastAPI)
   /nhapcode <code>      POST /api/codes/redeem   (X-API-Key)
   /bmvn code ...        POST /api/codes/sync     (X-API-Key)
   heartbeat 15s         POST /api/server/status  (X-API-Key)
```

## Tính năng

| Tính năng | Chi tiết |
|---|---|
| 🎁 Giftcode một lần | Định dạng `BMVN-XXXX-XXXX-XXXX` (bảng chữ cái bỏ I/O/L/0, giống website). Plugin tự sinh + đăng ký lên web qua `/api/codes/sync`, hoặc nhận code mua trên web. |
| 🏷️ Rank LuckPerms | **Strict mapping** — chỉ `vip`, `vip_plus`, `svip` được trao. Dùng API LuckPerms (`parent set` tương đương) hoặc lệnh console `lp user <p> parent set <rank>`. Rank lạ từ web bị từ chối + log. |
| 💎 Point PlayerPoints | API qua reflection (hỗ trợ cả bản 2.x lẫn 3.x) hoặc lệnh console `p give <p> <amount>`. |
| 🏠 Claim blocks | Lệnh console chuẩn GriefPrevention: `adjustbonusclaimblocks <p> <amount>`. |
| 📦 Bind item | `/bmvn binditem <key>` lưu **toàn bộ ItemMeta** (NBT, enchant, lore, display name) vào `items.yml`; trao khi redeem (rớt dưới chân nếu inventory đầy). |
| 📡 Heartbeat 15s | Telemetry `state, player_count, max_players, tps, memory` lên `/api/server/status` — website render trạng thái realtime. |
| 📜 Audit trail | Mọi sự kiện sinh/đổi giftcode ghi vào `audit.log` (append-only) + `redeem-history.yml` truy vấn theo player qua `/bmvn history`. `/bmvn exportaudit` nén **toàn bộ trạng thái plugin** thành snapshot `.tar.gz` và **đẩy lên website** (`/api/export`) để staff tải về; `/bmvn importaudit` khôi phục lại trên server khác. |
| 🚨 Staff alerts | Phát hiện hành vi đáng ngờ (vd brute-force nhập code: ≥5 `REDEEM_INVALID` trong 60s) → báo **Discord webhook** và/hoặc **email SMTP** cho staff. Ngưỡng/cửa sổ/cooldown cấu hình được. |
| 🔁 Pending rewards | Reward chưa trao được (offline / item chưa bind) lưu `pending-rewards.yml`, tự trao lại khi player vào server. |
| 🛡️ An toàn | Toàn bộ HTTP **async** (Java `HttpClient`) — zero lag main thread. Cache `used-codes.yml` chống dùng lại. Group/giá trị đều được validate chống command injection. |

## Cài đặt

1. **Website** (đã có sẵn endpoint): đặt biến môi trường trên backend:
   ```
   MC_API_KEY=<secret-của-bạn>
   MC_API_KEY_HEADER=X-API-Key
   ```
   (`.env.example` đã có sẵn 2 dòng này.)
2. Đặt `BanhmiVN-CoreSync.jar` vào thư mục `plugins/` của server Paper 1.21+ (Java 21).
3. Cài các plugin soft-depend: **LuckPerms**, **PlayerPoints**, **GriefPrevention**.
4. Chỉnh `plugins/BanhmiVN-CoreSync/config.yml`:
   - `api.base-url` — `https://banhmivn.fun`
   - `api.key` — giống `MC_API_KEY` trên website
   - `server.state` — `ONLINE | MAINTENANCE | CLOSED | UPCOMING_LAUNCH`
   - `server.heartbeat-interval-seconds` — mặc định `15`
5. `/bmvn reload` hoặc restart server.

## Lệnh

### Người chơi
| Lệnh | Mô tả |
|---|---|
| `/nhapcode <code>` (alias `/claim`) | Nhập giftcode nhận thưởng. |

### Admin (`banhmivn.admin`)
| Lệnh | Mô tả |
|---|---|
| `/bmvn binditem <key>` | Bind item đang cầm vào key. |
| `/bmvn unbinditem <key>` | Xoá binding. |
| `/bmvn listitems` | Danh sách key đã bind. |
| `/bmvn giveitem <key> <player> [qty]` | Trao trực tiếp item đã bind. |
| `/bmvn code <rank\|point\|land\|item\|crate> <value> [qty]` | Sinh giftcode + sync lên website. |
| `/bmvn history <player>` | Lịch sử các mã giftcode player đã redeem (20 mã gần nhất). |
| `/bmvn exportaudit` | Nén toàn bộ trạng thái (audit, history, used-codes, pending-rewards, items) thành snapshot `exports/audit-snapshot-<ts>.tar.gz` kèm `MANIFEST.txt`. |
| `/bmvn importaudit <file>` | Khôi phục trạng thái từ snapshot `.tar.gz` trong `exports/` (whitelist an toàn, atomic per-file) + nạp lại bộ nhớ. |
| `/bmvn status` | Trạng thái heartbeat / số liệu. |
| `/bmvn sync` | Đẩy heartbeat ngay. |
| `/bmvn reload` | Nạp lại config. |

Ví dụ sinh code:
```
/bmvn code rank vip+          → code tặng rank VIP+
/bmvn code point 500          → code tặng 500 point
/bmvn code land 1000          → code tặng 1000 claim blocks
/bmvn binditem crate:premium  → bind key Crate Premium (cầm key trong tay)
/bmvn code crate premium      → code tặng Crate Premium
```

## Ánh xạ reward (từ website `/api/codes/redeem`)

| `product_type` | Xử lý trong game |
|---|---|
| `rank` | Chuẩn hoá `"👑 Rank VIP+"` → enum STRICT → LuckPerms |
| `point` | PlayerPoints API hoặc `p give` |
| `land` | `adjustbonusclaimblocks <p> <qty>` |
| `crate` / `item` | Trao item đã bind (`crate:<name>` hoặc `<name>`) |

## Audit trail & lịch sử redeem

- **`audit.log`** — file append-only trong thư mục plugin, ghi MỌI sự kiện kèm timestamp:
  `REDEEM_OK` / `REDEEM_USED` / `REDEEM_INVALID` / `REDEEM_FAIL` / `GENERATE` / `SYNC_OK` / `SYNC_FAIL`
  (player, code, items, chi tiết). Dùng để điều tra lạm dụng / khiếu nại.
- **`redeem-history.yml`** — bản truy vấn nhanh theo player (tối đa 100 mã/player).
  Xem qua `/bmvn history <player>`.

### Xuất snapshot (`/bmvn exportaudit`)

Nén **toàn bộ trạng thái plugin** thành một file để bàn giao cho admin/điều tra:
`plugins/BanhmiVN-CoreSync/exports/audit-snapshot-<YYYYMMDD-HHmmss>.tar.gz`
(tar ustar qua gzip — giải nén bằng 7-Zip / WinRAR / `tar -xzf`).

- Gồm: `MANIFEST.txt` (thời điểm xuất, server, version, size từng file),
  `audit.log`, `audit-1.log` (bản đã quay vòng nếu có), `redeem-history.yml`,
  `used-codes.yml` (mã đã dùng cục bộ), `pending-rewards.yml` (thưởng còn nợ),
  `items.yml` (item đã bind). File nào chưa tồn tại sẽ được bỏ qua kèm cảnh báo.
- Mỗi lần xuất được ghi một dòng `EXPORT` vào chính `audit.log`.
- **Retention:** `exports.retention-days` (mặc định `30`) — snapshot cũ hơn N ngày
  tự động bị xoá sau mỗi lần xuất và khi plugin enable; đặt `0` để tắt.
- **Đẩy lên website:** sau khi xuất, snapshot được POST lên `POST /api/export`
  (X-API-Key) cho staff tải về từ trang admin; tắt bằng
  `exports.push-to-website: false`.
- **⏰ Auto-push định kỳ:** `exports.auto-push-interval-hours` (mặc định `6`,
  `0` = tắt) — cứ mỗi N giờ plugin tự xuất + đẩy snapshot mới lên website kể cả
  khi không ai chạy `/bmvn exportaudit`, để trang admin luôn có bản mới nhất.
  Lưu ý: bật mặc định — server đã cấu hình `api.key` + `push-to-website` sẽ tự
  xuất/đẩy mỗi 6h sau khi nâng cấp (đặt `0` để tắt). Chạy trên main thread
  (an toàn với audit.log), ghi event `AUTO_EXPORT` vào `audit.log`; tắt ngầm
  khi `push-to-website=false` hoặc `api.key` rỗng.
- **🔒 Mã hoá at-rest:** nếu đặt `exports.encryption-key` (base64 của 32 byte, sinh
  bằng `openssl rand -base64 32`), snapshot được mã hoá **AES-256-GCM** trước khi
  đẩy lên — website chỉ lưu bản mã hoá (`BMVNENC1 || IV || ciphertext || tag`),
  giải mã khi staff tải về. Key phải **khớp** với biến môi trường
  `SNAPSHOT_ENCRYPTION_KEY` trên website. GCM là authenticated encryption: key
  sai hoặc dữ liệu bị sửa → tải về trả `502`. Để trống key → vẫn đẩy nhưng
  KHÔNG mã hoá (kèm cảnh báo lúc reload). Snapshot cũ (bản rõ) tải về bình thường
  (tự nhận diện qua magic, không cần cột DB mới).
- **⚡ Kích hoạt từ website:** staff bấm nút **"⚡ Chạy exportaudit"** trên trang
  admin → website ghi lệnh chờ (`pending_command:<server>`); plugin kéo lệnh qua
  `GET /api/export/pending` ngay trong chu kỳ heartbeat có sẵn, chạy
  `/bmvn exportaudit` trên main thread rồi ack (`POST /api/export/pending/ack`)
  — không cần console. Chỉ 1 lệnh chờ/server (409 nếu còn lệnh cũ), whitelist
  chỉ cho phép `exportaudit`; nút bấm với **mọi server** đã từng đẩy snapshot.
- Chạy đồng bộ trên main thread (đọc+gzip vài MB — nhanh, tránh tranh chấp với các store).

### Khôi phục snapshot (`/bmvn importaudit <file>`)

Hoàn tất vòng bàn giao: copy snapshot `.tar.gz` sang thư mục `exports/` của server
mới, chạy `/bmvn importaudit <file>` để **xem trước**, rồi `/bmvn importaudit <file> confirm`
để thực hiện → các file trạng thái được ghi lại và store trong bộ nhớ được nạp
lại ngay (không cần restart). Bước xác nhận bắt buộc vì import sẽ **đè dữ liệu hiện tại**.

- **⬆ Khôi phục từ website:** trang admin có nút **"⬆ Khôi phục snapshot (import)"**
  — staff tải snapshot về máy rồi upload lại với server đích; website lưu file
  (base64) vào lệnh chờ `importaudit`, plugin kéo về qua heartbeat, ghi vào
  `exports/web-import-<ts>.tar.gz` và khôi phục **ngay** (bước xác nhận đã là
  hành động bấm nút của admin — không cần console). Giải mã/ghi file chạy trên
  thread async (không lag main thread), timeout request được nới rộng cho
  snapshot lớn. Khép kín vòng bàn giao: export → tải về → upload → import.

An toàn theo thiết kế:
- Chỉ nhận **tên file phẳng** (không chứa `/`, `\`, `..`) — chống path traversal.
- Chỉ khôi phục đúng **6 file trạng thái đã biết** (whitelist); entry lạ → từ chối
  **toàn bộ**, không ghi gì (không bao giờ đè `config.yml`...).
- Checksum tar được xác thực — file hỏng/không phải snapshot plugin bị từ chối.
- Chống tar-bomb: giới hạn kích thước mỗi entry (128MB), tổng dung lượng giải nén
  (256MB) và số entry (64) — kiểm tra trước khi cấp phát bộ nhớ.
- Mỗi file ghi qua temp + rename (atomic per-file).

## Cảnh báo an ninh (Staff alerts)

Plugin theo dõi stream audit và tự động báo staff khi thấy hành vi đáng ngờ
(sliding window, theo từng player). Mặc định bật quy tắc **brute-force guard**:
player thử ≥ **5** code sai (`REDEEM_INVALID`) trong **60 giây** → cảnh báo,
tối đa 1 cảnh báo / 300 giây / player (chống spam staff).

Cấu hình trong `config.yml`:

```yaml
alerts:
  enabled: true
  # Kênh Discord — tạo webhook rồi dán URL vào đây (để trống = tắt Discord)
  discord-webhook-url: "https://discord.com/api/webhooks/..."
  email:
    enabled: true          # bật khi có SMTP credentials
    smtp-host: "smtp.gmail.com"
    smtp-port: 587
    smtp-username: "alerts@banhmivn.fun"
    smtp-password: "..."   # app-password
    smtp-ssl: false         # true → cổng 465/SSL; false → STARTTLS (587)
    from: "alerts@banhmivn.fun"
    to: ["owner@banhmivn.fun"]
  rules:
    redeem-invalid:
      enabled: true
      event: "REDEEM_INVALID"   # event audit cần theo dõi
      window-seconds: 60
      threshold: 5
      cooldown-seconds: 300
```

- Có thể thêm quy tắc khác (vd `event: "REDEEM_USED"` — spam nhập mã đã dùng).
- Cảnh báo gửi qua **Discord webhook** (bất đồng bộ) và/hoặc **email SMTP**; nếu
  không cấu hình kênh nào, chỉ ghi vào console/`latest.log`.
- Jakarta Mail được **shade + relocate** (`vn.banhmivn.libs.mail.*`) — không xung
  đột classloader với plugin khác; mọi lỗi gửi chỉ ghi warning, không bao giờ
  làm gián đoạn game.

## Kiến trúc

```
vn.banhmivn.coresync
├── BanhmiVNCoreSync        # onEnable: null-check softdepend, wiring, join listener
├── config/PluginConfig     # typed config + reload
├── api/ApiClient           # async HTTP (Java HttpClient), X-API-Key, timeout
│   └── dto/                # payload khớp CHÍNH XÁC schema website (snake_case)
├── giftcode/               # Generator (BMVN-...), GiftCodeManager, UsedCodeCache
├── rank/RankType           # STRICT enum vip / vip_plus / svip
├── reward/                 # RewardApplier (LP/PP/GP/item) + PendingRewards
├── item/ItemBindingManager # items.yml (base64 ItemStack đầy đủ NBT/meta)
├── heartbeat/              # HeartbeatService 15s
├── alert/                  # SuspicionDetector (sliding window) + AlertNotifier (Discord/SMTP)
└── command/                # NhapCodeCommand + BmvnCommand (tab-complete)
```

## Build & test

```bash
mvn package          # build jar + chạy 68 unit tests (payload, codegen, rank, alerts, export/import, multipart, crypto, auto-push, pending-command)
```

- Gson + Jakarta Mail được **shade + relocate** (`vn.banhmivn.libs.*`) — plugin tự
  chứa, không đụng bản của server.
- Luồng E2E (sync → redeem → status → audit) chạy được với backend thật qua `scripts/e2e-test.py`.

## API contract (website)

| Endpoint | Body (JSON) | Ghi chú |
|---|---|---|
| `POST /api/codes/redeem` | `{code, player_name?, ign?}` | 200 → items; 404 invalid; 409 used; 410 rejected |
| `POST /api/codes/sync` | `{code, player_name?, items:[{product_type, product_name, qty}]}` | đăng ký code plugin sinh; 409 trùng |
| `POST /api/server/status` | `{status?, message?, player_count?, max_players?, tps?, ping?}` | merge — chỉ field gửi mới đè |
| `POST /api/export` | multipart: `server` + file `file` (.tar.gz) | đẩy snapshot audit — giữ bản mới nhất/server; nhận cả blob mã hoá AES-GCM |
| `GET /api/export/list` / `latest?server=` | — | admin JWT — danh sách / tải snapshot về |
| `POST /api/export/run` | `{command?="exportaudit", server?}` | admin JWT — yêu cầu server chạy exportaudit (chỉ 1 lệnh chờ/server; 409 nếu còn lệnh) |
| `GET /api/export/pending?server=` | — | X-API-Key — lệnh đang chờ của server (poll theo heartbeat, không tiêu thụ) |
| `POST /api/export/pending/ack` | `{server}` | X-API-Key — xác nhận đã xử lý xong lệnh chờ (idempotent) |
| `POST /api/export/import` | multipart: `server` + file `file` (.tar.gz) | admin JWT — upload snapshot để KHÔI PHỤC trên server (bản rõ gzip; 409 nếu còn lệnh chờ) |

Auth: header `X-API-Key` (= `MC_API_KEY` trên website) cho upload; JWT admin cho download.
Trạng thái web: `online | offline | maintenance | update`.
