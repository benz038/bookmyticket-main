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
import bookmyticket.service.BookingCore.*;
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

public final class AppServices {

    private AppServices() {
    }

    @Service
    public static class CatalogService {

        private final MovieRepository movieRepository;
        private final ShowRepository showRepository;
        private final BookingEngine bookingEngine;
        private final TheatreJpaRepository theatreJpa;

        public CatalogService(MovieRepository movieRepository, ShowRepository showRepository, BookingEngine bookingEngine, TheatreJpaRepository theatreJpa) {
            this.movieRepository = movieRepository;
            this.showRepository = showRepository;
            this.bookingEngine = bookingEngine;
            this.theatreJpa = theatreJpa;
        }

        public List<ApiResponses.MovieResponse> listMovies() {
            return movieRepository.findAll().stream().map(ApiResponses.MovieResponse::from).toList();
        }

        public ApiResponses.MovieResponse getMovie(String movieId) {
            return ApiResponses.MovieResponse.from(requireMovie(movieId));
        }

        public List<ApiResponses.ShowResponse> getShowsForMovie(String movieId) {
            requireMovie(movieId);
            return showRepository.findByMovie(movieId).stream().map(ApiResponses.ShowResponse::from).toList();
        }

        public ApiResponses.SeatMapResponse getSeatMap(String showId) {
            Show show = requireShow(showId);
            Set<Seat> available = new HashSet<>(bookingEngine.getAvailableSeats(show));
            List<ApiResponses.SeatDto> seats = show.getScreen().getSeats().stream().map(seat -> ApiResponses.SeatDto.from(seat, available.contains(seat))).toList();
            return new ApiResponses.SeatMapResponse(showId, available.size(), seats);
        }

        public List<String> listCities() {
            return theatreJpa.findAll().stream().map(TheatreEntity::getCity).distinct().sorted().toList();
        }

        public Show requireShow(String showId) {
            return showRepository.findById(showId).orElseThrow(() -> ApiException.notFound("Show not found: " + showId));
        }

        private Movie requireMovie(String movieId) {
            return movieRepository.findById(movieId).orElseThrow(() -> ApiException.notFound("Movie not found: " + movieId));
        }
    }

    @Service
    public static class BookingService {

        private final BookingEngine bookingEngine;
        private final CatalogService catalogService;
        private final ShowRepository showRepository;
        private final BookingRepository bookingRepository;
        private final int holdSeconds;

        public BookingService(BookingEngine bookingEngine, CatalogService catalogService, ShowRepository showRepository, BookingRepository bookingRepository, @Value("${bookmyticket.seat-hold-seconds:300}") int holdSeconds) {
            this.bookingEngine = bookingEngine;
            this.catalogService = catalogService;
            this.showRepository = showRepository;
            this.bookingRepository = bookingRepository;
            this.holdSeconds = holdSeconds;
        }

        public ApiResponses.SeatHoldResponse holdSeats(String showId, User user, ApiRequests.HoldSeatsRequest request) {
            Show show = catalogService.requireShow(showId);
            List<Seat> seats = resolveSeats(show, request.seatIds());
            bookingEngine.holdSeats(user, show, seats);
            Instant heldUntil = Instant.now().plusSeconds(holdSeconds);
            return new ApiResponses.SeatHoldResponse(showId, request.seatIds(), heldUntil, holdSeconds);
        }

        public void releaseSeats(String showId, User user, ApiRequests.HoldSeatsRequest request) {
            Show show = catalogService.requireShow(showId);
            List<Seat> seats = resolveSeats(show, request.seatIds());
            bookingEngine.releaseSeats(user, show, seats);
        }

        public Booking createBooking(String showId, User user, ApiRequests.CreateBookingRequest request) {
            Show show = catalogService.requireShow(showId);
            List<Seat> seats = resolveSeats(show, request.seatIds());
            Booking booking = bookingEngine.book(user, show, seats, request.paymentMode());
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                bookingRepository.save(booking);
            }
            return booking;
        }

        public List<ApiResponses.BookingSummaryResponse> myBookings(User user) {
            List<ApiResponses.BookingSummaryResponse> out = new ArrayList<>();
            for (BookingRecord rec : bookingRepository.findByUser(user.getId())) {

                Show show = showRepository.findById(rec.showId()).orElse(null);
                if (show == null) continue;
                List<String> seats = Arrays.stream(rec.seatLabels().split(",")).filter(s -> !s.isBlank()).toList();
                out.add(new ApiResponses.BookingSummaryResponse(rec.id(), show.getMovie().getName(), show.getTheatre().getName(), show.getStartTime(), seats, rec.amount(), rec.status().name(), rec.createdAt()));
            }
            return out;
        }

        private List<Seat> resolveSeats(Show show, List<String> seatIds) {
            List<Seat> seats = new ArrayList<>();
            for (String seatId : seatIds) {
                Seat seat = showRepository.findSeat(show, seatId).orElseThrow(() -> ApiException.notFound("Seat " + seatId + " not in show " + show.getId()));
                seats.add(seat);
            }
            return seats;
        }
    }

    @Service
    public static class UserService {

        private final UserRepository userRepository;

        public UserService(UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        public User fromPrincipal(OAuth2User principal) {
            String email = principal.getAttribute("email");
            String name = principal.getAttribute("name");
            if (email == null) {
                throw new IllegalStateException("Google account did not return an email");
            }

            return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(new User(email, name != null ? name : email, email, "")));
        }
    }

    @Service
    public static class WalletService {

        public static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

        private final WalletJpaRepository repo;

        public WalletService(WalletJpaRepository repo) {
            this.repo = repo;
        }

        @Transactional
        public BigDecimal getOrCreateBalance(String userId) {
            return getOrCreate(userId).getBalance();
        }

        @Transactional
        public boolean debit(String userId, BigDecimal amount) {
            WalletEntity wallet = getOrCreate(userId);
            if (!wallet.canAfford(amount)) {
                return false;
            }
            wallet.debit(amount);
            try {
                repo.saveAndFlush(wallet);
            } catch (OptimisticLockingFailureException e) {
                return false;
            }
            return true;
        }

        private WalletEntity getOrCreate(String userId) {
            return repo.findById(userId).orElseGet(() -> repo.save(new WalletEntity(userId, INITIAL_BALANCE)));
        }
    }
}
