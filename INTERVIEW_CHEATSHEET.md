# BookMyTicket — Interview Cheat Sheet

A Spring Boot movie-ticket booking app. Layered as **Controller → Service → Repository → Database**.
Use this one-pager to explain OOP, SOLID, and design patterns with concrete examples.

---

## 1. The 4 OOP Pillars

| Pillar | Example in repo | One-line explanation |
|--------|-----------------|----------------------|
| **Encapsulation** | `WalletEntity` (balance is `private`; changed only via `debit()` / `canAfford()`) | Object protects its own data; no direct field access. |
| **Abstraction** | `SeatLockProvider` interface | Defines *what* to do (lock/unlock/confirm seats), hides *how*. |
| **Inheritance** | `DbSeatLockProvider implements SeatLockProvider` | Classes inherit a contract and implement it. |
| **Polymorphism** | `PaymentStrategy` → UPI / Card / Wallet | Same `pay()` call, different behavior per object. |

---

## 2. SOLID Principles (strongest material)

- **S — Single Responsibility:** Each class = one job. `WalletService` (money), `CatalogService` (movies), `BookingService` (bookings). Layers are separated too.
- **O — Open/Closed:** Add a new payment type = write one new `PaymentStrategy` class. **No existing code changes** — the factory auto-discovers it.
- **L — Liskov Substitution:** `DbSeatLockProvider` and `InMemorySeatLockProvider` are interchangeable; `BookingEngine` works with either.
- **I — Interface Segregation:** Small, focused interfaces — `PricingStrategy.priceFor()`, `BookingObserver.onBookingConfirmed()`. No fat interfaces.
- **D — Dependency Inversion:** `BookingEngine` depends on the `SeatLockProvider` *interface*, not a concrete class. Spring injects the real one (constructor injection).

---

## 3. Design Patterns (name-drop these)

1. **Strategy** — `PaymentStrategy` (UPI/Card/Wallet) & `PricingStrategy`. Swap algorithms at runtime.
2. **Factory** — `PaymentStrategyFactory` returns the right strategy from a `PaymentMode` enum.
3. **Observer** — `BookingObserver` → `EmailNotifier`, `SmsNotifier`. On confirmation, all observers are notified.
4. **Saga / Orchestrator** — `SagaOrchestrator` runs steps **Hold → Pay → Confirm**; if a step fails it **compensates** (rolls back) in reverse order.
5. **Repository** — `MovieRepository` wraps Spring Data JPA and maps Entity ↔ domain Model.
6. **DTO** — `record` classes in `ApiResponses` decouple API shape from internal models.

---

## 4. Killer lines to say out loud

> "The booking flow uses the **Saga pattern**: hold seats, take payment, then confirm — and if payment fails it automatically **compensates** by releasing the seats. Payments use **Strategy + Factory**, so adding a new payment type means one new class with zero changes to existing code — that's **Open/Closed** in action."

> "`BookingEngine` depends on the `SeatLockProvider` **interface**, not the DB implementation — that's **Dependency Inversion**, and it lets me swap a real DB lock for an in-memory one in tests without touching the engine."

---

## 5. Likely follow-up Q&A

**Q: How would you add NetBanking payments?**
A: Create `NetBankingPaymentStrategy implements PaymentStrategy`, annotate `@Component`, done. The factory picks it up automatically. Zero changes to `BookingEngine`. (Open/Closed + Strategy)

**Q: How do you prevent two users booking the same seat?**
A: Seat locking via `SeatLockProvider`. The DB version uses optimistic locking (`@Version` on `ShowSeatEntity`) — concurrent updates throw `OptimisticLockingFailureException`, which becomes a "seat just taken" error.

**Q: What happens if payment succeeds but confirmation fails?**
A: The Saga compensates each completed step in reverse — releases the seats and (conceptually) refunds — keeping the system consistent.

**Q: Why interfaces everywhere?**
A: Testability and flexibility — depend on abstractions (DIP), swap implementations (LSP), extend without modifying (OCP).

---

## 6. Layer map (draw this if asked)

```
Client
  |
  v
ApiController / AuthController        <- REST endpoints (@RestController)
  |
  v
Services (CatalogService,            <- business logic (@Service)
          BookingService, WalletService, UserService)
  |
  v
BookingEngine ---> SagaOrchestrator ---> [HoldSeatsStep, PaymentStep, ConfirmSeatsStep]
  |                          \--> PaymentStrategyFactory --> UPI/Card/Wallet Strategy
  |                          \--> BookingObserver --> Email/SMS Notifier
  v
Repositories (MovieRepository, ...)  <- data access (@Repository, wraps JPA)
  |
  v
PostgreSQL (JPA Entities: Movie, Show, ShowSeat, Booking, Wallet, User)
```

**Build status:** `./gradlew build` → BUILD SUCCESSFUL (Java 17, Spring Boot 3.4.5).
