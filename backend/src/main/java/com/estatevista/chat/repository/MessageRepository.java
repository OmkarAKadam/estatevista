package com.estatevista.chat.repository;

import java.util.UUID;

import com.estatevista.chat.entity.Message;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for conversation messages.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {
}
