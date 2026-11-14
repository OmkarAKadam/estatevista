-- Stage 2.3 business schema: user notifications.
-- Source: DATABASE_DESIGN.md §12.1, §15.8, §19 (V12).
--
-- resource_type/resource_id are an intentional polymorphic reference and have no
-- conventional foreign key (§12.1). Notification type values are described as
-- potential categories, so no CHECK constraint is applied.

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    resource_type VARCHAR(50),
    resource_id UUID,
    is_read BOOLEAN NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id)
        REFERENCES users (id) ON DELETE CASCADE
);
