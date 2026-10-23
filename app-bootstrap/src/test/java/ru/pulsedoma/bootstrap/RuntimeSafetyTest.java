package ru.pulsedoma.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeSafetyTest {
    @Test
    void requiresPrivateConfigurationOutsideDemo() {
        var env = new MockEnvironment();
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeSafety(env, "local-dev-secret", "minioadmin", "minioadmin"));
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeSafety(env, "a".repeat(32), "admin", "minioadmin"));
        assertDoesNotThrow(
                () -> new RuntimeSafety(env, "a".repeat(48), "storage-user", "b".repeat(32)));
    }

    private MockEnvironment demoEnvironment() {
        var env = new MockEnvironment();
        env.setActiveProfiles("demo");
        return env;
    }

    @Test
    void isolatedBrowserDemoNeedsNoExternalCredentials() {
        assertDoesNotThrow(() -> new RuntimeSafety(demoEnvironment(), "", "", ""));
    }
}
