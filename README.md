# مانیتور شبکه‌ی ایجنت‌ها — نسخه‌ی اندروید 🕸️🤖

اپ اندرویدی نظارت زنده بر شبکه‌ی ایجنت‌ها (BabyT + یازده ایجنت): هر ایجنت یک کارت زنده دارد —
وضعیت، کاری که الان مشغولش است و آخرین فعالیت — و با تپ روی هر کارت می‌شود کارش را
متوقف یا دوباره شروع کرد.

این ریپو، نسخه‌ی اندرویدِ برنامه‌ای است که نسخه‌ی ویندوزش (PyQt6) در ریپوی
[thaarfknight-star/TAgents](https://github.com/thaarfknight-star/TAgents) ساخته می‌شود.
هر دو نسخه از **یک معماری مشترک** استفاده می‌کنند.

## معماری مشترک

### ۱. فید وضعیت (خواندنی، بدون احرازهویت)
```
https://raw.githubusercontent.com/thaarfknight-star/TAgents/main/status.json
```
اسکیما:
```json
{
  "updated_at": "<iso>",
  "agents": [
    {
      "id": "tagent1",
      "name": "TAgent1",
      "emoji": "🎮",
      "group": "main",
      "role_fa": "…",
      "status": "working|idle|paused|error",
      "current_task": "…",
      "last_activity": "<iso>",
      "today_done": 3
    }
  ]
}
```
دوازده نود: BabyT + یازده ایجنت (TAgent 🎮، TAgent1 تا TAgent4، IASinsta 📹،
BEPagent ☀️، IASagent 🖥️، IVAagent 🤖، LICagent 🔑، LAWagent ⚖️).

### ۲. حلقه‌ی فرمان (نوشتنی، با توکن گیت‌هاب)
- اپ فایل `inbox/cmd-<epoch>.json` را با GitHub Contents API در ریپوی `TAgents` می‌سازد:
  ```json
  {"id": "cmd-1728300000", "action": "pause", "agent": "tagent1",
   "ts": "<iso>", "by": "taha"}
  ```
- بعد `outbox/rep-*.json` همان ریپو را پول می‌کند تا گزارشِ دارای `cmd_id` برابر پیدا شود:
  ```json
  {"cmd_id": "cmd-1728300000", "agent": "tagent1", "action": "pause",
   "ok": true, "message_fa": "…", "ts": "<iso>"}
  ```
- نتیجه به کاربر نمایش داده می‌شود و فید رفرش می‌شود.

## راه‌اندازی

1. APK را از بخش Artifacts ورک‌فلوی «Build Android APK» (یا از Releaseها) بگیر و نصب کن.
2. از منوی ⋮ یا دکمه‌ی ⚙️ وارد **تنظیمات** شو و یک‌بار توکن گیت‌هاب (PAT با دسترسی
   `contents:write` روی ریپوی `TAgents`) را وارد کن. توکن فقط روی همین گوشی و به‌صورت
   رمزنگاری‌شده ذخیره می‌شود.
3. داشبورد هر ۱۵ ثانیه خودکار رفرش می‌شود؛ با سوایپ هم می‌شود دستی رفرش کرد.

## بیلد

- **خودکار:** هر پوش به `main` ورک‌فلوی «Build Android APK» را اجرا می‌کند و APK دیباگ را
  در Artifacts می‌گذارد. پوش تگ `v*` علاوه بر آن یک GitHub Release با APK ضمیمه می‌سازد.
- **لوکال:** `./gradlew assembleDebug` (نیازمند JDK 17 و Android SDK).

## ساختار پروژه

```
app/src/main/
├── AndroidManifest.xml
├── assets/dashboard.html      # داشبورد فارسی/راست‌چین (تم تیره‌ی نئونی)
├── java/ir/tahaarefi/tagents/
│   ├── MainActivity.kt         # WebView تمام‌صفحه + pull-to-refresh
│   ├── SettingsActivity.kt     # ورود یک‌باره‌ی توکن
│   ├── JsBridge.kt             # پل JS ↔ Kotlin
│   ├── GitHubClient.kt         # حلقه‌ی فرمان (OkHttp + Contents API)
│   └── TokenStore.kt           # ذخیره‌ی امن توکن
└── res/                        # تم، استرینگ‌های فارسی، آیکون
```

## وضعیت نسخه‌ی v1

- ✅ داشبورد زنده، کارت ایجنت‌ها، شیت جزئیات، فرمان توقف/شروع، پول خودکار ۱۵ثانیه‌ای
- ⏳ بعداً: اعلان (notification) هنگام تغییر وضعیت، ویجت هوم‌اسکرین، حالت آفلاین/کش
