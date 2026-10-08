package dev.focusduo;

import org.testcontainers.containers.PostgreSQLContainer;

/** Default CI path requires Docker; the explicit alternative still uses real PostgreSQL. */
final class TestDatabase {
    static final String URL;
    static final String USER;
    static final String PASSWORD;
    static final PostgreSQLContainer<?> CONTAINER;
    static {
        String external = System.getenv("FOCUSDUO_TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            CONTAINER = null;
            URL = external;
            USER = System.getenv().getOrDefault("FOCUSDUO_TEST_DB_USERNAME", "focusduo");
            PASSWORD = System.getenv().getOrDefault("FOCUSDUO_TEST_DB_PASSWORD", "");
        } else {
            CONTAINER = new PostgreSQLContainer<>("postgres:17.11-bookworm")
                .withDatabaseName("focusduo_test").withUsername("focusduo").withPassword("test-only-password");
            CONTAINER.start();
            URL = CONTAINER.getJdbcUrl(); USER = CONTAINER.getUsername(); PASSWORD = CONTAINER.getPassword();
        }
    }
    private TestDatabase() {}
}
