-- Stage 2.3 business schema: per-participant conversation read state.
-- Source: DATABASE_DESIGN.md §11.3, §14, §23.1(B), §19 (V11).
--
-- Participant membership and same-conversation message ownership are explicit
-- backend service-layer rules (§23.1 B); no trigger is introduced here.

CREATE TABLE conversation_read_states (
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    last_read_message_id UUID,
    last_read_at TIMESTAMPTZ,
    CONSTRAINT pk_conversation_read_states PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_conversation_read_states_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_read_states_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_read_states_message FOREIGN KEY (last_read_message_id)
        REFERENCES messages (id) ON DELETE SET NULL
);
