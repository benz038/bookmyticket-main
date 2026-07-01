# BookMyTicket — Spring Boot (Java)

A movie‑ticket booking app with a real‑site‑style web UI, a **Spring Boot 3** REST backend,
**Google (OAuth2) sign‑in**, and **Neon (PostgreSQL) persistence via Flyway**, built on a clean,
framework‑free **Low‑Level‑Design (LLD) core**. The headline feature is **thread‑safe seat
booking with no double‑booking**, enforced by the **database** (optimistic locking).

> Browse movies → pick a showtime → choose seats → **sign in** → pay & book.

📖 **For the deep dive (design patterns, SOLID, concurrency, saga, and interview Q&A) see
[docs/INTERVIEW.md](docs/INTERVIEW.md).**

---

## Run

The Gradle wrapper is committed — no global Gradle needed (**JDK 17** required).

```bash
./gradlew bootRun                 # start the server on http://localhost:8080
./gradlew build                   # compile + build the runnable jar
java -jar build/libs/bookmyticket-1.0.0.jar
```

Open **http://localhost:8080/** — movie grid → showtimes by theatre → cinema seat map.
Browsing is public; **booking requires sign‑in**.

> On a fresh database the first start seeds the catalog (6 movies × shows × seats) in one
> transaction — ~30–60s over Neon. It is idempotent, so later starts are fast.

The same three scenarios the old console demo showed (happy path, duplicate‑seat reject,
50‑thread concurrency) are proven by the unit tests — run `./gradlew test` (no DB needed).

---

## Configuration — one file

**Everything lives in [src/main/resources/application.yml](src/main/resources/application.yml).**
There are no more `google.properties` / `neon.properties` side files. Secrets use
`${ENV_VAR:default}` placeholders, so the app runs out of the box with the committed demo
defaults **and** can be locked down by exporting environment variables:

| Setting | Env var | Notes |
|---|---|---|
| DB JDBC URL | `DB_URL` | Neon serverless Postgres |
| DB user / password | `DB_USERNAME` / `DB_PASSWORD` | |
| Google client id / secret | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | enables real Google sign‑in |
| Groq (AI chat) API key | `GROQ_API_KEY` | enables the chat‑booking bot — free key at console.groq.com |
| AI endpoint / model (optional) | `LLM_BASE_URL` / `LLM_MODEL` | defaults to Groq + `llama-3.3-70b-versatile` |
| Seat‑hold TTL (s) | `bookmyticket.seat-hold-seconds` | default `300` |

```powershell
# PowerShell — override a secret without touching the file
$env:DB_PASSWORD = '...'
$env:GOOGLE_CLIENT_SECRET = '...'
```

> **Production note:** never commit real secrets. Set them as environment variables (or a
> git‑ignored `application-local.yml`). The committed defaults are demo‑grade only.

### Database (Neon / Postgres)
- The app owns the **`bmt`** schema; **Flyway** creates and owns it
  ([db/migration/V1__init.sql](src/main/resources/db/migration/V1__init.sql),
  [V2__wallet.sql](src/main/resources/db/migration/V2__wallet.sql)). Hibernate runs in
  `validate` mode, so entities must match the migrations.

### Google sign‑in (optional)
1. [Google Cloud Console](https://console.cloud.google.com/) → **Credentials → OAuth client
   ID → Web application**.
2. Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`.
3. Export `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` (or set them in `application.yml`).

**No Google creds? Dev sign‑in works out of the box.** Until a real Google client is present,
**Sign in** uses a local dev login (`GET /dev-login`) that builds the same `OAuth2User`
principal Google would — so you can book immediately. It is **auto‑disabled** the moment real
Google credentials are configured, so production never exposes it.

---

## REST API

Seeded: 6 movies, 3 theatres in Mumbai; each show has rows **A = REGULAR / B = PREMIUM /
C = RECLINER** (8 seats each). Booking users are created automatically from your identity.

| Method | Path | Auth | Purpose | Codes |
|---|---|---|---|---|
| GET    | `/api/movies`              | public | list movies                       | 200 |
| GET    | `/api/movies/{id}`         | public | movie detail                      | 200 / 404 |
| GET    | `/api/movies/{id}/shows`   | public | showtimes (theatre + time)        | 200 / 404 |
| GET    | `/api/shows/{id}/seats`    | public | live seat map                     | 200 / 404 |
| POST   | `/api/shows/{id}/holds`    | **login** | hold seats (lock for others)   | 200 / 409 / 400 / 404 / 401 |
| DELETE | `/api/shows/{id}/holds`    | **login** | release a held selection       | 204 / 401 |
| POST   | `/api/shows/{id}/bookings` | **login** | create booking (hold→pay→confirm) | 201 / 402 / 409 / 400 / 404 / 401 |
| GET    | `/api/me`                  | -      | current user or 401               | 200 / 401 |
| GET    | `/api/me/bookings`         | **login** | my bookings (latest first)     | 200 / 401 |
| GET    | `/api/me/wallet`           | **login** | wallet balance (₹1000 seed)    | 200 / 401 |
| GET    | `/api/auth/status`         | public | is Google sign‑in configured      | 200 |

```bash
# the body carries no userId — the logged‑in user is the owner
curl -X POST localhost:8080/api/shows/{showId}/bookings \
  -H 'Content-Type: application/json' \
  -d '{"seatIds":["A1","B1"],"paymentMode":"UPI"}'   # 401 unless authenticated
```

`201` confirmed · `402` payment failed (seats auto‑released) · `409` seat already taken
(the concurrency guard) · `400` invalid body · `404` unknown · `401` not logged in.
Booking IDs look like **`BMT-1A2B3C4D`**.

---

## AI chat booking (Groq) 🤖

A floating **chat assistant** (bottom‑right of every page) lets users browse and book by
natural language — *"What's showing tonight?"*, *"Book 2 recliners for Inception"*. It uses
**Spring AI** with an OpenAI‑compatible client pointed at **Groq's free Llama hosting**, and
**tool calling** so the model drives the *real* services (no separate logic, no DB access).

| Method | Path | Auth | Purpose | Codes |
|---|---|---|---|---|
| POST | `/api/chat` | public | one chat turn `{message, conversationId}` → `{reply, conversationId, signedIn}` | 200 / 400 |

**How it works**
- The bot can only call registered **tools** that wrap existing services —
  `searchMovies`, `getShowtimes`, `getAvailableSeats`, `holdSeats`, `bookSeats`,
  `myBookings`, `walletBalance` (see `BookingTools` in [`service/ChatAi.java`](src/main/java/bookmyticket/service/ChatAi.java)).
- **Browsing is public; booking requires sign‑in.** The authenticated user is injected into the
  tool context server‑side (never read from the chat text), so the bot can only act for the
  person who is actually signed in. Anonymous users get a "please sign in" prompt at the booking step.
- The system prompt forbids inventing data and requires an explicit **confirm** turn before
  `bookSeats` ever charges the wallet/UPI/card.
- Per‑conversation, **in‑memory** message window keeps multi‑turn context (nothing persisted).

**Enable it**
1. Get a free key at **[console.groq.com](https://console.groq.com)** → API Keys (`gsk_...`).
2. Export it and run:
   ```powershell
   $env:GROQ_API_KEY = 'gsk_...'    # PowerShell
   ./gradlew bootRun
   ```
   ```bash
   export GROQ_API_KEY='gsk_...'    # bash
   ./gradlew bootRun
   ```
3. Open the chat bubble and book. Without a key the bot replies that it isn't configured (no 500).

> Provider is swappable by env vars only (`LLM_BASE_URL` / `LLM_MODEL`) — e.g. point it at a
> local Ollama with no code change, just like the Spring AI `ChatClient` abstraction allows.

---

## How a booking works (hold → pay → confirm)

The UI is a **two‑phase** flow, like the real site:

1. **Select seats** → the seat map polls every few seconds, so seats others take disappear live.
2. **Proceed** → `POST /api/shows/{id}/holds` puts a **HELD** lock (with a TTL) on those seats,
   so everyone else immediately sees them as unavailable. The payment page shows a countdown.
3. **Pay** → `POST .../bookings` promotes the hold to a booking. Press **Back** (or let the
   timer run out) and the hold is released, freeing the seats again.

The hold and the booking are the **same** `SeatLockProvider` lock; `lockSeats` is re‑entrant
for the same user, so the booking saga simply confirms the hold the user already placed.

### Concurrency — the important bit
`DbSeatLockProvider` (active) makes no‑double‑booking a **database** guarantee:
`show_seats.version` (`@Version`) means two transactions racing for the same seat both read
version *N*, but only one commits (→ *N+1*); the loser fails the version check and is rejected →
`ApiException` (409 CONFLICT). Verified against Neon: **50 threads on one seat →
exactly 1 success, 49 rejected**. (`InMemorySeatLockProvider` is a reference impl with the same
guarantee using a per‑show `ReentrantLock`.)

### Wallet (₹1000 to start)
Every user gets a prepaid wallet seeded to **₹1000** on first use (lazy‑create). Paying with
**Wallet** debits via `WalletPaymentStrategy`; too‑low balance is declined (→ saga releases the
seats → **402**). The wallet row carries a `@Version` column, so two concurrent debits can't
double‑spend — the same optimistic‑locking trick as the seats.

---

## Project structure

```
bookmyticket/
├─ build.gradle · settings.gradle · gradlew         Gradle (wrapper committed)
├─ README.md  ·  docs/INTERVIEW.md                  this file + design deep‑dive
└─ src/
   ├─ main/
   │  ├─ java/bookmyticket/
   │  │  ├─ BookMyTicketApplication.java             Spring Boot entry point
   │  │  ├─ controller/   2 files — ApiController (all business + chat endpoints) and
   │  │  │                 AuthController (auth status + dev login)
   │  │  ├─ service/      4 files by concern (nested classes):
   │  │  │                 AppServices (Catalog/Booking/User/Wallet use‑cases),
   │  │  │                 ChatAi (AI ChatService + BookingTools),
   │  │  │                 BookingCore (BookingEngine, saga steps, seat‑lock),
   │  │  │                 Payments (pricing & payment strategies + factory, notifiers)
   │  │  ├─ repository/   2 files — Entities.java (@Entity classes, @Version) and
   │  │  │                 Repositories.java (Spring Data + domain repos that map entity ↔ `model`)
   │  │  ├─ model/        1 file — Models.java: domain POJOs + enums and the web records
   │  │  │                 (ApiRequests/ApiResponses, ChatRequest/ChatReply) as nested classes
   │  │  ├─ config/       SecurityConfig, BeanConfig (wires the LLD core), DataSeeder, AiConfig
   │  │  └─ exception/    GlobalExceptionHandler + one ApiException (409 / 402 / 404)
   │  └─ resources/
   │     ├─ application.yml                          single config file
   │     ├─ db/migration/ V1__init.sql, V2__wallet.sql   Flyway schema "bmt"
   │     └─ static/                                  ← FRONTEND (one place)
   │        ├─ index.html                            markup only
   │        ├─ css/styles.css                        all styles
   │        ├─ js/app.js  ·  js/chat.js              SPA logic + chat widget (vanilla JS)
   │        └─ posters/                              movie poster images
```

The domain core depends only on the `SeatLockProvider` **interface** — today the DB‑backed impl
is the bean; swapping to in‑memory/Redis is a one‑line change with controllers and services
untouched. That is the whole point of the DIP design.

> **Note:** CSRF is disabled (session‑cookie API for a local demo). For production, enable
> `CookieCsrfTokenRepository` and send the token from the SPA.

---

## Design patterns & SOLID (summary)

| Pattern | Where | Why |
|---|---|---|
| Strategy | `PricingStrategy`, `PaymentStrategy` | swap an algorithm without touching the engine |
| Factory | `PaymentStrategyFactory` (Spring registry) | new payment = new `@Component`, zero edits (OCP) |
| Observer | `BookingObserver` (Email/Sms) | notify on confirm; add channels freely |
| Saga (orchestration) | `SagaOrchestrator`: Hold→Pay→Confirm | each step has a compensation; undo on failure |
| Repository | `repository/` domain repos over Spring Data + `@Entity` | service gets clean `model` objects; JPA stays hidden |
| DTO | `model/` records (`ApiRequests`/`ApiResponses`) | decouple the wire format from domain models |
| Singleton | Spring beans (`config/`) | one container‑managed instance per type |

**SOLID:** single‑responsibility classes (lock vs pricing vs payment vs notify); open/closed for
new seat types, payment modes, and notifiers; Liskov‑clean provider substitution; small focused
interfaces; and dependency **inversion** — the engine depends on `SeatLockProvider` /
`PricingStrategy` / `PaymentStrategyFactory` *interfaces*, all constructor‑injected by Spring.
Full explanations and interview talking points are in
[docs/INTERVIEW.md](docs/INTERVIEW.md).

---

## Design & correctness notes

The seat‑locking design is what keeps double‑booking impossible under load:

- **Happy path** → booking **CONFIRMED**; **payment declined → FAILED and the seat is released**
  (saga compensation); a seat already taken → **409 CONFLICT**.
- **Concurrency** — `DbSeatLockProvider` uses a `@Version` optimistic lock, so 50 threads racing
  for one seat yield **exactly 1 success, 49 rejected**.
- **Saga** — on any step failure the orchestrator runs compensations in **reverse order**.
