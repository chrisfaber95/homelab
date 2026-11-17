package dev.homelab.inbox.read;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    /** The clock the read routes use; tests build the services with a fixed one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
