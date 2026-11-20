# CrisisConnect – Intelligent Emergency Resource Coordination System
**Course:** UCS553 Enterprise Web Application (L-T-P 3-0-2) · **Type:** Mini project with database integration

---

## 1. Abstract
During floods, road accidents or medicine shortages, help usually exists — it is just in the wrong place.
NGOs, hospitals, government bodies and volunteer groups each keep their own lists, and requests for help arrive
over phone calls and WhatsApp with no ordering. **CrisisConnect** puts every resource and every request into one
database and uses a **priority-queue matching engine** to dispatch the right resource to the most urgent request,
from the nearest suitable location. The system is built in **Core Java 17** with **JavaFX** for the desktop
command centre, **JDBC + SQLite** with the **DAO pattern** for persistence, and an embedded **REST API** that powers a
public **web portal** where affected areas can raise SOS requests.

## 2. Problem statement & objectives
| Problem | Objective / feature |
|---|---|
| No single view of available relief resources | Central inventory of 5 resource types across organizations |
| Requests handled first-come-first-served | Priority score + `PriorityQueue` so critical cases go first |
| Resources sent from far away while closer stock exists | Haversine distance + polymorphic cost ranking |
| Partial updates corrupt data when something fails | Every dispatch is a JDBC transaction with rollback |
| No records for audits / handover | CSV exports, situation reports, serialized backups, rotating logs |
| Field teams cannot access the desktop app | Web portal + REST API, changes appear live on the desktop |

## 3. Modules
1. **Organizations** – register NGOs, hospitals, govt. bodies (name uniqueness, phone & e-mail validation).
2. **Resources** – register stock with quantity, city and optional expiry date; restock; delete.
3. **Emergency requests** – raise SOS with urgency, people affected, vulnerable-groups flag; live priority preview; cancel.
4. **Smart Match Center** – dry-run preview and real matching (background thread), console log, dispatch history, auto-match timer.
5. **Dashboard** – KPI tiles, supply-vs-demand bar chart (gap analysis), urgency pie chart, live priority queue, activity feed.
6. **Reports & data** – situation report (SITREP), CSV export, CSV bulk import with per-line error report, backup/restore.
7. **Web portal** – hero with live counters, real situation map (Leaflet + OpenStreetMap, bundled locally), priority queue, gap analysis, Smart Match, SOS form.

## 4. Architecture
```
Presentation   JavaFX (ui/, ui/views/)              Website (website/)
                     │                                   │  HTTP + JSON
Integration          │                              api/ApiServer, api/Json
                     ▼                                   ▼
Service        service/CrisisService (facade) · MatchingEngine · ReportService · BackupService · CsvImportService
                     │                event/EventBus (observer) ──► JavaFX toasts + refresh
Data access    dao/GenericDao<T,ID> → OrganizationDao, ResourceDao, EmergencyRequestDao, AllocationDao
                     │                   dao/jdbc/Jdbc*Dao  (created by DaoFactory)
JDBC           db/JdbcTemplate · db/TransactionManager (ThreadLocal connection) · db/DatabaseManager (singleton)
                     │
Storage        SQLite data/crisisconnect.db · exports/*.csv · backups/*.ser · logs/*.log · config/app.properties
```
The UI and the API **never touch SQL**; they call `CrisisService`. The service never touches JDBC; it calls DAO
interfaces. Replacing SQLite by MySQL only needs a new `db.url` and driver dependency.

## 5. Class design (key classes)
```
           «interface» Identifiable<ID>
                      ▲
            «abstract» BaseEntity  (id, createdAt, displayName(), Serializable)
        ┌─────────────┼──────────────────────┬──────────────┐
  Organization   «abstract» Resource      EmergencyRequest    Allocation
                 implements Allocatable   implements Comparable<EmergencyRequest>
                 matchCost() = typeAdjustedCost(distance)   priorityScore()
       ┌──────────┬─────────┼─────────┬───────────┐
 FoodResource MedicalResource ShelterResource VehicleResource VolunteerResource
        (each overrides getType() and typeAdjustedCost() → runtime polymorphism)

 ResourceFactory.create(ResourceType)  – factory pattern (switch expression)
 Enums: ResourceType, UrgencyLevel(weight, colour), RequestStatus, DisasterType, OrganizationType
 Records: AllocationPlan, MatchResult, MatchReport, DashboardStats, CrisisEvent
```

## 6. Database design (SQLite)
```
organizations (id PK, name UNIQUE, type, contact_phone, email, city, latitude, longitude, created_at)
      1 ──── *
resources     (id PK, organization_id FK→organizations ON DELETE CASCADE, type, name,
               available_quantity CHECK ≥ 0, total_capacity, city, latitude, longitude, expiry_date, created_at)
      1 ──── *
allocations   (id PK, request_id FK→emergency_requests, resource_id FK→resources, quantity CHECK > 0,
               distance_km, created_at)
      * ──── 1
emergency_requests (id PK, title, disaster_type, resource_type, quantity_needed CHECK > 0, quantity_fulfilled,
               people_affected, urgency, city, latitude, longitude, status, contact_phone,
               vulnerable_groups, source[DESKTOP|WEB|CSV], created_at)
```
Indexes on `resources(type)`, `emergency_requests(status)`, `allocations(request_id)`. Schema is in
`src/main/resources/schema.sql` and is created automatically on first run.

## 7. Matching algorithm
```
score(r) = weight(urgency) + 10·log10(people+1) + min(1.5·hoursWaiting, 30) + (vulnerable ? 15 : 0)

queue ← PriorityQueue(active requests)          // O(n log n)
ledger ← HashMap<resourceId, unitsLeft>
byType ← EnumMap<ResourceType, List<Resource>>
while queue not empty:
    req ← queue.poll()
    cands ← byType[req.type] filtered by ledger > 0 and distance ≤ maxRadius
    sort cands by matchCost(req.location), then by stock DESC        // Comparator chain
    for res in cands while req not satisfied:
        take ← min(remaining, ledger[res]);  ledger[res] -= take;  plan.add(req, res, take)
for each request's plans:  BEGIN; re-read stock; allocate(); UPDATE resources; INSERT allocation;
                          UPDATE request progress; COMMIT   (ROLLBACK on any exception)
```
*Why greedy?* It is explainable to field officers, runs in milliseconds, and the ageing term guarantees that
low-priority requests are eventually served.

## 8. Syllabus mapping (where to look in the code)
| Syllabus topic | Implementation |
|---|---|
| **Unit 1** JVM/JDK, project structure, packages | Maven layout, 11 packages under `com.crisisconnect` |
| Classes, objects, interfaces, abstract classes | `BaseEntity`, `Resource` (abstract); `Identifiable<ID>`, `Allocatable`, `GenericDao`, `View` |
| Access modifiers, static vs instance | private fields + getters, `protected typeAdjustedCost`, static utility classes with private constructors, singletons `AppConfig`, `DatabaseManager`, `EventBus` |
| Coding conventions, Maven | `pom.xml` with javafx, sqlite-jdbc, junit, exec plugins |
| **Unit 2** Exception hierarchy, custom exceptions | `CrisisConnectException` → `ValidationException`, `InsufficientResourceException`, `EntityNotFoundException`, `AllocationFailedException`; unchecked `DataAccessException` |
| ArrayList, LinkedList, HashSet/HashMap, Iterator | `MatchingEngine` (HashMap ledger, ArrayList), `EventBus` (LinkedList history), `CollectionUtils.removeIf` (explicit Iterator), `EnumMap` |
| Comparators, Comparable | `EmergencyRequest.compareTo`, `Comparator.comparingDouble(...).thenComparing(...)` in engine |
| PriorityQueue | `MatchingEngine.plan`, `CrisisService.priorityQueueSnapshot` |
| Generics & type safety | `GenericDao<T,ID>`, `JdbcTemplate.RowMapper<T>`, `CollectionUtils.countBy/sumBy/maxOf`, `UiUtils.column<T,V>`, `TransactionalWork<T>` |
| Objects, Optional, Math, LocalDate/Time | `Objects.requireNonNull/hash`, `Optional` from DAOs & `AppConfig`, `Math` in Haversine/priority, `LocalDate` expiry, `LocalDateTime`, `Duration` |
| **Unit 3** Byte & character streams, file R/W | `ReportService` (BufferedWriter), `CsvImportService` (BufferedReader), `DatabaseManager.readResource` |
| Serialization / deserialization | `BackupService` (ObjectOutputStream / ObjectInputStream), `DataSnapshot` with `transient` field & `serialVersionUID` |
| Logging | `LoggingConfig` – java.util.logging, custom Formatter, ConsoleHandler + rotating FileHandler |
| Properties files | `app.properties` (classpath defaults) + `config/app.properties` override |
| **Unit 4** JDBC architecture, connecting | `DatabaseManager` (DriverManager, PRAGMAs, schema script) |
| INSERT/UPDATE/DELETE/SELECT, PreparedStatement, ResultSet | `JdbcTemplate` + all `Jdbc*Dao` classes, generated keys, 3-table JOIN in `JdbcAllocationDao` |
| Transaction management | `TransactionManager.inTransaction` – setAutoCommit(false), commit, rollback; used by `CrisisService.runMatching` and `BackupService.restore` |
| DAO design pattern | `dao/` interfaces, `dao/jdbc/` implementations, `DaoFactory` |
| **Unit 5** Stage, Scene, Scene graph, lifecycle | `CrisisConnectApp.init()/start()/stop()` |
| Layouts HBox, VBox, GridPane, BorderPane, StackPane | `MainView` (StackPane + BorderPane), forms in GridPane, KPI rows in HBox, `FlowPane` tiles |
| Controls Label, TextField, Button, TextArea, CheckBox, ComboBox | Request / resource / organization forms, DatePicker, ToggleButton navigation, Hyperlink |
| ListView, TableView | Custom `ListCell` priority queue, sortable/filterable `TableView` with `FilteredList` + `SortedList`, progress/badge cells |
| Event handling & listeners | `setOnAction`, property listeners (live priority preview, numeric-only fields), `Timeline` clock & auto-match |
| Validation & feedback | `Validator` + `ValidationException` → `Alert`, confirmation dialogs, `TextInputDialog`, animated `Toast` notifications |
| **Beyond syllabus** | REST API (`com.sun.net.httpserver`), website, observer pattern, background `Task` threads, charts, CSS theming, JUnit 5 tests |

## 9. Testing
`mvn test` runs 12 JUnit 5 tests (all passing):
- `MatchingEngineTest` – critical first, nearest first, radius limit, split allocation.
- `ModelTest` – Haversine distance, `InsufficientResourceException`, factory subclasses, status transitions, priority ordering, validator.
- `DaoIntegrationTest` – CRUD round-trip on a temp SQLite DB and **rollback** verification.

Manual test cases:
| # | Action | Expected |
|---|---|---|
| 1 | Submit request with empty title & 0 quantity | Alert lists every error (ValidationException) |
| 2 | Run Smart Match on demo data | 19 dispatches, critical requests served first, Food/Shelter shortfall shown |
| 3 | Raise SOS from website while desktop is open | Red toast "New SOS from web portal", dashboard refreshes |
| 4 | Import `samples/field_requests.csv` | 4 imported, 2 rejected with line numbers |
| 5 | Backup → modify data → Restore | Data returns to snapshot state (single transaction) |
| 6 | Restock a resource and re-run match | Partial requests become fulfilled |

## 10. Screenshots
See `docs/screenshots/` – dashboard, match center, requests, resources, reports, website hero, live ops, SOS form.

## 11. Suggested demo flow for evaluation (≈5 min)
1. Start with `mvn javafx:run` → show **Dashboard** (KPIs, gap chart, priority queue).
2. **Requests** → raise a CRITICAL request; point out the live priority score and validation alerts.
3. **Match Center** → *Preview plan* (dry run) → *Run Smart Match*; explain the console log & transactions.
4. Back to **Dashboard** → fulfilment rate jumped; queue only shows partial requests.
5. Open **http://localhost:8080** → map with dispatch routes; raise an SOS from the website → toast pops on desktop.
6. **Reports** → generate SITREP, export CSV, backup; show `logs/crisisconnect-0.log`.
7. Run `mvn test`.

## 12. Future scope
Cloud database (PostgreSQL/MySQL on AWS RDS), Spring Boot + WebSockets for push updates, real maps (OpenStreetMap),
SMS/WhatsApp intake, role-based login, optimisation (min-cost flow) instead of greedy, mobile app for volunteers.

## 13. Likely viva questions
- **Why PriorityQueue and not sorting a list?** Insert/poll are O(log n) and the queue always gives the current most
  urgent request; it is the natural structure for triage.
- **What happens if stock changes between planning and saving?** The stock is re-read inside the transaction;
  `allocate()` throws `InsufficientResourceException`, the transaction rolls back, other requests continue.
- **Checked vs unchecked exceptions here?** Business errors (`ValidationException` etc.) are checked so callers must
  handle them; JDBC failures are wrapped in unchecked `DataAccessException` to keep DAO interfaces clean.
- **Why DAO?** Separates SQL from business logic; enables swapping DB and unit-testing the engine without a DB.
- **How does the website update the desktop app?** API thread → `CrisisService.createRequest` → `EventBus.publish`
  → JavaFX listener → `Platform.runLater` → toast + refresh (UI updates must happen on the FX thread).
- **Why `transient` in DataSnapshot?** `loadedFrom` is runtime-only information and should not be written to disk.
- **init() vs start()?** `init()` runs on the launcher thread (no UI allowed) – used for DB/API setup; `start()` runs on
  the JavaFX Application Thread and builds the Stage/Scene.
