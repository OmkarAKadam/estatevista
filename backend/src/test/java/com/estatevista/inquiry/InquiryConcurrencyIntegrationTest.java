package com.estatevista.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.estatevista.inquiry.repository.InquiryRepository;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Real-PostgreSQL concurrency verification for V18 active-inquiry uniqueness. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class InquiryConcurrencyIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final int WORKERS = 8;

    @Autowired private InquiryRepository inquiries;
    @Autowired private PropertyRepository properties;
    @Autowired private PropertyCategoryRepository categories;
    @Autowired private PropertySubtypeRepository subtypes;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void concurrentDuplicateSubmissionsPersistExactlyOneActiveInquiry() throws Exception {
        UUID buyerId = null;
        UUID agentId = null;
        UUID propertyId = null;
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            Role buyerRole = roles.findByName("BUYER").orElseThrow();
            Role agentRole = roles.findByName("AGENT").orElseThrow();
            User buyer = newUser(buyerRole, "inq-conc-buyer");
            User agent = newUser(agentRole, "inq-conc-agent");
            buyer = users.saveAndFlush(buyer);
            agent = users.saveAndFlush(agent);
            buyerId = buyer.getId();
            agentId = agent.getId();

            PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
            PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                    .stream().findFirst().orElseThrow();
            Property property = new Property();
            property.setTitle(unique("Concurrent Inquiry Property"));
            property.setSlug(unique("concurrent-inquiry-property"));
            property.setDescription("Stage 6.4 concurrency fixture");
            property.setCategory(category);
            property.setSubtype(subtype);
            property.setListingType(ListingType.SALE);
            property.setPrice(new BigDecimal("2500000.00"));
            property.setCurrencyCode("INR");
            property.setAreaValue(new BigDecimal("1200.00"));
            property.setAreaUnit("SQ_FT");
            property.setAddressLine("1 Concurrency Road");
            property.setLocality("Concurrency Locality");
            property.setCity("Vadodara");
            property.setState("Gujarat");
            property.setCountry("India");
            property.setPostalCode("390001");
            property.setStatus(PropertyStatus.PUBLISHED);
            property.setPrimaryAgent(agent);
            property.setCreatedBy(agent);
            property.setPublishedAt(Instant.now());
            propertyId = properties.saveAndFlush(property).getId();

            final UUID bId = buyerId;
            final UUID pId = propertyId;
            final UUID aId = agentId;
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Integer>> tasks = IntStream.range(0, WORKERS)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return transactionTemplate.execute(status ->
                                inquiries.insertNewIgnoringActiveDuplicate(
                                        UUID.randomUUID(), bId, pId, aId,
                                        "Subject " + i, "Message " + i));
                    }).toList();
            List<Future<Integer>> outcomes = tasks.stream().map(executor::submit).toList();
            start.countDown();

            int inserted = 0;
            for (Future<Integer> outcome : outcomes) {
                inserted += outcome.get(30, TimeUnit.SECONDS);
            }
            Long rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM inquiries WHERE buyer_id = ? AND property_id = ? AND status IN ('NEW','IN_PROGRESS')",
                    Long.class, buyerId, propertyId);
            assertThat(inserted).isEqualTo(1);
            assertThat(rows).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            if (buyerId != null && propertyId != null) {
                jdbcTemplate.update("DELETE FROM inquiries WHERE buyer_id = ? AND property_id = ?", buyerId, propertyId);
            }
            if (propertyId != null) {
                jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
            }
            if (buyerId != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
            }
            if (agentId != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", agentId);
            }
        }
    }

    private User newUser(Role role, String label) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage64");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode("Example-Password-123"));
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    private static String unique(String label) {
        return (label + "-" + UUID.randomUUID()).toLowerCase(Locale.ROOT);
    }
}
