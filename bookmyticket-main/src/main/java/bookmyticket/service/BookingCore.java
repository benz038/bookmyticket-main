package bookmyticket.service;

import bookmyticket.exception.ApiException;
import bookmyticket.model.Models.ApiRequests;
import bookmyticket.model.Models.ApiResponses;
import bookmyticket.model.Models.Booking;
import bookmyticket.model.Models.BookingStatus;
import bookmyticket.model.Models.Movie;
import bookmyticket.model.Models.Seat;
import bookmyticket.model.Models.SeatType;
import bookmyticket.model.Models.Show;
import bookmyticket.model.Models.User;
import bookmyticket.repository.Entities.ShowSeatEntity;
import bookmyticket.repository.Entities.TheatreEntity;
import bookmyticket.repository.Entities.WalletEntity;
import bookmyticket.repository.Repositories.BookingRecord;
import bookmyticket.repository.Repositories.BookingRepository;
import bookmyticket.repository.Repositories.MovieRepository;
import bookmyticket.repository.Repositories.ShowRepository;
import bookmyticket.repository.Repositories.ShowSeatJpaRepository;
import bookmyticket.repository.Repositories.TheatreJpaRepository;
import bookmyticket.repository.Repositories.UserRepository;
import bookmyticket.repository.Repositories.WalletJpaRepository;
import bookmyticket.service.AppServices.*;
import bookmyticket.service.ChatAi.*;
import bookmyticket.service.Payments.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public final class BookingCore {

    private BookingCore() {
    }

    public static interface SagaStep {

        void execute(BookingContext context);

        void compensate(BookingContext context);

        default String name() {
            return getClass().getSimpleName();
        }
    }

    public static interface SeatLockProvider {

        void lockSeats(Show show, List<Seat> seats, String userId);

        void confirmSeats(Show show, List<Seat> seats, String userId);

        void unlockSeats(Show show, List<Seat> seats, String userId);

        boolean isSeatAvailable(Show show, Seat seat);

        List<Seat> getUnavailableSeats(Show show);
    }

    public static class BookingEngine {

        private final SeatLockProvider lockProvider;
        private final PricingStrategy pricingStrategy;
        private final SagaOrchestrator bookingSaga;
        private final List<BookingObserver> observers = new CopyOnWriteArrayList<>();

        public BookingEngine(SeatLockProvider lockProvider, PricingStrategy pricingStrategy, PaymentStrategyFactory paymentStrategyFactory) {
            this.lockProvider = lockProvider;
            this.pricingStrategy = pricingStrategy;
            this.bookingSaga = new SagaOrchestrator(List.of(new HoldSeatsStep(lockProvider), new PaymentStep(paymentStrategyFactory), new ConfirmSeatsStep(lockProvider)));
        }

        public void registerObserver(BookingObserver observer) {
            observers.add(observer);
        }

        public void holdSeats(User user, Show show, List<Seat> seats) {
            lockProvider.lockSeats(show, seats, user.getId());
        }

        public void releaseSeats(User user, Show show, List<Seat> seats) {
            lockProvider.unlockSeats(show, seats, user.getId());
        }

        public List<Seat> getAvailableSeats(Show show) {
            Set<Seat> unavailable = new HashSet<>(lockProvider.getUnavailableSeats(show));
            List<Seat> available = new ArrayList<>();
            for (Seat s : show.getScreen().getSeats()) {
                if (!unavailable.contains(s)) available.add(s);
            }
            return available;
        }

        public Booking book(User user, Show show, List<Seat> seats, PaymentMode mode) {
            double amount = seats.stream().mapToDouble(pricingStrategy::priceFor).sum();
            Booking booking = new Booking(generateBookingId(), show, user, seats, amount);
            BookingContext context = new BookingContext(user, show, seats, mode, amount);

            try {
                bookingSaga.run(context);
                booking.setStatus(BookingStatus.CONFIRMED);
                for (BookingObserver o : observers) o.onBookingConfirmed(booking);
                return booking;
            } catch (ApiException e) {
                booking.setStatus(BookingStatus.FAILED);
                if (e.getStatus() == HttpStatus.PAYMENT_REQUIRED) {
                    return booking;
                }
                throw e;
            }
        }

        private String generateBookingId() {
            return "BMT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    public static class SagaOrchestrator {

        private final List<SagaStep> steps;

        public SagaOrchestrator(List<SagaStep> steps) {
            this.steps = List.copyOf(steps);
        }

        public void run(BookingContext context) {
            Deque<SagaStep> completed = new ArrayDeque<>();
            try {
                for (SagaStep step : steps) {
                    step.execute(context);
                    completed.push(step);
                }
            } catch (RuntimeException failure) {
                compensate(completed, context);
                throw failure;
            }
        }

        private void compensate(Deque<SagaStep> completed, BookingContext context) {
            while (!completed.isEmpty()) {
                SagaStep step = completed.pop();
                try {
                    step.compensate(context);
                } catch (RuntimeException compensationError) {

                    System.err.println("    [SAGA] compensation failed for " + step.name() + ": " + compensationError.getMessage());
                }
            }
        }
    }

    public static class BookingContext {

        private final User user;
        private final Show show;
        private final List<Seat> seats;
        private final PaymentMode paymentMode;
        private final double amount;

        private boolean paid;

        public BookingContext(User user, Show show, List<Seat> seats, PaymentMode paymentMode, double amount) {
            this.user = user;
            this.show = show;
            this.seats = seats;
            this.paymentMode = paymentMode;
            this.amount = amount;
        }

        public User getUser() {
            return user;
        }

        public Show getShow() {
            return show;
        }

        public List<Seat> getSeats() {
            return seats;
        }

        public PaymentMode getPaymentMode() {
            return paymentMode;
        }

        public double getAmount() {
            return amount;
        }

        public boolean isPaid() {
            return paid;
        }

        public void setPaid(boolean paid) {
            this.paid = paid;
        }
    }

    public static class HoldSeatsStep implements SagaStep {

        private final SeatLockProvider lockProvider;

        public HoldSeatsStep(SeatLockProvider lockProvider) {
            this.lockProvider = lockProvider;
        }

        @Override
        public void execute(BookingContext c) {
            lockProvider.lockSeats(c.getShow(), c.getSeats(), c.getUser().getId());
        }

        @Override
        public void compensate(BookingContext c) {
            lockProvider.unlockSeats(c.getShow(), c.getSeats(), c.getUser().getId());
        }
    }

    public static class PaymentStep implements SagaStep {

        private final PaymentStrategyFactory paymentStrategyFactory;

        public PaymentStep(PaymentStrategyFactory paymentStrategyFactory) {
            this.paymentStrategyFactory = paymentStrategyFactory;
        }

        @Override
        public void execute(BookingContext c) {
            PaymentRequest request = new PaymentRequest(c.getUser().getId(), c.getAmount());
            boolean paid = paymentStrategyFactory.strategyFor(c.getPaymentMode()).pay(request);
            if (!paid) {
                throw ApiException.paymentFailed("Payment declined for Rs." + c.getAmount());
            }
            c.setPaid(true);
        }

        @Override
        public void compensate(BookingContext c) {
            if (c.isPaid()) {

                System.out.println("    [REFUND] Rs." + c.getAmount() + " refunded to " + c.getUser().getEmail());
            }
        }
    }

    public static class ConfirmSeatsStep implements SagaStep {

        private final SeatLockProvider lockProvider;

        public ConfirmSeatsStep(SeatLockProvider lockProvider) {
            this.lockProvider = lockProvider;
        }

        @Override
        public void execute(BookingContext c) {
            lockProvider.confirmSeats(c.getShow(), c.getSeats(), c.getUser().getId());
        }

        @Override
        public void compensate(BookingContext c) {
            lockProvider.unlockSeats(c.getShow(), c.getSeats(), c.getUser().getId());
        }
    }

    public static class SeatLock {
        private final Seat seat;
        private final String lockedByUserId;
        private final Instant lockedAt;
        private final int timeoutSeconds;

        public SeatLock(Seat seat, String lockedByUserId, int timeoutSeconds) {
            this.seat = seat;
            this.lockedByUserId = lockedByUserId;
            this.timeoutSeconds = timeoutSeconds;
            this.lockedAt = Instant.now();
        }

        public Seat getSeat() {
            return seat;
        }

        public String getLockedByUserId() {
            return lockedByUserId;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(lockedAt.plusSeconds(timeoutSeconds));
        }
    }

    public static class DbSeatLockProvider implements SeatLockProvider {

        private final ShowSeatJpaRepository repo;
        private final int holdSeconds;

        public DbSeatLockProvider(ShowSeatJpaRepository repo, @Value("${bookmyticket.seat-hold-seconds:300}") int holdSeconds) {
            this.repo = repo;
            this.holdSeconds = holdSeconds;
        }

        @Override
        @Transactional
        public void lockSeats(Show show, List<Seat> seats, String userId) {
            Instant until = Instant.now().plusSeconds(holdSeconds);
            List<ShowSeatEntity> targets = new ArrayList<>();
            for (Seat seat : seats) {
                ShowSeatEntity ss = require(show.getId(), seat.getId());
                boolean heldByMe = ss.getStatus() == bookmyticket.model.Models.ShowSeatStatus.HELD && userId.equals(ss.getHeldBy());
                if (!ss.isAvailableNow() && !heldByMe) {
                    throw ApiException.seatUnavailable("Seat " + seat + " available nahi (locked/booked by someone).");
                }
                ss.hold(userId, until);
                targets.add(ss);
            }
            flush(targets, "Seat just taken — try again.");
        }

        @Override
        @Transactional
        public void confirmSeats(Show show, List<Seat> seats, String userId) {
            List<ShowSeatEntity> targets = new ArrayList<>();
            for (Seat seat : seats) {
                ShowSeatEntity ss = require(show.getId(), seat.getId());
                boolean mine = ss.getStatus() == bookmyticket.model.Models.ShowSeatStatus.HELD && userId.equals(ss.getHeldBy()) && ss.getHeldUntil() != null && Instant.now().isBefore(ss.getHeldUntil());
                if (!mine) {
                    throw ApiException.seatUnavailable("Lock expired/lost for seat " + seat + ". Phir se try karo.");
                }
                ss.book();
                targets.add(ss);
            }
            flush(targets, "Lost the seat at confirm — try again.");
        }

        @Override
        @Transactional
        public void unlockSeats(Show show, List<Seat> seats, String userId) {
            for (Seat seat : seats) {
                repo.findByShowIdAndSeatLabel(show.getId(), seat.getId()).ifPresent(ss -> {
                    if (ss.getStatus() == bookmyticket.model.Models.ShowSeatStatus.HELD && userId.equals(ss.getHeldBy())) {
                        ss.release();
                        repo.save(ss);
                    }
                });
            }
        }

        @Override
        @Transactional(readOnly = true)
        public boolean isSeatAvailable(Show show, Seat seat) {
            return repo.findByShowIdAndSeatLabel(show.getId(), seat.getId()).map(ShowSeatEntity::isAvailableNow).orElse(false);
        }

        @Override
        @Transactional(readOnly = true)
        public List<Seat> getUnavailableSeats(Show show) {
            List<Seat> out = new ArrayList<>();
            for (ShowSeatEntity ss : repo.findByShowId(show.getId())) {
                if (!ss.isAvailableNow()) {
                    out.add(new Seat(ss.getSeatLabel(), ss.getRowIdx(), ss.getColIdx(), ss.getSeatType()));
                }
            }
            return out;
        }

        private ShowSeatEntity require(String showId, String seatLabel) {
            return repo.findByShowIdAndSeatLabel(showId, seatLabel).orElseThrow(() -> ApiException.seatUnavailable("Unknown seat " + seatLabel));
        }

        private void flush(List<ShowSeatEntity> targets, String raceMessage) {
            try {
                repo.saveAllAndFlush(targets);
            } catch (OptimisticLockingFailureException e) {
                throw ApiException.seatUnavailable(raceMessage);
            }
        }
    }

    public static class InMemorySeatLockProvider implements SeatLockProvider {

        private final int lockTimeoutSeconds;
        private final Map<String, Map<Seat, SeatLock>> locks = new ConcurrentHashMap<>();
        private final Map<String, Set<Seat>> booked = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> showMutex = new ConcurrentHashMap<>();

        public InMemorySeatLockProvider(int lockTimeoutSeconds) {
            this.lockTimeoutSeconds = lockTimeoutSeconds;
        }

        private ReentrantLock mutexFor(Show show) {
            return showMutex.computeIfAbsent(show.getId(), k -> new ReentrantLock());
        }

        @Override
        public void lockSeats(Show show, List<Seat> seats, String userId) {
            ReentrantLock mutex = mutexFor(show);
            mutex.lock();
            try {
                for (Seat seat : seats) {
                    if (!isAvailableInternal(show, seat) && !lockedByMe(show, seat, userId)) {
                        throw ApiException.seatUnavailable("Seat " + seat + " available nahi (locked/booked by someone).");
                    }
                }
                Map<Seat, SeatLock> showLocks = locks.computeIfAbsent(show.getId(), k -> new ConcurrentHashMap<>());
                for (Seat seat : seats) {
                    showLocks.put(seat, new SeatLock(seat, userId, lockTimeoutSeconds));
                }
            } finally {
                mutex.unlock();
            }
        }

        @Override
        public void confirmSeats(Show show, List<Seat> seats, String userId) {
            ReentrantLock mutex = mutexFor(show);
            mutex.lock();
            try {
                Map<Seat, SeatLock> showLocks = locks.get(show.getId());
                Set<Seat> showBooked = booked.computeIfAbsent(show.getId(), k -> ConcurrentHashMap.newKeySet());
                for (Seat seat : seats) {
                    SeatLock l = (showLocks == null) ? null : showLocks.get(seat);
                    if (l == null || !l.getLockedByUserId().equals(userId)) {

                        throw ApiException.seatUnavailable("Lock expired/lost for seat " + seat + ". Phir se try karo.");
                    }
                }
                for (Seat seat : seats) {
                    showBooked.add(seat);
                    showLocks.remove(seat);
                }
            } finally {
                mutex.unlock();
            }
        }

        @Override
        public void unlockSeats(Show show, List<Seat> seats, String userId) {
            ReentrantLock mutex = mutexFor(show);
            mutex.lock();
            try {
                Map<Seat, SeatLock> showLocks = locks.get(show.getId());
                if (showLocks == null) return;
                for (Seat seat : seats) {
                    SeatLock l = showLocks.get(seat);
                    if (l != null && l.getLockedByUserId().equals(userId)) {
                        showLocks.remove(seat);
                    }
                }
            } finally {
                mutex.unlock();
            }
        }

        @Override
        public boolean isSeatAvailable(Show show, Seat seat) {
            ReentrantLock mutex = mutexFor(show);
            mutex.lock();
            try {
                return isAvailableInternal(show, seat);
            } finally {
                mutex.unlock();
            }
        }

        private boolean isAvailableInternal(Show show, Seat seat) {
            Set<Seat> showBooked = booked.get(show.getId());
            if (showBooked != null && showBooked.contains(seat)) return false;

            Map<Seat, SeatLock> showLocks = locks.get(show.getId());
            if (showLocks == null) return true;
            SeatLock l = showLocks.get(seat);
            if (l == null) return true;
            if (l.isExpired()) {
                showLocks.remove(seat);
                return true;
            }
            return false;
        }

        private boolean lockedByMe(Show show, Seat seat, String userId) {
            Map<Seat, SeatLock> showLocks = locks.get(show.getId());
            if (showLocks == null) return false;
            SeatLock l = showLocks.get(seat);
            return l != null && !l.isExpired() && l.getLockedByUserId().equals(userId);
        }

        @Override
        public List<Seat> getUnavailableSeats(Show show) {
            ReentrantLock mutex = mutexFor(show);
            mutex.lock();
            try {
                List<Seat> out = new ArrayList<>();
                Set<Seat> showBooked = booked.get(show.getId());
                if (showBooked != null) out.addAll(showBooked);
                Map<Seat, SeatLock> showLocks = locks.get(show.getId());
                if (showLocks != null) {
                    for (Map.Entry<Seat, SeatLock> e : showLocks.entrySet()) {
                        if (!e.getValue().isExpired()) out.add(e.getKey());
                    }
                }
                return out;
            } finally {
                mutex.unlock();
            }
        }
    }
}
