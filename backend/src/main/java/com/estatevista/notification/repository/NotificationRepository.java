package com.estatevista.notification.repository;

import java.util.UUID;

import com.estatevista.notification.entity.Notification;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for user notifications.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
