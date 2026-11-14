-- Stage 2.3 business schema: indexes from the approved indexing strategy.
-- Source: DATABASE_DESIGN.md §15, §19 (V14).
--
-- Only indexes explicitly listed in §15 are created. Unique constraints and the
-- partial primary-image index are defined alongside their tables.

-- §15.1 Users
CREATE INDEX idx_users_role_id ON users (role_id);
CREATE INDEX idx_users_account_status ON users (account_status);

-- §15.2 Properties
CREATE INDEX idx_properties_status ON properties (status);
CREATE INDEX idx_properties_category_id ON properties (category_id);
CREATE INDEX idx_properties_subtype_id ON properties (subtype_id);
CREATE INDEX idx_properties_listing_type ON properties (listing_type);
CREATE INDEX idx_properties_price ON properties (price);
CREATE INDEX idx_properties_city ON properties (city);
CREATE INDEX idx_properties_state ON properties (state);
CREATE INDEX idx_properties_primary_agent_id ON properties (primary_agent_id);
CREATE INDEX idx_properties_created_at ON properties (created_at);

-- §15.3 Property images
CREATE INDEX idx_property_images_property_sort ON property_images (property_id, sort_order);

-- §15.4 Favorites
CREATE INDEX idx_favorites_buyer_created ON favorites (buyer_id, created_at);

-- §15.5 Inquiries
CREATE INDEX idx_inquiries_buyer_created ON inquiries (buyer_id, created_at);
CREATE INDEX idx_inquiries_agent_status ON inquiries (assigned_agent_id, status);
CREATE INDEX idx_inquiries_property_created ON inquiries (property_id, created_at);

-- §15.6 Conversations
CREATE INDEX idx_conversations_buyer_updated ON conversations (buyer_id, updated_at);
CREATE INDEX idx_conversations_agent_updated ON conversations (assigned_agent_id, updated_at);

-- §15.7 Messages
CREATE INDEX idx_messages_conversation_sent ON messages (conversation_id, sent_at);
CREATE INDEX idx_messages_sender_sent ON messages (sender_id, sent_at);

-- §15.8 Notifications
CREATE INDEX idx_notifications_recipient_read_created
    ON notifications (recipient_id, is_read, created_at);

-- §15.9 Audit logs
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs (actor_id, created_at);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
