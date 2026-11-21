# 🚨 CrisisConnect
### Intelligent Emergency Resource Coordination System
*UCS553 – Enterprise Web Application · Mini Project*

CrisisConnect is a platform for coordinating resources during emergencies such as floods, accidents and medical shortages.
Organizations register **food, medical supplies, shelter beds, rescue vehicles and volunteers**; affected areas raise
**SOS requests**. A **PriorityQueue-based matching engine** dispatches the right resources to the most urgent requests
first, using the nearest suitable stock — and every dispatch is saved in a **JDBC transaction**.

It ships with two front-ends that share one database:

| Front-end | Tech | What it does |
|---|---|---|
| **Desktop command centre** | JavaFX | Dashboard, request/resource/organization management, Smart Match, reports, backup & restore |
| **Web portal** | HTML + CSS + JS, served by a Java REST API | Live ops map, priority queue, SOS form, Smart Match — updates the desktop app in real time |

---

## ▶ How to run

### 1. One-time setup (macOS)
```bash
brew install openjdk@21 maven
```
(Any JDK **17 or newer** works. Windows/Linux: install a JDK 17+ and Maven.)

### 2. Start the app
```bash
cd ~/Desktop/CrisisConnect
mvn javafx:run
```
or just **double-click `run.command`** (macOS) / `run.bat` (Windows).

The first run downloads dependencies (JavaFX, SQLite driver) and creates `data/crisisconnect.db` with a
realistic demo scenario (Punjab monsoon floods + NH-44 accident).

### 3. Open the website
While the desktop app is running, open **http://localhost:8080** (or click the *Web portal* link in the app header).
Raise an SOS on the website → it pops up instantly on the desktop dashboard as a notification.

> The website also works **without** the Java server: open `website/index.html` directly and it switches to
> **DEMO MODE**, running the same matching algorithm in JavaScript on sample data.

### Other commands
| Command | Purpose |
|---|---|
| `mvn test` | Run the 26 JUnit 5 tests (engine, models, DAO + rollback, login & roles, tracking, SMS/WhatsApp alerts) |
| `mvn compile exec:java` | Run only the REST API + website (no desktop window) |
| `./run-web-only.command` | Same as above, double-clickable |

**IntelliJ IDEA:** *File → Open →* select `pom.xml` → *Open as Project*. Then in the Maven panel run
`Plugins → javafx → javafx:run`.

**Reset the demo data:** close the app and delete the `data/` folder.

---

## 🔐 Accounts, roles, SOS tracking & alerts (v2)

| Feature | What it does |
|---|---|
| **Login & roles** | Public visitors view live ops, raise and track an SOS. **Coordinators** run Smart Match and manage requests, resources and organizations. **Administrators** also approve coordinators, assign roles/organizations and read the audit log. |
| **Sign-up flow** | Request access → 6-digit e-mail code (10 min, 5 tries) → administrator approves. Without an e-mail provider the code is shown on screen (demo mode). |
| **Security** | PBKDF2-SHA256 password hashing (JDK only), random 24 h session tokens in an httpOnly SameSite cookie (only their SHA-256 is stored), 5 wrong passwords lock the account for 15 min, rate limiting, CSRF header check, audit log. Design ported from our *Partner in Project* platform. |
| **SOS tracking** | Every SOS gets a tracking ID (e.g. `CC-7KQ4MZ`). `/track.html?code=…` shows status, place in the priority queue, a timeline of dispatches and the SMS sent to the reporter (phone masked). |
| **SMS / WhatsApp simulation** | SOS received → SMS with tracking link · CRITICAL SOS → WhatsApp alert to organizations within 60 km · Smart Match → WhatsApp dispatch order to each supplying organization + "help is on the way" SMS with ETA · request closed → SMS. All messages are stored in the outbox (`notifications` table) and shown in the console. E-mails are really sent through **Brevo** when `BREVO_API_KEY` + `EMAIL_FROM` are set. |
| **Web console** | `/admin.html`: overview + Smart Match, requests (re-prioritise / close), resources (add / restock / remove), organizations, SMS & WhatsApp outbox, users, audit log, change password. |

**Accounts**
- Administrator: `bhargavchetanya47@gmail.com` (from `ADMIN_EMAILS`). Its password comes from the `ADMIN_PASSWORD` environment variable; if that is not set, a random password is printed once in the server log at start-up.
- Demo coordinator: `coordinator@demo.crisisconnect.in` / `DemoPass#2026` (turn off with `DEMO_ACCOUNTS=false`).

**Environment variables** (override `config/app.properties`)

| Variable | Purpose |
|---|---|
| `ADMIN_EMAILS` | comma-separated administrator e-mails |
| `ADMIN_PASSWORD` | administrator password (set it in Render → Environment) |
| `BREVO_API_KEY`, `EMAIL_FROM` | optional real e-mail delivery |
| `APP_PUBLIC_URL` | link used in SMS/WhatsApp messages (on Render, `RENDER_EXTERNAL_URL` is used automatically) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | "Continue with Google" (redirect URI: `<public URL>/api/auth/google/callback`) |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | real WhatsApp through Twilio (sandbox sender `+14155238886` by default; override with `TWILIO_WHATSAPP_FROM`) |
| `ADMIN_WHATSAPP` | administrator's mobile: every SOS, join request, new organization and Smart Match summary |
| `WHATSAPP_ALERT_NUMBERS` | comma-separated mobiles that get every SOS with disaster + city (sandbox: each must send the join code first) |

Only `ADMIN_WHATSAPP` and `WHATSAPP_ALERT_NUMBERS` receive real WhatsApp; all other numbers stay simulated. The console's **SMS & WhatsApp → Send test** button checks the whole setup.

Run locally with an admin password: `ADMIN_PASSWORD='YourPass123' mvn compile exec:java`

---

## 🧠 The matching algorithm
```
priority = urgencyWeight (CRITICAL 100 / HIGH 70 / MEDIUM 40 / LOW 15)
         + 10 × log10(peopleAffected + 1)
         + min(1.5 × hoursWaiting, 30)          ← ageing: no request starves
         + 15 if children/elderly/pregnant women present
```
1. All active requests go into a `PriorityQueue<EmergencyRequest>` (natural order = `compareTo`).
2. `poll()` the most urgent request.
3. Candidates = resources of the same type, in stock, within `match.maxRadiusKm` (Haversine distance).
4. Sort candidates with a `Comparator` on the polymorphic `matchCost()`:
   - **Food / Medical** – near-expiry stock is preferred (avoid waste)
   - **Shelter** – distance × 1.5 (people have to travel)
   - **Vehicle** – distance × 0.8 (vehicles move)
5. Allocate greedily, splitting across resources if needed.
6. Each request is committed in **its own transaction** – any failure rolls back only that request.

---

## 🏗 Architecture
```
 JavaFX UI  ─┐                         ┌─ website/ (HTML/CSS/JS)
             │                         │
             ▼                         ▼
        AppContext  ◄──────────  ApiServer (REST, JSON)
             │
        Service layer:  CrisisService · MatchingEngine · ReportService · BackupService · CsvImportService · EventBus
             │
        DAO layer:      GenericDao<T,ID> → ResourceDao, EmergencyRequestDao, ... → Jdbc*Dao (+ DaoFactory)
             │
        JDBC:           JdbcTemplate (PreparedStatement) · TransactionManager (commit / rollback) · DatabaseManager
             │
        SQLite file:    data/crisisconnect.db
```

### Project structure
```
CrisisConnect/
├── pom.xml                      Maven build (JavaFX, SQLite JDBC, JUnit 5)
├── config/app.properties        editable settings (port, radius, DB url, log level ...)
├── samples/field_requests.csv   sample file for "Import CSV" (contains 2 bad rows on purpose)
├── website/                     web portal (index.html, css/, js/)
├── docs/                        project report + screenshots
└── src/main/java/com/crisisconnect/
    ├── CrisisConnectApp.java    JavaFX Application (init / start / stop)
    ├── AppContext.java          wires all layers together
    ├── model/                   BaseEntity, Resource (abstract) + 5 subclasses, EmergencyRequest, enums ...
    ├── exception/               custom checked + unchecked exception hierarchy
    ├── dao/ , dao/jdbc/         DAO interfaces + JDBC implementations
    ├── db/                      DatabaseManager, JdbcTemplate, TransactionManager, DataSeeder
    ├── service/                 business logic, matching engine, reports, backup, CSV import
    ├── event/                   EventBus (observer pattern)
    ├── api/                     REST API server + JSON writer
    ├── ui/ , ui/views/          JavaFX screens, toast notifications, reusable widgets
    └── util/                    config, logging, validation, geo maths, generic collection helpers
```

Generated at runtime: `data/` (database), `logs/` (rotating log files), `exports/` (CSV + SITREP), `backups/` (`.ser` snapshots).

### REST API
| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/stats` | dashboard numbers, supply/demand per type |
| GET | `/api/queue` | active requests in priority order |
| GET | `/api/requests` · `/api/resources` · `/api/organizations` · `/api/allocations` | data lists |
| GET | `/api/events` | live activity feed |
| POST | `/api/requests` | raise an SOS (form-encoded; validated server-side) |
| POST | `/api/match` | run the Smart Match engine (coordinator login required) |
| GET | `/api/track/{code}` | public SOS tracking |
| POST | `/api/auth/register` · `verify` · `resend` · `login` · `logout` · `password` | accounts (GET `/api/auth/me`) |
| GET/POST/DELETE | `/api/admin/...` | console: requests, resources, organizations, outbox, users, audit |

---

## 📚 Syllabus coverage (UCS553)
See **`docs/PROJECT_REPORT.md`** for the full unit-by-unit mapping with class names.

| Unit | Where it is used |
|---|---|
| 1 – Java foundations | packages, Maven, abstract classes, interfaces, factory, singletons, static vs instance |
| 2 – Exceptions, collections, generics | custom exceptions, `PriorityQueue`, `HashMap`, `EnumMap`, `LinkedList`, Iterator, Comparator, `Optional`, `LocalDateTime`, generic DAO/utils |
| 3 – Files, persistence, logging | CSV export/import, serialization backup/restore, `java.util.logging`, `app.properties` |
| 4 – JDBC & DAO | SQLite, CRUD, `PreparedStatement`, `ResultSet`, JOINs, transactions, DAO pattern |
| 5 – JavaFX | lifecycle, layouts, controls, TableView/ListView, charts, events, validation, alerts |
