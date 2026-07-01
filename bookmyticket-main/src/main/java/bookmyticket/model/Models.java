package bookmyticket.model;

import bookmyticket.service.Payments.PaymentMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public final class Models {

    private Models() {
    }

    public static enum SeatType {
        REGULAR, PREMIUM, RECLINER
    }

    public static enum BookingStatus {
        CREATED, CONFIRMED, EXPIRED, FAILED
    }

    public static enum ShowSeatStatus {
        AVAILABLE, HELD, BOOKED
    }

    public static class Movie {
        private final String id;
        private final String name;
        private final String language;
        private final String genre;
        private final int durationMins;
        private final String certificate;
        private final double rating;
        private final String posterUrl;

        public Movie(String id, String name, String language, String genre, int durationMins, String certificate, double rating, String posterUrl) {
            this.id = id;
            this.name = name;
            this.language = language;
            this.genre = genre;
            this.durationMins = durationMins;
            this.certificate = certificate;
            this.rating = rating;
            this.posterUrl = posterUrl;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getLanguage() {
            return language;
        }

        public String getGenre() {
            return genre;
        }

        public int getDurationMins() {
            return durationMins;
        }

        public String getCertificate() {
            return certificate;
        }

        public double getRating() {
            return rating;
        }

        public String getPosterUrl() {
            return posterUrl;
        }
    }

    public static class Theatre {
        private final String id;
        private final String name;
        private final String city;
        private final List<Screen> screens;

        public Theatre(String id, String name, String city, List<Screen> screens) {
            this.id = id;
            this.name = name;
            this.city = city;
            this.screens = screens;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getCity() {
            return city;
        }

        public List<Screen> getScreens() {
            return screens;
        }
    }

    public static class Screen {
        private final String id;
        private final String name;
        private final List<Seat> seats;

        public Screen(String id, String name, List<Seat> seats) {
            this.id = id;
            this.name = name;
            this.seats = seats;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Seat> getSeats() {
            return seats;
        }
    }

    public static class Seat {
        private final String id;
        private final int row;
        private final int col;
        private final SeatType type;

        public Seat(String id, int row, int col, SeatType type) {
            this.id = id;
            this.row = row;
            this.col = col;
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public int getRow() {
            return row;
        }

        public int getCol() {
            return col;
        }

        public SeatType getType() {
            return type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Seat)) return false;
            return id.equals(((Seat) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return id;
        }
    }

    public static class Show {
        private final String id;
        private final Movie movie;
        private final Theatre theatre;
        private final Screen screen;
        private final LocalDateTime startTime;

        public Show(String id, Movie movie, Theatre theatre, Screen screen, LocalDateTime startTime) {
            this.id = id;
            this.movie = movie;
            this.theatre = theatre;
            this.screen = screen;
            this.startTime = startTime;
        }

        public String getId() {
            return id;
        }

        public Movie getMovie() {
            return movie;
        }

        public Theatre getTheatre() {
            return theatre;
        }

        public Screen getScreen() {
            return screen;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }
    }

    public static class Booking {
        private final String id;
        private final Show show;
        private final User user;
        private final List<Seat> seats;
        private final double amount;
        private BookingStatus status;

        public Booking(String id, Show show, User user, List<Seat> seats, double amount) {
            this.id = id;
            this.show = show;
            this.user = user;
            this.seats = seats;
            this.amount = amount;
            this.status = BookingStatus.CREATED;
        }

        public String getId() {
            return id;
        }

        public Show getShow() {
            return show;
        }

        public User getUser() {
            return user;
        }

        public List<Seat> getSeats() {
            return seats;
        }

        public double getAmount() {
            return amount;
        }

        public BookingStatus getStatus() {
            return status;
        }

        public void setStatus(BookingStatus status) {
            this.status = status;
        }
    }

    public static class User {
        private final String id;
        private final String name;
        private final String email;
        private final String phone;

        public User(String id, String name, String email, String phone) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.phone = phone;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        public String getPhone() {
            return phone;
        }
    }

    public static final class ApiRequests {

        private ApiRequests() {
        }

        public record CreateBookingRequest(@NotEmpty(message = "at least one seatId is required") List<String> seatIds,

                                           @NotNull(message = "paymentMode is required (UPI or CARD)") PaymentMode paymentMode) {
        }

        public record HoldSeatsRequest(@NotEmpty(message = "at least one seatId is required") List<String> seatIds) {
        }
    }

    public static final class ApiResponses {

        private ApiResponses() {
        }

        public record MovieResponse(String id, String title, String language, String genre, String certificate,
                                    double rating, int durationMins, String posterUrl) {
            public static MovieResponse from(Movie m) {
                return new MovieResponse(m.getId(), m.getName(), m.getLanguage(), m.getGenre(), m.getCertificate(), m.getRating(), m.getDurationMins(), m.getPosterUrl());
            }
        }

        public record ShowResponse(String id, String theatreId, String theatreName, String city,
                                   LocalDateTime startTime) {
            public static ShowResponse from(Show show) {
                return new ShowResponse(show.getId(), show.getTheatre().getId(), show.getTheatre().getName(), show.getTheatre().getCity(), show.getStartTime());
            }
        }

        public record SeatDto(String id, int row, int col, String type, boolean available) {
            public static SeatDto from(Seat seat, boolean available) {
                return new SeatDto(seat.getId(), seat.getRow(), seat.getCol(), seat.getType().name(), available);
            }
        }

        public record SeatMapResponse(String showId, int availableCount, List<SeatDto> seats) {
        }

        public record BookingResponse(String bookingId, String showId, String userId, List<String> seats, double amount,
                                      String status) {
            public static BookingResponse from(Booking booking) {
                List<String> seatIds = booking.getSeats().stream().map(s -> s.getId()).toList();
                return new BookingResponse(booking.getId(), booking.getShow().getId(), booking.getUser().getId(), seatIds, booking.getAmount(), booking.getStatus().name());
            }
        }

        public record BookingSummaryResponse(String bookingId, String movieTitle, String theatreName,
                                             LocalDateTime startTime, List<String> seats, double amount, String status,
                                             Instant bookedAt) {
        }

        public record SeatHoldResponse(String showId, List<String> seatIds, Instant heldUntil, int holdSeconds) {
        }

        public record UserResponse(String name, String email, String picture) {
        }

        public record ApiError(Instant timestamp, int status, String error, String message, String path) {
            public static ApiError of(int status, String error, String message, String path) {
                return new ApiError(Instant.now(), status, error, message, path);
            }
        }
    }

    public static record ChatRequest(@NotBlank(message = "message is required") String message, String conversationId) {
    }

    public static record ChatReply(String reply, String conversationId, boolean signedIn) {
    }
}
