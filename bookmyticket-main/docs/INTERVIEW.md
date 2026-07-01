# BookMyTicket — Architecture & Interview Deep‑Dive

This document explains **how the system works**, **why it is built this way**, and gives you
**ready‑to‑say talking points** for an interview. It is the companion to the
[README](../README.md).

- [1. The 60‑second pitch](#1-the-60second-pitch)
- [2. Architecture: layers & the framework‑free core](#2-architecture-layers--the-frameworkfree-core)
- [3. The booking request lifecycle](#3-the-booking-request-lifecycle)
- [4. Concurrency: no double‑booking (the headline)](#4-concurrency-no-doublebooking-the-headline)
- [5. The Saga: hold → pay → confirm](#5-the-saga-hold--pay--confirm)
- [6. Design patterns (where, why, how to explain)](#6-design-patterns-where-why-how-to-explain)
- [7. SOLID, with concrete examples](#7-solid-with-concrete-examples)
- [8. Data model & persistence](#8-data-model--persistence)
- [9. Security & authentication](#9-security--authentication)
- [10. Trade‑offs & scale‑up](#10-tradeoffs--scaleup)
- [11. Rapid‑fire interview Q&A](#11-rapidfire-interview-qa)

---

## 1. The 60‑second pitch

> "BookMyTicket is a movie‑ticket booking service. The interesting engineering problem is
> **concurrency**: many users fight for the same seat, and we must never sell it twice. I solved
> it with **optimistic locking** on a per‑seat row (`@Version`) so the *database* is the source
> of truth — 50 threads racing for one seat yield exactly one winner. A booking spans a seat
> hold and a payment, which commit independently, so instead of one ACID transaction I use an
> **orchestration saga** with **compensating actions** (release the hold, refund) that undo on
> failure. The code is a clean **layered design** — thin Spring controllers over a
> **framework‑free domain core** wired by **Dependency Inversion**, using Strategy, Factory,
> Observer and Template‑Method patterns so new payment modes, seat types and notifiers are
> drop‑in with zero edits to existing code."

That single paragraph hits: domain, the hard problem, the solution, the patterns, and the
principles. Everything below is the evidence.

---

## 2. Architecture: layers & the framework‑free core

```
            HTTP (browser SPA: index.html + css + js)
                         │
   ┌─────────────────────▼─────────────────────┐
   │  WEB TIER (Spring)                          │   controller/  + model/ (web records) +
   │  thin @RestControllers, validate, map DTOs  │   exception/  + config/SecurityConfig
   └─────────────────────┬─────────────────────┘
                         │  constructor‑injected (Spring)
   ┌─────────────────────▼─────────────────────┐
   │  SERVICE / DOMAIN TIER                       │   service/  — use‑cases (Catalog/Booking/User/
   │  use‑cases, engine, strategies, saga, locks  │   Wallet) + BookingEngine, saga, seat‑lock,
   │                                              │   pricing, payment, notifiers, ChatService
   └─────────────────────┬─────────────────────┘
                         │  SeatLockProvider interface
   ┌─────────────────────▼─────────────────────┐
   │  PERSISTENCE                                 │   repository/  @Entity + Spring Data +
   │  entities, JPA, Flyway, Neon Postgres       │   domain repos that map entity ↔ model objects
   └────────────────────────────────────────────┘
```

**Key idea — the core has no framework annotations.** The strategy, engine, saga and lock classes
live in `service/` next to the `@Service` use‑cases, but the *domain* ones (`BookingEngine`,
`PricingStrategy`, `PaymentStrategy`, `SeatLockProvider`, the saga steps) and everything in `model/`
are plain, unit‑testable POJOs. Spring only owns their *lifecycle*, wired in one place:
[config/BeanConfig.java](../src/main/java/bookmyticket/config/BeanConfig.java). The persistence
adapter (`DbSeatLockProvider`) is the *only* core‑facing class that is a `@Component`, and it is
injected **by interface** (`SeatLockProvider`).

**Why this matters (say this):** "The domain doesn't know Spring or JPA exist. That keeps it
testable without a container or a database, and means I can swap the seat‑lock implementation —
in‑memory, DB, or Redis — by changing one bean, with controllers and services untouched. That's
**Dependency Inversion** and the **Ports & Adapters** idea in practice."

### Folder responsibilities

| Package | Responsibility |
|---|---|
| `controller/` | HTTP endpoints across two files — `ApiController` (all business + AI chat endpoints) and `AuthController` (auth status + dev login). Thin: parse, validate, delegate, map to a record, set status code. |
| `service/` | The business layer split by concern into **four files** (nested classes): `AppServices` (use‑cases `CatalogService`/`BookingService`/`UserService`/`WalletService`), `ChatAi` (the AI `ChatService` + `BookingTools`), `BookingCore` (`BookingEngine`, the saga `SagaOrchestrator` + steps, seat‑locking `SeatLockProvider` + Db/InMemory), and `Payments` (pricing & payment strategies + factory, `PaymentMode`, notifiers). |
| `repository/` | The data layer in **two files** — `Entities.java` (`@Entity` classes; `ShowSeatEntity`/`WalletEntity` carry `@Version`) and `Repositories.java` (Spring Data `JpaRepository` interfaces + domain‑facing repos that map entities ↔ `model` objects via `toDomain`, so JPA never leaks into the domain). |
| `model/` | **One file, `Models.java`** — domain POJOs and enums (`SeatType`, `BookingStatus`, `ShowSeatStatus`) plus the web records (`ApiRequests`/`ApiResponses` incl. `ApiError`, and `ChatRequest`/`ChatReply`) as nested classes. |
| `config/` | `SecurityConfig`, `BeanConfig` (wires the LLD core), `DataSeeder`, `FlywayConfig`, `AiConfig`, `LocalDataSourceConfig`. |
| `exception/` | `GlobalExceptionHandler` (`@RestControllerAdvice`) + a single `ApiException` carrying its HTTP status. |

---

## 3. The booking request lifecycle

`POST /api/shows/{id}/bookings` →
[ApiController](../src/main/java/bookmyticket/controller/ApiController.java) →
`BookingService` → the **saga**:

```
1. HoldSeatsStep    execute: lockSeats(...)        compensate: releaseSeats(...)
2. PaymentStep      execute: paymentStrategy.pay() compensate: refund(...)
3. ConfirmSeatsStep execute: confirmSeats(...)     compensate: releaseSeats(...)
```

- **All steps pass** → booking `CONFIRMED` → observers fire (email/SMS) → **201 Created**.
- **Payment declined** → `ApiException` (402) → saga runs `HoldSeatsStep`'s compensation
  (releases the hold) → booking `FAILED` → **402 Payment Required**.
- **Seat lost at confirm** (someone else won the race) → `ApiException` (409) → saga
  refunds **and** releases → **409 Conflict**.

The controller never sees a user id in the body — the authenticated principal is the owner
(`userService.fromPrincipal(principal)`), which closes an **IDOR** hole (you can't book "as"
someone else).

---

## 4. Concurrency: no double‑booking (the headline)

This is the question the whole project is designed to answer. There are **two implementations of
the same `SeatLockProvider` interface**, and the design lets you pick one without touching anything
else.

### 4a. `DbSeatLockProvider` — optimistic locking (active, production design)

Class: `DbSeatLockProvider` in [service/BookingCore.java](../src/main/java/bookmyticket/service/BookingCore.java).

Each seat is one row in `bmt.show_seats` with a `version BIGINT` column mapped by Hibernate
`@Version` on the `ShowSeatEntity` class in
[repository/Entities.java](../src/main/java/bookmyticket/repository/Entities.java).

**The race, step by step:**

1. Two transactions T1 and T2 both read seat `A1`, both see `version = 5`, both think it's free.
2. Both call `hold(...)` and flush. Hibernate issues
   `UPDATE show_seats SET status=?, version=6 WHERE id=? AND version=5`.
3. The database serializes the two updates. The **first** commit matches `version=5`, updates one
   row, sets `version=6`. ✅
4. The **second** update now finds **0 rows** matching `version=5` (it's already 6). Hibernate sees
   "expected 1 row, updated 0" → throws `OptimisticLockingFailureException`.
5. We translate that to `ApiException` (409 CONFLICT) → **HTTP 409**. ❌

> Exactly one winner, no locks held across the think‑time, no double‑booking. **Verified: 50
> threads on one seat → 1 success, 49 rejected.**

**Holds carry an expiry** (`held_until`): an abandoned payment frees the seat automatically — a
`HELD` row past its expiry counts as available again (lazy expiry, no background job needed).

**Re‑entrancy:** `lockSeats` is re‑entrant for the *same* user, so the final `ConfirmSeatsStep`
simply promotes the hold the user already placed, instead of fighting for the seat again.

### 4b. `InMemorySeatLockProvider` — same guarantee, no DB (reference)

Class: `InMemorySeatLockProvider` in [service/BookingCore.java](../src/main/java/bookmyticket/service/BookingCore.java).

State in a `ConcurrentHashMap`; a **per‑show `ReentrantLock`** makes "check availability + set
state" one atomic critical section. The lock is **per show, not global**, so different shows book
in parallel. This is what the fast unit tests run against (no Neon, no OAuth).

### Optimistic vs pessimistic — know the trade‑off

| | Optimistic (`@Version`) — used here | Pessimistic (`SELECT … FOR UPDATE`) |
|---|---|---|
| Holds a DB lock during think‑time? | **No** | Yes (until commit) |
| Best when | conflicts are **rare** (most seats are free) | conflicts are **hot/frequent** |
| Failure mode | retry/refuse the loser | blocking + possible deadlocks |
| Scales reads | great | worse |

"Seats are mostly uncontended, so optimistic is the right default; for a single super‑hot seat
you could fall back to a pessimistic `FOR UPDATE` or a Redis `SETNX`+TTL front‑lock." (See §10.)

---

## 5. The Saga: hold → pay → confirm

Files: [service/](../src/main/java/bookmyticket/service/) (`SagaOrchestrator`, `SagaStep`, `BookingContext`, and the three steps).

**Why not just `@Transactional`?** A booking touches two things that **commit independently** —
the seat hold (a DB write) and the payment (a call to an external gateway). A database transaction
**cannot roll back a charge on a payment gateway.** So there is no single ACID transaction to wrap
the whole operation.

The **orchestration saga** gives "all‑or‑nothing" semantics across independent commits: each step
has a *compensating* action, and if a later step fails, the `SagaOrchestrator` undoes the completed
steps **in reverse order**.

| Step | Action (execute) | Compensation |
|---|---|---|
| 1. `HoldSeatsStep` | hold the seats | release the hold |
| 2. `PaymentStep` | charge the user | refund |
| 3. `ConfirmSeatsStep` | hold → booked | release the seats |

`SagaOrchestratorTest` proves the compensations run in **reverse order** on failure. "It's the
same pattern you'd use across microservices — just with events/commands instead of in‑process
method calls."

---

## 6. Design patterns (where, why, how to explain)

| Pattern | Where | One‑liner for the interview |
|---|---|---|
| **Strategy** | `PricingStrategy`, `PaymentStrategy` | "Encapsulate interchangeable algorithms behind an interface; the engine picks one at runtime without knowing the concrete type." |
| **Factory** | `PaymentStrategyFactory` + `DefaultPaymentStrategyFactory` | "Spring injects *all* `PaymentStrategy` beans; the factory builds a `mode → strategy` map. A new payment mode is a new `@Component` — **no edits** to the factory (OCP)." |
| **Observer** | `BookingObserver` + `EmailNotifier`/`SmsNotifier` | "On `CONFIRMED`, the engine notifies a list of observers. Add a push/WhatsApp channel by registering another observer." |
| **Saga / Orchestrator** | `SagaOrchestrator` | "Distributed‑transaction substitute: per‑step compensations, undo in reverse on failure." |
| **Repository** | domain repos in `repository/*` over Spring Data + `@Entity` | "The service depends on a domain‑facing repository that returns clean `model` objects; the JPA entity/Spring‑Data detail stays hidden in the same data layer." |
| **DTO** | `model/*` records (`ApiRequests`/`ApiResponses`) | "The HTTP contract is separate from domain models, so internal refactors don't break the API." |
| **Dependency Injection / Singleton** | Spring beans in `config/` | "Container‑managed single instances, constructor‑injected — testable and swappable." |

**The killer example to volunteer (Strategy + Factory + OCP together):**
> "To add Net‑Banking payments I add one class `NetBankingPaymentStrategy implements
> PaymentStrategy` annotated `@Component`. Spring auto‑collects it into the factory's map. I
> change **zero** existing files. That's Open/Closed in action."

---

## 7. SOLID, with concrete examples

- **S — Single Responsibility.** Locking (`SeatLockProvider`), pricing (`PricingStrategy`),
  paying (`PaymentStrategy`), notifying (`BookingObserver`) are each their own class. Controllers
  only do HTTP; services only do use‑cases.
- **O — Open/Closed.** New `SeatType`, `PaymentMode`, or notifier is **additive** — existing code
  is untouched (see the factory example above).
- **L — Liskov Substitution.** `DbSeatLockProvider` and `InMemorySeatLockProvider` are drop‑in
  substitutes for the `SeatLockProvider` interface — the engine works identically with either, so
  swapping the bean changes nothing about the booking flow's correctness.
- **I — Interface Segregation.** Small, focused interfaces (`PaymentStrategy`, `PricingStrategy`,
  `SeatLockProvider`, `BookingObserver`) — no fat "do‑everything" interface.
- **D — Dependency Inversion.** The engine depends on
  `SeatLockProvider` / `PricingStrategy` / `PaymentStrategyFactory` **interfaces**, all
  constructor‑injected. High‑level policy doesn't depend on low‑level detail — both depend on
  abstractions.

---

## 8. Data model & persistence

- **Schema `bmt`**, owned by **Flyway**
  ([V1__init.sql](../src/main/resources/db/migration/V1__init.sql),
  [V2__wallet.sql](../src/main/resources/db/migration/V2__wallet.sql)). Hibernate runs in
  `validate` mode, so the entities must match the migrations exactly — schema drift fails fast at
  startup instead of corrupting data silently.

| Table | Notes |
|---|---|
| `movies`, `theatres`, `shows` | catalog |
| **`show_seats`** | one row per seat per show; **`version`** column = the optimistic lock; `UNIQUE(show_id, seat_label)` |
| `users` | created on first login from the OAuth identity |
| `bookings` | id `BMT-…`, status, total, seat list |
| **`wallets`** | one per user, ₹1000 seed, **`version`** for safe concurrent debits |

**Why `validate` + Flyway (say this):** "Migrations are the single source of truth for schema;
`ddl-auto: validate` means Hibernate never silently alters production tables — it only checks the
mapping matches. Schema changes are reviewable, versioned SQL."

---

## 9. Security & authentication

File: [config/SecurityConfig.java](../src/main/java/bookmyticket/config/SecurityConfig.java).

- **Public:** the SPA + static assets, `GET /api/movies/**`, `GET /api/shows/**`, `/api/me/**`,
  `/api/auth/**`.
- **Authenticated:** `POST/DELETE /api/shows/*/holds` and `POST /api/shows/*/bookings`.
- API clients get a **401** (via `HttpStatusEntryPoint`), not an HTML redirect to Google, so the
  SPA can react.
- `oauth2Login` is wired **only if** a Google `ClientRegistrationRepository` exists — so the app
  still starts and browses without credentials.
- **Dev login** (`AuthController` in [controller/AuthController.java](../src/main/java/bookmyticket/controller/AuthController.java))
  builds the *same* `OAuth2User` principal Google would, and is **disabled by construction** the
  moment real Google credentials are present — so production never exposes it.
- The booking owner is always the **authenticated principal**, never a request‑body field
  (prevents IDOR).

> Honest caveat to raise yourself: "CSRF is disabled because it's a local session‑cookie demo. In
> production I'd enable `CookieCsrfTokenRepository` and send the token from the SPA."

---

## 10. Trade‑offs & scale‑up

Good follow‑up answers when the interviewer pushes on scale:

- ✅ **Already done:** booked state lives in a DB row with an optimistic `@Version` column — correct
  across multiple app instances out of the box (the DB is the arbiter).
- **Distributed hold:** put **Redis `SETNX` + TTL** in front for a fast, cross‑node seat hold
  before you ever touch Postgres — cuts DB contention.
- **Async payment:** make the gateway call asynchronous (webhook/callback); the **hold TTL** covers
  the in‑flight window, and the saga resumes on the callback.
- **Hot single seat:** fall back to **pessimistic `SELECT … FOR UPDATE`** for a notoriously
  contended seat/row, accepting the blocking cost where conflicts are guaranteed.
- **Read scale:** seat‑map reads are pollable and cacheable; writes are the only serialized path.
- **Idempotency:** add an idempotency key on `POST /bookings` so client retries don't double‑charge.

---

## 11. Rapid‑fire interview Q&A

**Q: How do you prevent two people booking the same seat?**
Optimistic locking. Each seat is a row with a `@Version`; concurrent `UPDATE … WHERE id=? AND
version=?` means only the first commit wins, the loser updates 0 rows and is rejected with 409. No
lock is held during user think‑time.

**Q: Why a saga instead of `@Transactional`?**
Because payment is an external call that a DB transaction can't roll back. The saga gives
all‑or‑nothing across independent commits via compensating actions (release hold, refund), undone
in reverse order on failure.

**Q: Where would optimistic locking be the wrong choice?**
Under heavy contention on the *same* row — you'd waste work on constant retries. Use pessimistic
locking (or a Redis front‑lock) there.

**Q: How do you add a new payment method with zero edits?**
Implement `PaymentStrategy`, annotate `@Component`. Spring injects it into
`DefaultPaymentStrategyFactory`'s `mode → strategy` map automatically. Open/Closed.

**Q: How is the domain testable without Spring or a DB?**
The core is plain POJOs depending on interfaces. You can wire the `InMemorySeatLockProvider` and a
fake payment factory to exercise the engine — no Spring context, no DB, including a 50‑thread race.

**Q: What happens if the payment succeeds but confirm fails?**
`ConfirmSeatsStep` throws `ApiException` (409); the saga compensates `PaymentStep` (refund)
**and** `HoldSeatsStep` (release) → 409. The user is made whole.

**Q: How does an abandoned checkout free the seat?**
The hold has a `held_until` expiry; a `HELD` row past its expiry is treated as available again
(lazy expiry), so no cron job is required.

**Q: Why keep the domain free of Spring annotations?**
Testability and portability — the business rules don't depend on a framework, and the
infrastructure (DB/Redis) is a swappable adapter behind `SeatLockProvider` (Dependency Inversion /
Ports & Adapters).
