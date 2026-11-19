# DANA Notif Bridge

Pengganti MacroDroid: baca notifikasi DANA di HP Android → POST ke gateway
alldayPay `/internal/notification` dengan format payload yang sama persis.

## Format POST (harus tetap sama dengan MacroDroid)

```
POST {GATEWAY_URL}/internal/notification
Content-Type: application/json
x-notif-secret: {NOTIF_SECRET}

{"text": "<isi notifikasi DANA apa adanya>", "source": "phone"}
```

Gateway dedup via sha1(text) sendiri, jadi app mengirim text notifikasi mentah
tanpa dimodifikasi.

## Setup di HP

1. Install APK (artifact GitHub Actions).
2. Buka app → "Buka izin Notification Access" → aktifkan untuk DANA Notif Bridge.
3. Isi Gateway URL (dev: `http://192.168.1.17:3000`, produksi: `https://alldayprem.my.id`)
   dan NOTIF_SECRET, lalu Simpan.
4. Ketuk "Tes koneksi" — harus OK (endpoint `/internal/ping` pakai secret yang sama).
5. Transfer DANA → invoice di store harus ke-update → cek log di app.

## Build via GitHub Actions

Push folder ini ke repo GitHub baru → Actions jalan otomatis → download artifact
`dana-notif-bridge-debug-apk` → install APK di HP.

## Catatan

- Secret & URL tidak di-hardcode; tersimpan di SharedPreferences app.
- Retry 3x dengan backoff 2s/4s kalau POST gagal.
- Layar log menampilkan 100 event terakhir; notif status juga muncul di system tray.
- Watchdog heartbeat: foreground service GET /internal/ping tiap N menit
  (default 3, bisa diatur 1–14) — pengganti penuh macro ping MacroDroid.
  Begitu app aktif dengan izin Notification Access, MacroDroid dibersihin total.
