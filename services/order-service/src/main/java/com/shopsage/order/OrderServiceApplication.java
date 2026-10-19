package com.shopsage.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the order-service. Shared web error handling and observability defaults arrive through
 * shopsage-common's auto-configuration, so component scanning stays limited to {@code com.shopsage.order}.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
