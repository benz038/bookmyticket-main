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

public final class ChatAi {

    private ChatAi() {
    }

    @Service
    public static class ChatService {

        private static final String SYSTEM_PROMPT = """
                You are the BookMyTicket assistant. You help users browse movies and book cinema tickets
                entirely through chat. Be friendly, concise and use INR (₹) for all prices.
                
                Rules:
                - ALWAYS use the provided tools to get real data. NEVER invent movies, showtimes, show ids,
                  seat ids, prices, availability or booking references — if you don't have it, call a tool.
                - A typical flow: searchMovies → getShowtimes(movieId) → getAvailableSeats(showId) →
                  holdSeats(showId, seats) → (user confirms) → bookSeats(showId, seats, paymentMode).
                - Seat ids look like A1, B2, C3. Rows: A = REGULAR (₹150), B = PREMIUM (₹250), C = RECLINER (₹400).
                - Payment methods are UPI, CARD or WALLET.
                - NEVER call bookSeats until the user has clearly confirmed the exact seats, the total price
                  and the payment method in a separate message. Always summarise and ask "Shall I confirm and
                  pay?" first.
                - Booking requires the user to be signed in. If a tool reports the user isn't signed in, ask
                  them to click "Sign in" and then continue.
                - Keep replies short. Show seat ids, times and totals clearly. Don't expose internal ids unless
                  they're useful to the user.
                """;

        private final ChatClient chatClient;
        private final boolean configured;

        public ChatService(ChatModel chatModel, ChatMemory chatMemory, BookingTools bookingTools, @Value("${spring.ai.openai.api-key:not-configured}") String apiKey) {
            this.configured = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
            this.chatClient = ChatClient.builder(chatModel).defaultSystem(SYSTEM_PROMPT).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).defaultTools(bookingTools).build();
        }

        public boolean isConfigured() {
            return configured;
        }

        public String chat(String conversationId, String message, User user) {
            if (!configured) {
                return "The AI assistant isn't configured yet. Set a GROQ_API_KEY environment variable " + "(get a free key at https://console.groq.com) and restart the app.";
            }
            Map<String, Object> toolContext = user == null ? Map.of() : Map.of(BookingTools.USER_KEY, user);
            return chatClient.prompt().user(message).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).toolContext(toolContext).call().content();
        }
    }

    @Component
    public static class BookingTools {

        public static final String USER_KEY = "user";

        private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
        private static final Map<String, Double> SEAT_PRICE = Map.of("REGULAR", 150.0, "PREMIUM", 250.0, "RECLINER", 400.0);
        private static final String NOT_SIGNED_IN = "The user is not signed in, so this action can't be completed. Ask them to sign in (the 'Sign in' button) and try again.";
        private final CatalogService catalogService;
        private final BookingService bookingService;
        private final WalletService walletService;

        public BookingTools(CatalogService catalogService, BookingService bookingService, WalletService walletService) {
            this.catalogService = catalogService;
            this.bookingService = bookingService;
            this.walletService = walletService;
        }

        @Tool(description = "List movies now showing. Optionally filter by a free-text query that " + "matches the title, language or genre. Returns each movie's id (needed for showtimes), " + "title, language, genre, certificate, rating and runtime.")
        public String searchMovies(@ToolParam(required = false, description = "Optional case-insensitive text to match against title/language/genre") String query) {
            List<ApiResponses.MovieResponse> movies = catalogService.listMovies();
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
            List<ApiResponses.MovieResponse> matched = movies.stream().filter(m -> q.isBlank() || m.title().toLowerCase(Locale.ENGLISH).contains(q) || m.language().toLowerCase(Locale.ENGLISH).contains(q) || m.genre().toLowerCase(Locale.ENGLISH).contains(q)).toList();
            if (matched.isEmpty()) {
                return q.isBlank() ? "No movies are currently listed." : "No movies match '" + query + "'.";
            }
            return matched.stream().map(m -> "- id=%s | %s (%s, %s) | %s | rating %.1f | %d min".formatted(m.id(), m.title(), m.language(), m.genre(), m.certificate(), m.rating(), m.durationMins())).collect(Collectors.joining("\n"));
        }

        @Tool(description = "List the cities that currently have theatres/shows, for the user to pick a location.")
        public String listCities() {
            List<String> cities = catalogService.listCities();
            return cities.isEmpty() ? "No cities available." : "Cities: " + String.join(", ", cities);
        }

        @Tool(description = "List the showtimes for a given movie id: returns each show's id (needed to view seats " + "or book), theatre name, city and start time.")
        public String getShowtimes(@ToolParam(description = "The movie id returned by searchMovies") String movieId) {
            try {
                List<ApiResponses.ShowResponse> shows = catalogService.getShowsForMovie(movieId);
                if (shows.isEmpty()) {
                    return "No showtimes found for movie " + movieId + ".";
                }
                return shows.stream().map(s -> "- showId=%s | %s, %s | %s".formatted(s.id(), s.theatreName(), s.city(), s.startTime().format(WHEN))).collect(Collectors.joining("\n"));
            } catch (RuntimeException e) {
                return "Could not load showtimes: " + e.getMessage();
            }
        }

        @Tool(description = "Show the live seat availability for a show id. Returns the available seat ids grouped " + "by type with their price in INR. Rows: A=REGULAR (Rs.150), B=PREMIUM (Rs.250), C=RECLINER (Rs.400).")
        public String getAvailableSeats(@ToolParam(description = "The show id returned by getShowtimes") String showId) {
            try {
                ApiResponses.SeatMapResponse map = catalogService.getSeatMap(showId);
                List<ApiResponses.SeatDto> free = map.seats().stream().filter(ApiResponses.SeatDto::available).toList();
                if (free.isEmpty()) {
                    return "Show " + showId + " is sold out — no seats available.";
                }
                String byType = free.stream().collect(Collectors.groupingBy(ApiResponses.SeatDto::type, java.util.TreeMap::new, Collectors.mapping(ApiResponses.SeatDto::id, Collectors.toList()))).entrySet().stream().map(e -> "%s (Rs.%.0f): %s".formatted(e.getKey(), SEAT_PRICE.getOrDefault(e.getKey(), 150.0), String.join(", ", e.getValue()))).collect(Collectors.joining("\n"));
                return "%d seats available for show %s:\n%s".formatted(map.availableCount(), showId, byType);
            } catch (RuntimeException e) {
                return "Could not load seats: " + e.getMessage();
            }
        }

        @Tool(description = "Hold (reserve) seats for the signed-in user on a show so no one else can take them while " + "they confirm. Call this before bookSeats. Requires the user to be signed in.")
        public String holdSeats(@ToolParam(description = "The show id") String showId, @ToolParam(description = "Seat ids to hold, e.g. [\"A1\",\"A2\"]") List<String> seatIds, ToolContext toolContext) {
            User user = currentUser(toolContext);
            if (user == null) {
                return NOT_SIGNED_IN;
            }
            try {
                ApiResponses.SeatHoldResponse hold = bookingService.holdSeats(showId, user, new ApiRequests.HoldSeatsRequest(seatIds));
                return "Held seats %s on show %s for ~%d seconds. Ask the user to confirm payment, then call bookSeats.".formatted(String.join(", ", hold.seatIds()), showId, hold.holdSeconds());
            } catch (RuntimeException e) {
                return "Could not hold those seats: " + e.getMessage();
            }
        }

        @Tool(description = "Book and pay for seats on a show for the signed-in user, completing the purchase. Only call " + "this AFTER the user has explicitly confirmed the seats, price and payment method. Requires sign-in.")
        public String bookSeats(@ToolParam(description = "The show id") String showId, @ToolParam(description = "Seat ids to book, e.g. [\"A1\",\"A2\"]") List<String> seatIds, @ToolParam(description = "Payment method: UPI, CARD or WALLET") String paymentMode, ToolContext toolContext) {
            User user = currentUser(toolContext);
            if (user == null) {
                return NOT_SIGNED_IN;
            }
            PaymentMode mode;
            try {
                mode = PaymentMode.valueOf(paymentMode.trim().toUpperCase(Locale.ENGLISH));
            } catch (RuntimeException e) {
                return "Invalid payment method '" + paymentMode + "'. Choose UPI, CARD or WALLET.";
            }
            try {
                Booking booking = bookingService.createBooking(showId, user, new ApiRequests.CreateBookingRequest(seatIds, mode));
                return switch (booking.getStatus()) {
                    case CONFIRMED ->
                            "Booking CONFIRMED. Reference %s, seats %s, paid Rs.%.0f via %s.".formatted(booking.getId(), booking.getSeats().stream().map(s -> s.getId()).collect(Collectors.joining(", ")), booking.getAmount(), mode);
                    default ->
                            "Payment did not go through (status %s) — the seats were released. The user can retry ".formatted(booking.getStatus()) + (mode == PaymentMode.WALLET ? "or pick another payment method (wallet balance may be too low)." : "or pick another payment method.");
                };
            } catch (RuntimeException e) {
                return "Booking failed: " + e.getMessage();
            }
        }

        @Tool(description = "List the signed-in user's bookings, newest first. Requires sign-in.")
        public String myBookings(ToolContext toolContext) {
            User user = currentUser(toolContext);
            if (user == null) {
                return NOT_SIGNED_IN;
            }
            List<ApiResponses.BookingSummaryResponse> bookings = bookingService.myBookings(user);
            if (bookings.isEmpty()) {
                return "No bookings yet.";
            }
            return bookings.stream().map(b -> "- %s | %s @ %s | %s | seats %s | Rs.%.0f | %s".formatted(b.bookingId(), b.movieTitle(), b.theatreName(), b.startTime().format(WHEN), String.join(", ", b.seats()), b.amount(), b.status())).collect(Collectors.joining("\n"));
        }

        @Tool(description = "Get the signed-in user's prepaid wallet balance in INR. Requires sign-in.")
        public String walletBalance(ToolContext toolContext) {
            User user = currentUser(toolContext);
            if (user == null) {
                return NOT_SIGNED_IN;
            }
            BigDecimal balance = walletService.getOrCreateBalance(user.getId());
            return "Wallet balance: Rs." + balance.toPlainString();
        }

        private User currentUser(ToolContext toolContext) {
            Object user = toolContext == null ? null : toolContext.getContext().get(USER_KEY);
            return user instanceof User u ? u : null;
        }
    }
}
