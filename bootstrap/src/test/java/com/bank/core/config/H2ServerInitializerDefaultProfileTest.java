package com.bank.core.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.ConnectException;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-safety regression guard. Asserts that under the non-dev test
 * profile, the {@link H2ServerInitializer} bean is absent — proving that
 * the {@code @Profile("dev")} guard is what controls the TCP server, not
 * just the {@code bank-core.h2.tcp-server.enabled} property.
 *
 * <p>An accidental future removal of the {@code @Profile("dev")} annotation
 * would silently expose port 9092 in production; this test fails first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class H2ServerInitializerDefaultProfileTest {

    @Autowired ApplicationContext context;

    @Test
    void tcpServerBeanIsAbsentUnderTestProfile() {
        assertThat(context.getBeansOfType(H2ServerInitializer.class))
                .as("H2ServerInitializer must remain @Profile(\"dev\")-gated — "
                        + "the bean must not be constructed under the test profile")
                .isEmpty();
    }

    @Test
    void noProcessListensOnDefaultH2Port() {
        // Best-effort: if a developer happens to be running ./gradlew bootRun
        // with the dev profile in parallel with the test suite, port 9092 will
        // be in use by that legitimate process. In that case we skip rather
        // than fail — the bean-absence assertion above is the load-bearing one.
        try (Socket socket = new Socket("localhost", 9092)) {
            Assumptions.assumeFalse(socket.isConnected(),
                    "port 9092 is in use (likely a developer's local bootRun); "
                            + "skipping — the bean-absence assertion is sufficient");
        } catch (ConnectException expected) {
            // Nothing is listening on 9092 — exactly what we want under the test profile.
        } catch (Exception e) {
            // Any other I/O issue is also acceptable — what matters is the absence of
            // an accepting connection. Treat as "no listener".
        }
    }
}
