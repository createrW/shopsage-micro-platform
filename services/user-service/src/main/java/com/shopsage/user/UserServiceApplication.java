package com.shopsage.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the user-service. Shared web error handling and observability defaults arrive through
 * shopsage-common's auto-configuration, so component scanning stays limited to {@code com.shopsage.user}.
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
