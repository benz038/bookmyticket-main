package bookmyticket.config;

import bookmyticket.service.BookingCore.SeatLockProvider;
import bookmyticket.service.Payments.EmailNotifier;
import bookmyticket.service.Payments.SmsNotifier;
import bookmyticket.service.Payments.PaymentStrategyFactory;
import bookmyticket.service.Payments.PricingStrategy;
import bookmyticket.service.Payments.SeatTypePricingStrategy;
import bookmyticket.service.BookingCore.BookingEngine;
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
}
