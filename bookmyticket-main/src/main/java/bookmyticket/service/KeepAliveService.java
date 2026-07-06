package bookmyticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class KeepAliveService {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveService.class);

    private final RestTemplate restTemplate;
    private final String targetUrl;
    private final Duration interval;
    private final Clock clock;
    private Instant lastPing = Instant.EPOCH;

    public KeepAliveService(RestTemplate restTemplate,
                            Clock clock) {
        this.restTemplate = restTemplate;
        this.clock = clock;
        this.targetUrl = "https://bookmyticket-a4jc.onrender.com/api/health";
        this.interval = Duration.ofMinutes(5);
    }

    @Scheduled(fixedDelay = 600000)
    public void keepAlive() {
        Instant now = clock.instant();
        if (!lastPing.plus(interval).isBefore(now)) {
            return;
        }

        lastPing = now;
        try {
            restTemplate.getForEntity(targetUrl, String.class);
            log.debug("Keep-alive ping succeeded for {}", targetUrl);
        } catch (Exception ex) {
            log.warn("Keep-alive ping failed for {}: {}", targetUrl, ex.getMessage());
        }
    }
}
