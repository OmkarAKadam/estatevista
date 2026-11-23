package com.estatevista.property.image;

import java.util.UUID;

/** Fired after the metadata/outbox transaction commits. */
public record ImageDeletionRequested(
        UUID taskId, UUID propertyId, UUID imageId, UUID actorId) {
}
