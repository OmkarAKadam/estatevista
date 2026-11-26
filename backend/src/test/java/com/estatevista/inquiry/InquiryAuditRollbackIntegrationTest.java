package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.estatevista.audit.AuditEventService;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.inquiry.service.InquiryService;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies that a mandatory audit failure rolls back the inquiry mutation. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquiryAuditRollbackIntegrationTest {

    @Autowired private InquiryService service;
    @Autowired private InquiryRepository inquiries;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private AuditEventService audit;

    private final List<UUID> createdInquiries = new ArrayList<>();
    private final List<UUID> createdProperties = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!createdInquiries.isEmpty()) {
            jdbc.update("delete from inquiries where id in (" + placeholders(createdInquiries.size()) + ")",
                    createdInquiries.toArray());
        }
        if (!createdProperties.isEmpty()) {
            jdbc.update("delete from properties where id in (" + placeholders(createdProperties.size()) + ")",
                    createdProperties.toArray());
        }
        if (!createdUsers.isEmpty()) {
            jdbc.update("delete from users where id in (" + placeholders(createdUsers.size()) + ")",
                    createdUsers.toArray());
        }
    }

    @Test
    void auditPersistenceFailureRollsBackStatusChange() {
        User buyer = user("BUYER", "audit-rollback-buyer");
        User agent = user("AGENT", "audit-rollback-agent");
        User admin = user("ADMIN", "audit-rollback-admin");
        Property property = property(agent);
        UUID id = UUID.randomUUID();
        assertThat(jdbc.update(
                "insert into inquiries "
                        + "(id, buyer_id, property_id, assigned_agent_id, subject, message, "
                        + "status, created_at, updated_at, resolved_at) "
                        + "values (?, ?, ?, ?, ?, ?, 'NEW', now(), now(), null)",
                id, buyer.getId(), property.getId(), agent.getId(), "subject", "message")).isEqualTo(1);
        createdInquiries.add(id);

        doThrow(new IllegalStateException("forced audit sink failure"))
                .when(audit).recordInquiryStatusChangeInTransaction(
                        any(UUID.class), any(UUID.class), any(UUID.class), any(UUID.class),
                        any(UUID.class), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.changeStatus(
                auth(admin.getId(), "ADMIN"), id, patch("IN_PROGRESS")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inquiries.findById(id).orElseThrow().getStatus()).isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id=?", Long.class, id)).isZero();
    }

    private User user(String role, String label) {
        Role entity = roles.findByName(role).orElseThrow();
        User user = new User();
        user.setRole(entity);
        user.setFirstName("Stage65");
        user.setLastName(label);
        user.setEmail(label + UUID.randomUUID() + "@example.com");
        user.setPhone("+919876543210");
        user.setPasswordHash("unused");
        user.setAccountStatus(AccountStatus.ACTIVE);
        User saved = users.saveAndFlush(user);
        createdUsers.add(saved.getId());
        return saved;
    }

    private Property property(User agent) {
        PropertyCategory category = categories.findById(
                UUID.fromString("00000000-0000-0000-0000-000000000101")).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(category.getId(), Sort.by("name"))
                .stream().findFirst().orElseThrow();
        Property property = new Property();
        property.setTitle("Audit rollback " + UUID.randomUUID());
        property.setSlug("audit-rollback-" + UUID.randomUUID());
        property.setDescription("Stage 6.5 audit rollback fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Rollback Road");
        property.setLocality("Rollback Locality");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(agent);
        property.setPublishedAt(Instant.now());
        Property saved = properties.saveAndFlush(property);
        createdProperties.add(saved.getId());
        return saved;
    }

    private static InquiryStatusPatchRequest patch(String status) {
        InquiryStatusPatchRequest request = new InquiryStatusPatchRequest();
        request.setStatus(status);
        return request;
    }

    private static Authentication auth(UUID id, String role) {
        return new UsernamePasswordAuthenticationToken(id.toString(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
