package org.urizo.axmodulestudio.backend;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AxModuleStudioBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AxModuleStudioBackendApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
