package com.estatevista.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.estatevista.audit.entity.AuditLog;
import com.estatevista.audit.repository.AuditLogRepository;
import com.estatevista.common.logging.CorrelationIdFilter;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Verifies that an audit-sink failure is non-silent and cannot change the primary outcome. */
class AuditEventServiceFailureTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditEventService service = new AuditEventService(
            auditLogRepository, userRepository, new PassThroughTransactionManager());

    @AfterEach
    void cleanUp() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void repositoryFailureIsLoggedAndDoesNotPropagate() {
        String requestId = "audit-failure-" + UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, requestId);
        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new DataIntegrityViolationException("audit sink unavailable"));

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AuditEventService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(() -> service.recordLoginSuccess(UUID.randomUUID())).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        verify(auditLogRepository).save(any(AuditLog.class));
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("Audit write failed")
                        .contains(AuditEventService.ACTION_LOGIN_SUCCESS)
                        .contains(requestId));
    }

    private static final class PassThroughTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return false;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No-op for this isolated writer test.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No-op for this isolated writer test.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No-op for this isolated writer test.
        }
    }
}
