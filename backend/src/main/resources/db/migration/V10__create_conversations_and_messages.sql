-- Stage 2.3 business schema: property-specific conversations and messages.
-- Source: DATABASE_DESIGN.md §11.1, §11.2, §15.6, §15.7, §17, §19 (V10).
--
-- Conversation status values and message types are not constrained here: the
-- design lists proposed/initial values and defers the chat lifecycle (§11, §21).

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL,
    property_id UUID NOT NULL,
    assigned_agent_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    CONSTRAINT fk_conversations_buyer FOREIGN KEY (buyer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_assigned_agent FOREIGN KEY (assigned_agent_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_conversations_buyer_property UNIQUE (buyer_id, property_id)
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
