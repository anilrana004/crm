package com.securetravels.crm;

import com.securetravels.crm.common.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class SecureTravelsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureTravelsApplication.class, args);
    }
}