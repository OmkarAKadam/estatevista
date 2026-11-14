package com.estatevista.inquiry.repository;

import java.util.UUID;

import com.estatevista.inquiry.entity.Inquiry;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for property inquiries.
 */
public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {
}
