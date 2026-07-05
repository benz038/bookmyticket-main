package bookmyticket.config;

import bookmyticket.repository.Repositories.ShowSeatJpaRepository;
import bookmyticket.service.BookingCore;
import bookmyticket.service.BookingCore.SeatLockProvider;
import bookmyticket.service.Payments.EmailNotifier;
import bookmyticket.service.Payments.SmsNotifier;
import bookmyticket.service.Payments.PaymentStrategyFactory;
import bookmyticket.service.Payments.PricingStrategy;
import bookmyticket.service.Payments.SeatTypePricingStrategy;
import bookmyticket.service.BookingCore.BookingEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfig {

    @Bean
    PricingStrategy pricingStrategy() {
        return new SeatTypePricingStrategy();
    }

    @Bean
    BookingEngine bookingEngine(SeatLockProvider lockProvider,
                                PricingStrategy pricingStrategy,
                                PaymentStrategyFactory paymentStrategyFactory) {
        BookingEngine engine = new BookingEngine(lockProvider, pricingStrategy, paymentStrategyFactory);
        engine.registerObserver(new EmailNotifier());
        engine.registerObserver(new SmsNotifier());
        return engine;
    }

    @Bean
    SeatLockProvider seatLockProvider(ShowSeatJpaRepository showSeatJpaRepository,
                                      @Value("${bookmyticket.seat-hold-seconds:300}") int holdSeconds) {
        return new BookingCore.DbSeatLockProvider(showSeatJpaRepository, holdSeconds);
    }

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
        loggingFilter.setIncludeClientInfo(true);
        loggingFilter.setIncludeQueryString(true);
        loggingFilter.setIncludePayload(true);
        loggingFilter.setMaxPayloadLength(10000);
        loggingFilter.setIncludeHeaders(true);
        loggingFilter.setBeforeMessagePrefix("[REQUEST] ");
        loggingFilter.setAfterMessagePrefix("[REQUEST] ");
        return loggingFilter;
    }
}
