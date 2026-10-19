package com.shopsage.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the inventory-service. Shared web error handling and observability defaults arrive through
 * shopsage-common's auto-configuration, so component scanning stays limited to {@code com.shopsage.inventory}.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
