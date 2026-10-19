package com.shopsage.common.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Boot application so slice/integration tests in this library module have a
 * {@code @SpringBootConfiguration} to bootstrap from (a library has no main class of its own).
 *
 * <p>Lives in its own package on purpose: its component scan must not reach {@code com.shopsage.common.web}, so
 * the beans under test arrive through the auto-configuration imports file exactly as they do in a service.
 */
@SpringBootApplication
public class TestApplication {
}
