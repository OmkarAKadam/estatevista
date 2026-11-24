package com.estatevista.favorite;

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

import com.estatevista.favorite.repository.FavoriteRepository;
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

/**
 * Real-PostgreSQL concurrency verification for the favorite uniqueness guard.
 *
 * <p>Unlike the transactional repository tests, this fixture is committed before
 * the workers start so each worker uses an independent transaction and observes
 * the same database unique constraint as production.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class FavoriteConcurrencyIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant FIXED_TIME = Instant.parse("2026-11-24T10:30:00Z");
    private static final int WORKERS = 8;

    @Autowired
    private FavoriteRepository favorites;
    @Autowired
    private PropertyRepository properties;
    @Autowired
    private PropertyCategoryRepository categories;
    @Autowired
    private PropertySubtypeRepository subtypes;
    @Autowired
    private UserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void concurrentDuplicateAddsPersistExactlyOneFavoriteWithoutRollbackOnlyFailures() throws Exception {
        UUID buyerId = null;
        UUID propertyId = null;
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            Role role = roles.findByName("BUYER").orElseThrow();
            User buyer = new User();
            buyer.setRole(role);
            buyer.setFirstName("Concurrent");
            buyer.setLastName("Favorite");
            buyer.setEmail(unique("concurrent-favorite@example.com"));
            buyer.setPhone("+9199" + UUID.randomUUID().toString().replaceAll("[^0-9]", "").substring(0, 8));
            buyer.setPasswordHash(passwordEncoder.encode("Example-Password-123"));
            buyer.setAccountStatus(AccountStatus.ACTIVE);
            buyer = users.saveAndFlush(buyer);
            buyerId = buyer.getId();

            PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
            PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name"))
                    .stream().findFirst().orElseGet(() -> {
                        PropertySubtype created = new PropertySubtype();
                        created.setCategory(category);
                        created.setName(unique("Concurrent Favorite Subtype"));
                        return subtypes.saveAndFlush(created);
                    });

            Property property = new Property();
            property.setTitle(unique("Concurrent Favorite Property"));
            property.setSlug(unique("concurrent-favorite-property"));
            property.setDescription("Stage 6.2 concurrency fixture");
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
            property.setPrimaryAgent(buyer);
            property.setCreatedBy(buyer);
            property.setPublishedAt(FIXED_TIME);
            propertyId = properties.saveAndFlush(property).getId();

            final UUID concurrentBuyerId = buyerId;
            final UUID concurrentPropertyId = propertyId;
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Integer>> tasks = IntStream.range(0, WORKERS)
                    .<Callable<Integer>>mapToObj(index -> () -> {
                        start.await();
                        return transactionTemplate.execute(status ->
                                favorites.insertIgnoringDuplicate(
                                        UUID.randomUUID(), concurrentBuyerId,
                                        concurrentPropertyId));
                    })
                    .toList();
            List<Future<Integer>> outcomes = tasks.stream().map(executor::submit).toList();
            start.countDown();

            int inserted = 0;
            for (Future<Integer> outcome : outcomes) {
                inserted += outcome.get(20, TimeUnit.SECONDS);
            }

            Long rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM favorites WHERE buyer_id = ? AND property_id = ?",
                    Long.class, buyerId, propertyId);
            assertThat(inserted).isEqualTo(1);
            assertThat(rows).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            if (buyerId != null && propertyId != null) {
                jdbcTemplate.update(
                        "DELETE FROM favorites WHERE buyer_id = ? AND property_id = ?",
                        buyerId, propertyId);
                jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
            }
            if (buyerId != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
            }
        }
    }

    private static String unique(String label) {
        return (label + UUID.randomUUID()).toLowerCase(Locale.ROOT);
    }
}
