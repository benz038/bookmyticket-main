package bookmyticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> clientRegistrations)
            throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/static/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/movies/**", "/api/shows/**").permitAll()
                .requestMatchers("/api/me/**", "/api/auth/**", "/dev-login", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/chat").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/shows/*/holds").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/shows/*/holds").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/shows/*/bookings").authenticated()
                .anyRequest().permitAll()
            )

            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(l -> l
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                .logoutSuccessUrl("/")
                .permitAll());

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(o -> o.defaultSuccessUrl("/", true));
        }

        return http.build();
    }
}
