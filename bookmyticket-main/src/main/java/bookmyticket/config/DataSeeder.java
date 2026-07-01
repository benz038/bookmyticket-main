package bookmyticket.config;

import bookmyticket.model.Models.SeatType;
import bookmyticket.repository.Entities.MovieEntity;
import bookmyticket.repository.Entities.ShowEntity;
import bookmyticket.repository.Entities.ShowSeatEntity;
import bookmyticket.repository.Entities.TheatreEntity;
import bookmyticket.repository.Repositories.MovieJpaRepository;
import bookmyticket.repository.Repositories.ShowJpaRepository;
import bookmyticket.repository.Repositories.ShowSeatJpaRepository;
import bookmyticket.repository.Repositories.TheatreJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final int CITIES_PER_MOVIE = 3;
    private static final int[] SHOW_HOURS = {10, 13, 16, 19, 22};

    private final MovieJpaRepository movieJpa;
    private final TheatreJpaRepository theatreJpa;
    private final ShowJpaRepository showJpa;
    private final ShowSeatJpaRepository showSeatJpa;

    public DataSeeder(MovieJpaRepository movieJpa, TheatreJpaRepository theatreJpa,
                      ShowJpaRepository showJpa, ShowSeatJpaRepository showSeatJpa) {
        this.movieJpa = movieJpa;
        this.theatreJpa = theatreJpa;
        this.showJpa = showJpa;
        this.showSeatJpa = showSeatJpa;
    }

    @Override
    public void run(String... args) {
        ensureMovies();
        ensureTheatres();
        ensureShows();
    }

    private List<MovieEntity> catalog() {
        String p = "/posters/";
        return List.of(
            new MovieEntity("m1",  "Animal",             "Hindi",   "Action/Drama",       201, "A",  8.2, p + "m1.jpg"),
            new MovieEntity("m2",  "Jawan",              "Hindi",   "Action/Thriller",    169, "UA", 7.8, p + "m2.jpg"),
            new MovieEntity("m3",  "Pathaan",            "Hindi",   "Action/Spy",         146, "UA", 7.4, p + "m3.jpg"),
            new MovieEntity("m4",  "Dunki",              "Hindi",   "Comedy/Drama",       161, "UA", 7.0, p + "m4.jpg"),
            new MovieEntity("m5",  "Stree 2",            "Hindi",   "Horror/Comedy",      149, "UA", 8.0, p + "m5.jpg"),
            new MovieEntity("m6",  "Kalki 2898 AD",      "Telugu",  "Sci-Fi/Action",      181, "UA", 7.6, p + "m6.jpg"),
            new MovieEntity("m7",  "12th Fail",          "Hindi",   "Biography/Drama",    147, "U",  9.0, p + "m7.jpg"),
            new MovieEntity("m8",  "Fighter",            "Hindi",   "Action/Drama",       166, "UA", 7.2, p + "m8.jpg"),
            new MovieEntity("m9",  "RRR",                "Telugu",  "Action/Drama",       187, "UA", 8.8, p + "m9.jpg"),
            new MovieEntity("m10", "Pushpa 2: The Rule", "Telugu",  "Action/Drama",       200, "UA", 7.9, p + "m10.jpg"),
            new MovieEntity("m11", "Oppenheimer",        "English", "Biography/Thriller", 180, "A",  8.9, p + "m11.jpg"),
            new MovieEntity("m12", "Inception",          "English", "Sci-Fi/Thriller",    148, "UA", 8.8, p + "m12.jpg"));
    }

    private void ensureMovies() {
        List<MovieEntity> desired = catalog();
        Map<String, MovieEntity> existing = movieJpa.findAll().stream()
                .collect(Collectors.toMap(MovieEntity::getId, m -> m));
        boolean needsSync = desired.stream().anyMatch(d -> {
            MovieEntity e = existing.get(d.getId());
            return e == null || !Objects.equals(e.getPosterUrl(), d.getPosterUrl());
        });
        if (needsSync) {
            movieJpa.saveAll(desired);
            log.info("Seeded/updated {} movies (with posters)", desired.size());
        }
    }

    private List<TheatreEntity> theatres() {
        return List.of(
            new TheatreEntity("t1",  "PVR Phoenix Lower Parel", "Mumbai"),
            new TheatreEntity("t2",  "INOX Nariman Point",      "Mumbai"),
            new TheatreEntity("t3",  "Cinepolis Andheri",       "Mumbai"),
            new TheatreEntity("t4",  "PVR Select Citywalk",     "Delhi"),
            new TheatreEntity("t5",  "INOX Nehru Place",        "Delhi"),
            new TheatreEntity("t6",  "PVR Orion Mall",          "Bengaluru"),
            new TheatreEntity("t7",  "INOX Garuda Mall",        "Bengaluru"),
            new TheatreEntity("t8",  "AMB Cinemas Gachibowli",  "Hyderabad"),
            new TheatreEntity("t9",  "PVR Inorbit Madhapur",    "Hyderabad"),
            new TheatreEntity("t10", "PVR Pavillion FC Road",   "Pune"),
            new TheatreEntity("t11", "INOX Bund Garden",        "Pune"),
            new TheatreEntity("t12", "PVR Grand Galada",        "Chennai"),
            new TheatreEntity("t13", "INOX Citi Centre",        "Chennai"));
    }

    private void ensureTheatres() {
        Set<String> existingIds = theatreJpa.findAll().stream()
                .map(TheatreEntity::getId).collect(Collectors.toSet());
        List<TheatreEntity> missing = theatres().stream()
                .filter(t -> !existingIds.contains(t.getId())).toList();
        if (!missing.isEmpty()) {
            theatreJpa.saveAll(missing);
            log.info("Added {} theatres (new cities)", missing.size());
        }
    }

    private void ensureShows() {
        List<MovieEntity> movies = catalog();
        Map<String, TheatreEntity> theatreById = theatres().stream()
                .collect(Collectors.toMap(TheatreEntity::getId, t -> t));

        Map<String, String[]> cityTheatres = new LinkedHashMap<>();
        cityTheatres.put("Mumbai",    new String[]{"t1", "t2"});
        cityTheatres.put("Delhi",     new String[]{"t4", "t5"});
        cityTheatres.put("Bengaluru", new String[]{"t6", "t7"});
        cityTheatres.put("Hyderabad", new String[]{"t8", "t9"});
        cityTheatres.put("Pune",      new String[]{"t10", "t11"});
        cityTheatres.put("Chennai",   new String[]{"t12", "t13"});
        List<String> cities = new ArrayList<>(cityTheatres.keySet());

        Set<String> showIds = showJpa.findAll().stream()
                .map(ShowEntity::getId).collect(Collectors.toCollection(java.util.HashSet::new));

        int created = 0;
        for (int mi = 0; mi < movies.size(); mi++) {
            MovieEntity movie = movies.get(mi);
            for (int off = 0; off < CITIES_PER_MOVIE; off++) {
                String city = cities.get((mi + off) % cities.size());
                String theatreId = cityTheatres.get(city)[mi % 2];
                TheatreEntity theatre = theatreById.get(theatreId);
                int h1 = SHOW_HOURS[(mi + off) % SHOW_HOURS.length];
                int h2 = SHOW_HOURS[(mi + off + 2) % SHOW_HOURS.length];
                for (int h : new int[]{h1, h2}) {
                    String id = movie.getId() + "-" + theatreId + "-" + h;
                    if (!showIds.add(id)) continue;
                    LocalDateTime start = LocalDate.now().atTime(h, 0);
                    showJpa.save(new ShowEntity(id, movie, theatre, "Audi 1", start));
                    showSeatJpa.saveAll(buildSeats(id));
                    created++;
                }
            }
        }
        if (created > 0) {
            log.info("Created {} new shows (now movies={}, theatres={}, shows={}, showSeats={})",
                    created, movieJpa.count(), theatreJpa.count(), showJpa.count(), showSeatJpa.count());
        }
    }

    private List<ShowSeatEntity> buildSeats(String showId) {
        List<ShowSeatEntity> seats = new ArrayList<>();
        String[] rows = {"A", "B", "C"};
        SeatType[] rowTypes = {SeatType.REGULAR, SeatType.PREMIUM, SeatType.RECLINER};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 1; c <= 8; c++) {
                seats.add(new ShowSeatEntity(showId, rows[r] + c, r, c, rowTypes[r]));
            }
        }
        return seats;
    }
}
