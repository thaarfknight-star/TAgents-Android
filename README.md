# مانیتور شبکه‌ی ایجنت‌ها — نسخه‌ی اندروید 🕸️🤖

اپ اندرویدی نظارت زنده بر شبکه‌ی **main** ایجنت‌ها (BabyT + پنج ایجنت): هر ایجنت یک کارت زنده دارد —
وضعیت، کاری که الان مشغولش است و آخرین فعالیت — و با تپ روی هر کارت می‌شود کارش را
متوقف یا دوباره شروع کرد.

> **محدوده:** این برنامه فقط شبکه‌ی **main** را پوشش می‌دهد
> (BabyT 🧠، TAgent 🎮، TAgent1، TAgent2، TAgent3، TAgent4).
> شبکه‌ی **work** (IASinsta 📹، BEPagent ☀️، IASagent 🖥️، IVAagent 🤖، LICagent 🔑، LAWagent ⚖️)
> به دستور طه کاملاً دست‌نخورده می‌ماند و در این اپ نمایش داده نمی‌شود.

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
شش نود شبکه‌ی main: BabyT 🧠، TAgent 🎮، TAgent1، TAgent2، TAgent3، TAgent4.
(ورودی‌های غیر main اگر در فید باشند، توسط اپ فیلتر و نادیده گرفته می‌شوند.)

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

### ۳. نگهبان توکن (Token Sentinel) — نسخه‌ی ۱.۱.۰
- **صندوق چندتوکنه:** افزودن/حذف/برچسب‌گذاری چند PAT؛ ذخیره‌سازی فقط با
  `EncryptedSharedPreferences + MasterKey` (Keystore) — بدون هیچ fallback متن ساده.
  مقدار خام توکن هرگز در لاگ، فایل، ریپو، چت یا لایه‌ی JS دیده نمی‌شود.
- **بازرس توکن** (برای هر توکن، با OkHttp):
  - اعتبارسنجی با `GET /user` → نمایش مالک (login، نام، آواتار، نوع اکانت)؛
  - تشخیص نوع: پیشوند `ghp_` = کلاسیک، `github_pat_` = دقیق (fine-grained) + تأیید با
    هدرهای `X-OAuth-Scopes` / `X-Accepted-GitHub-Permissions`؛
  - سیگنال مصرف از `GET /rate_limit` (limit/remaining/reset) + «هشدار مصرف مشکوک»
    وقتی سهمیه‌ی باقی‌مانده به‌طور غیرعادی کم باشد؛
  - سرنخ فعالیت اکانت از `GET /users/{login}/events/public` (۱۰ مورد آخر، فقط سرنخ)؛
  - برچسب دستی «این توکن دست کیه؟».
- **قطع دسترسی:** `POST https://api.github.com/credentials/revoke` با بدنه‌ی
  `{"credentials": ["<token>"]}` (بدون هدر احرازهویت) + تأیید دو مرحله‌ای فارسی +
  هشدار صریح «قطع برای همه‌ی مصرف‌کننده‌هاست، تکی نمی‌شود» + لینک
  `github.com/settings/security-log` برای دیدن دستی IPها.
- **محدودیت صادقانه در UI:** تشخیص اینکه دقیقاً کدام شخص یا AI (مثل ChatGPT/Claude/Gemini)
  از یک توکن مشترک استفاده می‌کند از سمت گیت‌هاب ممکن نیست — این متن عیناً در اپ نمایش داده می‌شود.
- رفتار این فیچر در نسخه‌ی ویندوز و اندروید یکسان است (مشخصات مشترک).

## راه‌اندازی

1. APK را از بخش Artifacts ورک‌فلوی «Build Android APK» (یا از Releaseها) بگیر و نصب کن.
2. از دکمه‌ی 🛡️ وارد **نگهبان توکن** شو (یا از منوی ⋮ ← ⚙️ وارد **صندوق توکن**):
   توکن گیت‌هاب (PAT با دسترسی `contents:write` روی ریپوی `TAgents`) را اضافه کن.
   توکن فقط روی همین گوشی و به‌صورت رمزنگاری‌شده ذخیره می‌شود.
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
│   ├── SettingsActivity.kt     # مدیر صندوق چندتوکنه (افزودن/حذف/برچسب)
│   ├── JsBridge.kt             # پل JS ↔ Kotlin (+ متدهای نگهبان توکن)
│   ├── GitHubClient.kt         # حلقه‌ی فرمان (OkHttp + Contents API)
│   ├── TokenVault.kt           # صندوق چندتوکنه‌ی رمزنگاری‌شده (فقط Keystore)
│   └── TokenInspector.kt       # بازرس توکن + revoke (OkHttp)
└── res/                        # تم، استرینگ‌های فارسی، آیکون
```

## وضعیت نسخه‌ها

- ✅ v1.0.x: داشبورد زنده، کارت ایجنت‌ها، شیت جزئیات، فرمان توقف/شروع، پول خودکار ۱۵ثانیه‌ای
- ✅ v1.1.0: نگهبان توکن — صندوق چندتوکنه‌ی رمزنگاری‌شده، بازرس توکن (مالک/نوع/دسترسی/مصرف/فعالیت)، قطع دسترسی با تأیید دو مرحله‌ای
- ⏳ بعداً: اعلان (notification) هنگام تغییر وضعیت، ویجت هوم‌اسکرین، حالت آفلاین/کش
