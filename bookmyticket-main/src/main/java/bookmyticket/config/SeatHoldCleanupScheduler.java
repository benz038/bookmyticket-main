package bookmyticket.config;

import bookmyticket.model.Models.ShowSeatStatus;
import bookmyticket.repository.Entities.ShowSeatEntity;
import bookmyticket.repository.Repositories.ShowSeatJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class SeatHoldCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldCleanupScheduler.class);

    private final ShowSeatJpaRepository showSeatJpaRepository;

    public SeatHoldCleanupScheduler(ShowSeatJpaRepository showSeatJpaRepository) {
        this.showSeatJpaRepository = showSeatJpaRepository;
    }

    @Scheduled(fixedRateString = "PT1M", initialDelayString = "PT10S")
    @Transactional
    public void cleanupExpiredHolds() {
        Instant now = Instant.now();
        List<ShowSeatEntity> expiredSeats = showSeatJpaRepository.findByStatusAndHeldUntilBefore(ShowSeatStatus.HELD, now);
        if (expiredSeats.isEmpty()) {
            return;
        }
        expiredSeats.forEach(ShowSeatEntity::release);
        showSeatJpaRepository.saveAll(expiredSeats);
        log.info("Released {} expired seat holds", expiredSeats.size());
    }
}
