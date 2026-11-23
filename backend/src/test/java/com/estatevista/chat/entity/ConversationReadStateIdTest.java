package com.estatevista.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ConversationReadStateIdTest {

    private final UUID conversationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void isEqualToAnotherInstanceWithTheSameValues() {
        ConversationReadStateId id = new ConversationReadStateId(conversationId, userId);
        ConversationReadStateId same = new ConversationReadStateId(conversationId, userId);

        assertThat(id).isEqualTo(same).hasSameHashCodeAs(same);
    }

    @Test
    void differsWhenEitherComponentDiffers() {
        ConversationReadStateId id = new ConversationReadStateId(conversationId, userId);

        assertThat(id)
                .isNotEqualTo(new ConversationReadStateId(UUID.randomUUID(), userId))
                .isNotEqualTo(new ConversationReadStateId(conversationId, UUID.randomUUID()));
    }

    @Test
    void isOnlyEqualToItselfAndTheSameType() {
        ConversationReadStateId id = new ConversationReadStateId(conversationId, userId);

        assertThat(id).isEqualTo(id).isNotEqualTo(null).isNotEqualTo("not-an-id");
    }

    @Test
    void supportsTheNoArgumentContractRequiredByJpa() {
        ConversationReadStateId id = new ConversationReadStateId();
        id.setConversationId(conversationId);
        id.setUserId(userId);

        assertThat(id.getConversationId()).isEqualTo(conversationId);
        assertThat(id.getUserId()).isEqualTo(userId);
        assertThat(id).isEqualTo(new ConversationReadStateId(conversationId, userId));
    }
}
