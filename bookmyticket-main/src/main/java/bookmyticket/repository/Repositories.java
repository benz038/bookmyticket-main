package bookmyticket.repository;

import bookmyticket.model.Models.Booking;
import bookmyticket.model.Models.BookingStatus;
import bookmyticket.model.Models.Movie;
import bookmyticket.model.Models.Screen;
import bookmyticket.model.Models.Seat;
import bookmyticket.model.Models.Show;
import bookmyticket.model.Models.ShowSeatStatus;
import bookmyticket.model.Models.Theatre;
import bookmyticket.model.Models.User;
import bookmyticket.repository.Entities.BookingEntity;
import bookmyticket.repository.Entities.MovieEntity;
import bookmyticket.repository.Entities.ShowEntity;
import bookmyticket.repository.Entities.ShowSeatEntity;
import bookmyticket.repository.Entities.TheatreEntity;
import bookmyticket.repository.Entities.UserEntity;
import bookmyticket.repository.Entities.WalletEntity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

public final class Repositories {

    private Repositories() {
    }

    public static interface MovieJpaRepository extends JpaRepository<MovieEntity, String> {
    }

    public static interface ShowJpaRepository extends JpaRepository<ShowEntity, String> {
        List<ShowEntity> findByMovie_Id(String movieId);
    }

    public static interface ShowSeatJpaRepository extends JpaRepository<ShowSeatEntity, Long> {
        List<ShowSeatEntity> findByShowId(String showId);
        Optional<ShowSeatEntity> findByShowIdAndSeatLabel(String showId, String seatLabel);
        List<ShowSeatEntity> findByStatusAndHeldUntilBefore(ShowSeatStatus status, Instant before);
    }

    public static interface TheatreJpaRepository extends JpaRepository<TheatreEntity, String> {
    }

    public static interface UserJpaRepository extends JpaRepository<UserEntity, String> {
        Optional<UserEntity> findByEmail(String email);
    }

    public static interface BookingJpaRepository extends JpaRepository<BookingEntity, String> {
        List<BookingEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    }

    public static interface WalletJpaRepository extends JpaRepository<WalletEntity, String> {
    }

    public static record BookingRecord(
            String id,
            String showId,
            String seatLabels,
            double amount,
            BookingStatus status,
            Instant createdAt
    ) {}

    @Repository
    public static class MovieRepository {

        private final MovieJpaRepository jpa;

        public MovieRepository(MovieJpaRepository jpa) {
            this.jpa = jpa;
        }

        public List<Movie> findAll() {
            return jpa.findAll().stream().map(MovieRepository::toDomain).toList();
        }

        public Optional<Movie> findById(String id) {
            return jpa.findById(id).map(MovieRepository::toDomain);
        }

        static Movie toDomain(MovieEntity e) {
            return new Movie(e.getId(), e.getName(), e.getLanguage(), e.getGenre(),
                    e.getDurationMins(), e.getCertificate(), e.getRating(), e.getPosterUrl());
        }
    }

    @Repository
    public static class ShowRepository {

        private final ShowJpaRepository showJpa;
        private final ShowSeatJpaRepository showSeatJpa;

        public ShowRepository(ShowJpaRepository showJpa, ShowSeatJpaRepository showSeatJpa) {
            this.showJpa = showJpa;
            this.showSeatJpa = showSeatJpa;
        }

        public Optional<Show> findById(String showId) {
            return showJpa.findById(showId).map(this::toDomain);
        }

        public List<Show> findAll() {
            return showJpa.findAll().stream().map(this::toDomain).toList();
        }

        public List<Show> findByMovie(String movieId) {
            return showJpa.findByMovie_Id(movieId).stream().map(this::toDomain).toList();
        }

        public Optional<Seat> findSeat(Show show, String seatId) {
            return show.getScreen().getSeats().stream()
                    .filter(s -> s.getId().equals(seatId))
                    .findFirst();
        }

        private Show toDomain(ShowEntity e) {
            List<Seat> seats = showSeatJpa.findByShowId(e.getId()).stream()
                    .sorted(Comparator.comparingInt(ShowSeatEntity::getRowIdx)
                            .thenComparingInt(ShowSeatEntity::getColIdx))
                    .map(ss -> new Seat(ss.getSeatLabel(), ss.getRowIdx(), ss.getColIdx(), ss.getSeatType()))
                    .toList();
            Screen screen = new Screen("scr-" + e.getId(), e.getScreenName(), seats);
            Movie movie = MovieRepository.toDomain(e.getMovie());
            Theatre theatre = TheatreRepository.toDomain(e.getTheatre());
            return new Show(e.getId(), movie, theatre, screen, e.getStartTime());
        }
    }

    @Repository
    public static class TheatreRepository {

        private final TheatreJpaRepository jpa;

        public TheatreRepository(TheatreJpaRepository jpa) {
            this.jpa = jpa;
        }

        public List<Theatre> findAll() {
            return jpa.findAll().stream().map(TheatreRepository::toDomain).toList();
        }

        public Optional<Theatre> findById(String id) {
            return jpa.findById(id).map(TheatreRepository::toDomain);
        }

        static Theatre toDomain(TheatreEntity e) {
            return new Theatre(e.getId(), e.getName(), e.getCity(), List.of());
        }
    }

    @Repository
    public static class UserRepository {

        private final UserJpaRepository jpa;

        public UserRepository(UserJpaRepository jpa) {
            this.jpa = jpa;
        }

        public Optional<User> findById(String id) {
            return jpa.findById(id).map(UserRepository::toDomain);
        }

        public Optional<User> findByEmail(String email) {
            return jpa.findByEmail(email).map(UserRepository::toDomain);
        }

        public User save(User user) {
            jpa.save(new UserEntity(user.getId(), user.getName(), user.getEmail(), user.getPhone()));
            return user;
        }

        static User toDomain(UserEntity e) {
            return new User(e.getId(), e.getName(), e.getEmail(), e.getPhone());
        }
    }

    @Repository
    public static class BookingRepository {

        private final BookingJpaRepository jpa;

        public BookingRepository(BookingJpaRepository jpa) {
            this.jpa = jpa;
        }

        public Booking save(Booking booking) {
            String seatLabels = booking.getSeats().stream().map(Seat::getId).collect(Collectors.joining(","));
            jpa.save(new BookingEntity(
                    booking.getId(),
                    booking.getUser().getId(),
                    booking.getShow().getId(),
                    booking.getStatus(),
                    booking.getAmount(),
                    seatLabels,
                    Instant.now()));
            return booking;
        }

        public List<BookingRecord> findByUser(String userId) {
            return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .map(BookingRepository::toRecord)
                    .toList();
        }

        static BookingRecord toRecord(BookingEntity e) {
            return new BookingRecord(e.getId(), e.getShowId(), e.getSeatLabels(),
                    e.getTotalAmount(), e.getStatus(), e.getCreatedAt());
        }
    }
}
