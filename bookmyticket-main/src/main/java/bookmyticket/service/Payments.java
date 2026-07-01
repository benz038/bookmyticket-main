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
import bookmyticket.service.BookingCore.*;
import bookmyticket.service.ChatAi.*;

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

public final class Payments {

    private Payments() {
    }

    public static enum PaymentMode {UPI, CARD, WALLET}

    public static interface PricingStrategy {
        double priceFor(Seat seat);
    }

    public static interface PaymentStrategy {

        PaymentMode mode();

        boolean pay(PaymentRequest request);
    }

    public static interface PaymentStrategyFactory {
        PaymentStrategy strategyFor(PaymentMode mode);
    }

    public static interface BookingObserver {
        void onBookingConfirmed(Booking booking);
    }

    public static class SeatTypePricingStrategy implements PricingStrategy {
        private final Map<SeatType, Double> priceMap = new EnumMap<>(SeatType.class);

        public SeatTypePricingStrategy() {
            priceMap.put(SeatType.REGULAR, 150.0);
            priceMap.put(SeatType.PREMIUM, 250.0);
            priceMap.put(SeatType.RECLINER, 400.0);
        }

        @Override
        public double priceFor(Seat seat) {
            return priceMap.getOrDefault(seat.getType(), 150.0);
        }
    }

    public static record PaymentRequest(String userId, double amount) {
    }

    @Component
    public static class DefaultPaymentStrategyFactory implements PaymentStrategyFactory {

        private final Map<PaymentMode, PaymentStrategy> registry = new EnumMap<>(PaymentMode.class);

        public DefaultPaymentStrategyFactory(List<PaymentStrategy> strategies) {
            for (PaymentStrategy strategy : strategies) {
                registry.put(strategy.mode(), strategy);
            }
        }

        @Override
        public PaymentStrategy strategyFor(PaymentMode mode) {
            PaymentStrategy strategy = registry.get(mode);
            if (strategy == null) {
                throw new IllegalArgumentException("Unsupported payment mode: " + mode);
            }
            return strategy;
        }
    }

    @Component
    public static class UpiPaymentStrategy implements PaymentStrategy {
        @Override
        public PaymentMode mode() {
            return PaymentMode.UPI;
        }

        @Override
        public boolean pay(PaymentRequest request) {
            System.out.println("    [UPI] Rs." + request.amount() + " paid via UPI.");
            return true;
        }
    }

    @Component
    public static class CardPaymentStrategy implements PaymentStrategy {
        @Override
        public PaymentMode mode() {
            return PaymentMode.CARD;
        }

        @Override
        public boolean pay(PaymentRequest request) {
            System.out.println("    [CARD] Rs." + request.amount() + " paid via Card.");
            return true;
        }
    }

    @Component
    public static class WalletPaymentStrategy implements PaymentStrategy {

        private final WalletService walletService;

        public WalletPaymentStrategy(WalletService walletService) {
            this.walletService = walletService;
        }

        @Override
        public PaymentMode mode() {
            return PaymentMode.WALLET;
        }

        @Override
        public boolean pay(PaymentRequest request) {
            boolean ok = walletService.debit(request.userId(), BigDecimal.valueOf(request.amount()));
            System.out.println(ok ? "    [WALLET] Rs." + request.amount() + " debited from wallet." : "    [WALLET] insufficient balance for Rs." + request.amount() + ".");
            return ok;
        }
    }

    public static class EmailNotifier implements BookingObserver {
        @Override
        public void onBookingConfirmed(Booking booking) {
            System.out.println("    [EMAIL] " + booking.getUser().getEmail() + " -> Booking " + booking.getId() + " confirmed for " + booking.getShow().getMovie().getName());
        }
    }

    public static class SmsNotifier implements BookingObserver {
        @Override
        public void onBookingConfirmed(Booking booking) {
            System.out.println("    [SMS] " + booking.getUser().getPhone() + " -> Seats " + booking.getSeats() + " booked.");
        }
    }
}
