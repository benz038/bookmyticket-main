package bookmyticket.controller;

import bookmyticket.model.Models.ApiRequests;
import bookmyticket.model.Models.ApiResponses;
import bookmyticket.model.Models.Booking;
import bookmyticket.model.Models.BookingStatus;
import bookmyticket.model.Models.ChatReply;
import bookmyticket.model.Models.ChatRequest;
import bookmyticket.model.Models.User;
import bookmyticket.service.AppServices.BookingService;
import bookmyticket.service.AppServices.CatalogService;
import bookmyticket.service.ChatAi.ChatService;
import bookmyticket.service.AppServices.UserService;
import bookmyticket.service.AppServices.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ApiController {

    private final CatalogService catalogService;
    private final BookingService bookingService;
    private final UserService userService;
    private final WalletService walletService;
    private final ChatService chatService;

    public ApiController(CatalogService catalogService, BookingService bookingService, UserService userService, WalletService walletService, ChatService chatService) {
        this.catalogService = catalogService;
        this.bookingService = bookingService;
        this.userService = userService;
        this.walletService = walletService;
        this.chatService = chatService;
    }

    @GetMapping("/api/movies")
    public List<ApiResponses.MovieResponse> listMovies() {
        return catalogService.listMovies();
    }

    @GetMapping("/api/movies/{movieId}")
    public ApiResponses.MovieResponse getMovie(@PathVariable String movieId) {
        return catalogService.getMovie(movieId);
    }

    @GetMapping("/api/movies/{movieId}/shows")
    public List<ApiResponses.ShowResponse> getShows(@PathVariable String movieId) {
        return catalogService.getShowsForMovie(movieId);
    }

    @GetMapping("/api/shows/{showId}/seats")
    public ApiResponses.SeatMapResponse getSeatMap(@PathVariable String showId) {
        return catalogService.getSeatMap(showId);
    }

    @GetMapping("/api/cities")
    public List<String> listCities() {
        return catalogService.listCities();
    }

    @PostMapping("/api/shows/{showId}/holds")
    public ApiResponses.SeatHoldResponse hold(@PathVariable String showId, @Valid @RequestBody ApiRequests.HoldSeatsRequest request, @AuthenticationPrincipal OAuth2User principal) {
        User user = userService.fromPrincipal(principal);
        return bookingService.holdSeats(showId, user, request);
    }

    @DeleteMapping("/api/shows/{showId}/holds")
    public ResponseEntity<Void> release(@PathVariable String showId, @Valid @RequestBody ApiRequests.HoldSeatsRequest request, @AuthenticationPrincipal OAuth2User principal) {
        User user = userService.fromPrincipal(principal);
        bookingService.releaseSeats(showId, user, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/shows/{showId}/bookings")
    public ResponseEntity<ApiResponses.BookingResponse> createBooking(@PathVariable String showId, @Valid @RequestBody ApiRequests.CreateBookingRequest request, @AuthenticationPrincipal OAuth2User principal) {
        User user = userService.fromPrincipal(principal);
        Booking booking = bookingService.createBooking(showId, user, request);
        HttpStatus status = booking.getStatus() == BookingStatus.CONFIRMED ? HttpStatus.CREATED : HttpStatus.PAYMENT_REQUIRED;
        return ResponseEntity.status(status).body(ApiResponses.BookingResponse.from(booking));
    }

    @GetMapping("/api/me")
    public ResponseEntity<ApiResponses.UserResponse> me(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new ApiResponses.UserResponse(principal.getAttribute("name"), principal.getAttribute("email"), principal.getAttribute("picture")));
    }

    @GetMapping("/api/me/bookings")
    public ResponseEntity<List<ApiResponses.BookingSummaryResponse>> myBookings(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.fromPrincipal(principal);
        return ResponseEntity.ok(bookingService.myBookings(user));
    }

    @GetMapping("/api/me/wallet")
    public ResponseEntity<Map<String, Object>> wallet(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        User user = userService.fromPrincipal(principal);
        BigDecimal balance = walletService.getOrCreateBalance(user.getId());
        return ResponseEntity.ok(Map.of("balance", balance));
    }

    @PostMapping("/api/chat")
    public ResponseEntity<ChatReply> chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal OAuth2User principal) {
        User user = principal == null ? null : userService.fromPrincipal(principal);
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank()) ? UUID.randomUUID().toString() : request.conversationId();
        String reply = chatService.chat(conversationId, request.message(), user);
        return ResponseEntity.ok(new ChatReply(reply, conversationId, user != null));
    }
}
