package com.minwonhaeso.esc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.cache.annotation.EnableCaching;

@EnableTransactionManagement
@EnableJpaAuditing
@EnableCaching
@SpringBootApplication
public class EscServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EscServerApplication.class, args);
    }
}
