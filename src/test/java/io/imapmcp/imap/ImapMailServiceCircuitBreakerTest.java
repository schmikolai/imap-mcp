package io.imapmcp.imap;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the resilience4j wiring in {@link ImapMailService#withStore}
 * directly (via public tool methods, since {@code withStore} is private):
 * repeated connection failures ({@link ImapMailService.ImapOperationException}, the type
 * configured as {@code record-exceptions} in application.yml) must open the
 * breaker; a client-input error ({@link NoSuchElementException}, e.g. a bad
 * folder name) must never count toward it.
 */
class ImapMailServiceCircuitBreakerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Test
    void repeatedConnectionFailuresOpenTheBreaker() throws Exception {
        ImapConnectionPool pool = mock(ImapConnectionPool.class);
        when(pool.borrow(any())).thenThrow(new Exception("connection refused"));
        ImapMailService service = new ImapMailService(pool, mock(MimeContentExtractor.class), circuitBreakerRegistry());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.listFolders(TENANT_ID, ACCOUNT_ID))
                    .isInstanceOf(ImapMailService.ImapOperationException.class);
        }
        verify(pool, times(5)).borrow(any());

        // The breaker is now OPEN (5 calls, 100% failure, minimumNumberOfCalls
        // reached) — the 6th call must fail fast without touching the pool.
        assertThatThrownBy(() -> service.listFolders(TENANT_ID, ACCOUNT_ID))
                .isInstanceOf(ImapMailService.ImapOperationException.class)
                .hasMessageContaining("temporarily unavailable");
        verify(pool, times(5)).borrow(any());
    }

    @Test
    void clientInputErrorsNeverTripTheBreaker() throws Exception {
        ImapConnectionPool pool = mock(ImapConnectionPool.class);
        Store store = mock(Store.class);
        Folder missingFolder = mock(Folder.class);
        when(missingFolder.exists()).thenReturn(false);
        when(store.getFolder("INBOX")).thenReturn(missingFolder);
        when(pool.borrow(any())).thenReturn(store);
        ImapMailService service = new ImapMailService(pool, mock(MimeContentExtractor.class), circuitBreakerRegistry());

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> service.readMessage(TENANT_ID, ACCOUNT_ID, "INBOX", 1L))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("No such folder");
        }

        // Never wrapped as "temporarily unavailable" and the pool is still
        // consulted on every call — the breaker never opened.
        verify(pool, times(6)).borrow(any());
    }

    private CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(ImapMailService.ImapOperationException.class::isInstance)
                .build();
        return CircuitBreakerRegistry.of(config);
    }
}
