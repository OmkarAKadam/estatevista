package com.estatevista.property.image;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.property.entity.PropertyImageDeletionOutbox;
import com.estatevista.property.repository.PropertyImageDeletionOutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes durable provider-deletion tasks after the metadata transaction has
 * committed. Provider I/O is never performed inside a database transaction.
 *
 * <p>Due tasks are claimed with PostgreSQL {@code FOR UPDATE SKIP LOCKED} under a
 * short lease. A claimed row that is not completed because the worker crashes is
 * reclaimed automatically when its lease expires. Repeating a provider delete is
 * safe: a confirmed absent asset is recorded as successful cleanup.</p>
 */
@Component
public class PropertyImageCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(PropertyImageCleanupWorker.class);

    private final PropertyImageDeletionOutboxRepository repository;
    private final ObjectProvider<ImageStorage> storageProvider;
    private final ImageCleanupProperties properties;
    private final AuditEventService audit;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;

    public PropertyImageCleanupWorker(
            PropertyImageDeletionOutboxRepository repository,
            ObjectProvider<ImageStorage> storageProvider,
            ImageCleanupProperties properties,
            AuditEventService audit,
            PlatformTransactionManager transactionManager,
            @Qualifier("imageCleanupTaskExecutor") TaskExecutor executor) {
        this.repository = repository;
        this.storageProvider = storageProvider;
        this.properties = properties;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ImageDeletionRequested event) {
        audit.recordImageCleanup(
                event.propertyId(), event.imageId(), event.actorId(), "TASK_CREATED");
        if (!properties.isEnabled()) {
            return;
        }
        try {
            // Dispatch only: provider I/O must not delay the 204 response. The
            // background pass uses the same SKIP LOCKED claim as the poller, so
            // concurrent dispatch and polling cannot process a task twice.
            executor.execute(() -> {
                try {
                    processDueTasks();
                } catch (RuntimeException failure) {
                    log.warn("After-commit image cleanup pass failed taskId={} error={}",
                            event.taskId(), failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            log.warn("After-commit image cleanup dispatch failed taskId={} error={}",
                    event.taskId(), failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${app.image-cleanup.poll-ms:5000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            processDueTasks();
        } catch (RuntimeException failure) {
            log.warn("Scheduled image cleanup poll failed error={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** Claims due tasks in one short transaction, then calls the provider outside it. */
    public int processDueTasks() {
        List<UUID> claimed = claimDueIds();
        for (UUID taskId : claimed) {
            processTask(taskId);
        }
        return claimed.size();
    }

    private void processTask(UUID taskId) {
        PropertyImageDeletionOutbox task = repository.findById(taskId).orElse(null);
        if (task == null || task.isFinished()) {
            return;
        }
        ImageStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            recordFailure(taskId, "PROVIDER_NOT_CONFIGURED");
            return;
        }
        try {
            storage.delete(task.getStorageProvider(), task.getStoragePublicId());
            recordCompleted(taskId, "SUCCESS");
        } catch (ProviderAssetNotFoundException alreadyDeleted) {
            recordCompleted(taskId, "ALREADY_MISSING");
        } catch (PermanentProviderException permanent) {
            log.warn("Permanent image cleanup failure taskId={} type={}",
                    taskId, permanent.getClass().getSimpleName());
            recordDeadLetter(taskId, permanent.getClass().getSimpleName(), "PERMANENT_FAILURE");
        } catch (RuntimeException failure) {
            log.warn("Provider image cleanup failed taskId={} type={}",
                    taskId, failure.getClass().getSimpleName());
            recordFailure(taskId, failure.getClass().getSimpleName());
        }
    }

    private List<UUID> claimDueIds() {
        return transactions.execute(status -> {
            List<UUID> ids = repository.findClaimableIds(Math.max(1, properties.getBatchSize()));
            Instant now = Instant.now();
            Instant leaseUntil = now.plusSeconds(Math.max(1, properties.getLeaseSeconds()));
            for (UUID id : ids) {
                repository.findById(id).ifPresent(task -> task.markProcessing(now, leaseUntil));
            }
            repository.flush();
            return ids;
        });
    }

    private void recordCompleted(UUID taskId, String outcome) {
        transactions.executeWithoutResult(status -> repository.findById(taskId)
                .ifPresent(task -> {
                    if (task.isFinished()) {
                        return;
                    }
                    task.markCompleted(Instant.now());
                    // Flush before auditing so a database failure cannot leave a
                    // success audit for a task whose completion did not commit.
                    repository.flush();
                    audit.recordImageCleanup(
                            task.getPropertyId(), task.getImageId(), task.getActorId(), outcome);
                }));
    }

    private void recordFailure(UUID taskId, String reason) {
        transactions.executeWithoutResult(status -> repository.findById(taskId)
                .ifPresent(task -> {
                    if (task.isFinished()) {
                        return;
                    }
                    Instant now = Instant.now();
                    if (task.getAttempts() >= Math.max(1, properties.getMaxAttempts())) {
                        recordDeadLetterInCurrentTransaction(task, now, reason, "DEAD_LETTER");
                        return;
                    }
                    int exponent = Math.max(0, task.getAttempts() - 1);
                    long multiplier = 1L << Math.min(exponent, 20);
                    long initial = Math.max(0, properties.getBackoffMs());
                    long maximum = Math.max(initial, properties.getMaxBackoffMs());
                    long delay = initial > maximum / multiplier ? maximum : initial * multiplier;
                    task.markFailed(now, now.plusMillis(delay), truncate(reason));
                    audit.recordImageCleanup(
                            task.getPropertyId(), task.getImageId(), task.getActorId(),
                            "RETRY_SCHEDULED");
                }));
    }

    private void recordDeadLetter(UUID taskId, String reason, String outcome) {
        transactions.executeWithoutResult(status -> repository.findById(taskId)
                .ifPresent(task -> {
                    if (task.isFinished()) {
                        return;
                    }
                    recordDeadLetterInCurrentTransaction(
                            task, Instant.now(), reason, outcome);
                }));
    }

    private void recordDeadLetterInCurrentTransaction(
            PropertyImageDeletionOutbox task, Instant now, String reason, String outcome) {
        task.markDeadLetter(now, truncate(reason));
        audit.recordImageCleanup(
                task.getPropertyId(), task.getImageId(), task.getActorId(), outcome);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "PROVIDER_FAILURE";
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
