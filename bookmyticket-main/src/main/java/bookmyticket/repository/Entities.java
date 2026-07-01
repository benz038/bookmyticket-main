package bookmyticket.repository;

import bookmyticket.model.Models.BookingStatus;
import bookmyticket.model.Models.SeatType;
import bookmyticket.model.Models.ShowSeatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

public final class Entities {

    private Entities() {
    }

    @Entity
    @Table(name = "movies")
    public static class MovieEntity {

        @Id
        private String id;
        private String name;
        private String language;
        private String genre;
        private int durationMins;
        private String certificate;
        private double rating;
        private String posterUrl;

        protected MovieEntity() {}

        public MovieEntity(String id, String name, String language, String genre, int durationMins,
                           String certificate, double rating, String posterUrl) {
            this.id = id;
            this.name = name;
            this.language = language;
            this.genre = genre;
            this.durationMins = durationMins;
            this.certificate = certificate;
            this.rating = rating;
            this.posterUrl = posterUrl;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getLanguage() { return language; }
        public String getGenre() { return genre; }
        public int getDurationMins() { return durationMins; }
        public String getCertificate() { return certificate; }
        public double getRating() { return rating; }
        public String getPosterUrl() { return posterUrl; }
    }

    @Entity
    @Table(name = "shows")
    public static class ShowEntity {

        @Id
        private String id;

        @ManyToOne(fetch = FetchType.EAGER)
        @JoinColumn(name = "movie_id")
        private MovieEntity movie;

        @ManyToOne(fetch = FetchType.EAGER)
        @JoinColumn(name = "theatre_id")
        private TheatreEntity theatre;

        private String screenName;
        private LocalDateTime startTime;

        protected ShowEntity() {}

        public ShowEntity(String id, MovieEntity movie, TheatreEntity theatre, String screenName, LocalDateTime startTime) {
            this.id = id;
            this.movie = movie;
            this.theatre = theatre;
            this.screenName = screenName;
            this.startTime = startTime;
        }

        public String getId() { return id; }
        public MovieEntity getMovie() { return movie; }
        public TheatreEntity getTheatre() { return theatre; }
        public String getScreenName() { return screenName; }
        public LocalDateTime getStartTime() { return startTime; }
    }

    @Entity
    @Table(name = "show_seats",
            uniqueConstraints = @UniqueConstraint(columnNames = {"showId", "seatLabel"}))
    public static class ShowSeatEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String showId;

        @Column(nullable = false)
        private String seatLabel;
        private int rowIdx;
        private int colIdx;

        @Enumerated(EnumType.STRING)
        private SeatType seatType;

        @Enumerated(EnumType.STRING)
        private ShowSeatStatus status;

        private String heldBy;
        private Instant heldUntil;

        @Version
        private long version;

        protected ShowSeatEntity() {}

        public ShowSeatEntity(String showId, String seatLabel, int rowIdx, int colIdx, SeatType seatType) {
            this.showId = showId;
            this.seatLabel = seatLabel;
            this.rowIdx = rowIdx;
            this.colIdx = colIdx;
            this.seatType = seatType;
            this.status = ShowSeatStatus.AVAILABLE;
        }

        public Long getId() { return id; }
        public String getShowId() { return showId; }
        public String getSeatLabel() { return seatLabel; }
        public int getRowIdx() { return rowIdx; }
        public int getColIdx() { return colIdx; }
        public SeatType getSeatType() { return seatType; }
        public ShowSeatStatus getStatus() { return status; }
        public String getHeldBy() { return heldBy; }
        public Instant getHeldUntil() { return heldUntil; }

        public boolean isAvailableNow() {
            if (status == ShowSeatStatus.AVAILABLE) return true;
            return status == ShowSeatStatus.HELD && heldUntil != null && Instant.now().isAfter(heldUntil);
        }

        public void hold(String userId, Instant until) {
            this.status = ShowSeatStatus.HELD;
            this.heldBy = userId;
            this.heldUntil = until;
        }

        public void book() {
            this.status = ShowSeatStatus.BOOKED;
            this.heldUntil = null;
        }

        public void release() {
            this.status = ShowSeatStatus.AVAILABLE;
            this.heldBy = null;
            this.heldUntil = null;
        }
    }

    @Entity
    @Table(name = "theatres")
    public static class TheatreEntity {

        @Id
        private String id;
        private String name;
        private String city;

        protected TheatreEntity() {}

        public TheatreEntity(String id, String name, String city) {
            this.id = id;
            this.name = name;
            this.city = city;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getCity() { return city; }
    }

    @Entity
    @Table(name = "users")
    public static class UserEntity {

        @Id
        private String id;
        private String name;
        private String email;
        private String phone;

        protected UserEntity() {}

        public UserEntity(String id, String name, String email, String phone) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.phone = phone;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
    }

    @Entity
    @Table(name = "bookings")
    public static class BookingEntity {

        @Id
        private String id;
        private String userId;
        private String showId;

        @Enumerated(EnumType.STRING)
        private BookingStatus status;

        private double totalAmount;

        @Column(name = "seats")
        private String seatLabels;

        private Instant createdAt;

        @Version
        private long version;

        protected BookingEntity() {}

        public BookingEntity(String id, String userId, String showId, BookingStatus status,
                             double totalAmount, String seatLabels, Instant createdAt) {
            this.id = id;
            this.userId = userId;
            this.showId = showId;
            this.status = status;
            this.totalAmount = totalAmount;
            this.seatLabels = seatLabels;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public String getUserId() { return userId; }
        public String getShowId() { return showId; }
        public BookingStatus getStatus() { return status; }
        public double getTotalAmount() { return totalAmount; }
        public String getSeatLabels() { return seatLabels; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "wallets")
    public static class WalletEntity {

        @Id
        private String userId;

        @Column(nullable = false)
        private BigDecimal balance;

        @Version
        private long version;

        protected WalletEntity() {}

        public WalletEntity(String userId, BigDecimal balance) {
            this.userId = userId;
            this.balance = balance;
        }

        public String getUserId() { return userId; }
        public BigDecimal getBalance() { return balance; }

        public boolean canAfford(BigDecimal amount) {
            return balance.compareTo(amount) >= 0;
        }

        public void debit(BigDecimal amount) {
            this.balance = this.balance.subtract(amount);
        }
    }
}
