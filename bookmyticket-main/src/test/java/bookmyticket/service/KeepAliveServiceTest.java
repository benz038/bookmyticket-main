package bookmyticket.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeepAliveServiceTest {

    @Test
    void keepAlivePingsLocalHealthEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity("https://bookmyticket-a4jc.onrender.com/api/health", String.class))
                .thenReturn(ResponseEntity.ok("ok"));

        KeepAliveService service = new KeepAliveService(restTemplate, Clock.systemUTC());

        service.keepAlive();

        verify(restTemplate).getForEntity("https://bookmyticket-a4jc.onrender.com/api/health", String.class);
    }
}
