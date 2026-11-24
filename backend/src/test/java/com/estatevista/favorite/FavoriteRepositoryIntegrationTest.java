package com.estatevista.favorite;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.estatevista.favorite.entity.Favorite;
import com.estatevista.favorite.repository.FavoriteRepository;
import com.estatevista.property.PropertySummaryMapper;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Real-PostgreSQL repository verification for buyer-scoped favorite queries. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class FavoriteRepositoryIntegrationTest {

    private static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant FIXED_TIME = Instant.parse("2026-11-24T10:30:00Z");

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
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PropertySummaryMapper summaryMapper;

    @Test
    void nativeInsertIsDuplicateSafeAndPreservesTheDatabaseConstraint() {
        User buyer = persistUser("BUYER", "fav-native-buyer");
        Property property = persistProperty(PropertyStatus.PUBLISHED);

        int first = favorites.insertIgnoringDuplicate(
                UUID.randomUUID(), buyer.getId(), property.getId());
        int duplicate = favorites.insertIgnoringDuplicate(
                UUID.randomUUID(), buyer.getId(), property.getId());

        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(countFavorites(buyer.getId(), property.getId())).isEqualTo(1L);
    }

    @Test
    void deleteOnlyRemovesTheAuthenticatedBuyersRelationship() {
        User buyer = persistUser("BUYER", "fav-delete-buyer");
        User otherBuyer = persistUser("BUYER", "fav-delete-other-buyer");
        Property property = persistProperty(PropertyStatus.PUBLISHED);
        persistFavorite(buyer, property, UUID.randomUUID());
        persistFavorite(otherBuyer, property, UUID.randomUUID());
        entityManager.flush();
        entityManager.clear();

        long deleted = favorites.deleteByBuyer_IdAndProperty_Id(buyer.getId(), property.getId());
        entityManager.flush();

        assertThat(deleted).isEqualTo(1L);
        assertThat(favorites.existsByBuyer_IdAndProperty_Id(buyer.getId(), property.getId())).isFalse();
        assertThat(favorites.existsByBuyer_IdAndProperty_Id(
                otherBuyer.getId(), property.getId())).isTrue();
    }

    @Test
    void pageQueryOrdersByCreatedAtWithUuidTiebreak() {
        User buyer = persistUser("BUYER", "fav-page-buyer");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Property first = persistProperty(PropertyStatus.PUBLISHED);
        Property second = persistProperty(PropertyStatus.PUBLISHED);
        persistFavorite(buyer, first, firstId);
        persistFavorite(buyer, second, secondId);
        entityManager.flush();
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = ? WHERE id IN (?, ?)",
                Timestamp.from(FIXED_TIME), firstId, secondId);
        entityManager.clear();

        Page<Favorite> newest = favorites.findPageByBuyer(buyer.getId(),
                PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
        Page<Favorite> oldest = favorites.findPageByBuyer(buyer.getId(),
                PageRequest.of(0, 12, Sort.by(Sort.Direction.ASC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id"))));

        assertThat(newest.getContent().stream().map(Favorite::getId).toList())
                .containsExactly(secondId, firstId);
        assertThat(oldest.getContent().stream().map(Favorite::getId).toList())
                .containsExactly(firstId, secondId);
        assertThat(newest.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void batchStatusQueryIsBuyerScopedPublishedAndSetBased() {
        User buyer = persistUser("BUYER", "fav-batch-buyer");
        User otherBuyer = persistUser("BUYER", "fav-batch-other-buyer");
        Property published = persistProperty(PropertyStatus.PUBLISHED);
        Property draft = persistProperty(PropertyStatus.DRAFT);
        UUID missing = UUID.randomUUID();
        persistFavorite(buyer, published, UUID.randomUUID());
        persistFavorite(buyer, draft, UUID.randomUUID());
        persistFavorite(otherBuyer, published, UUID.randomUUID());
        entityManager.flush();
        entityManager.clear();

        List<UUID> result = favorites.findPublishedPropertyIds(
                buyer.getId(), PropertyStatus.PUBLISHED,
                List.of(published.getId(), draft.getId(), missing));

        assertThat(result).containsExactly(published.getId());
    }

    @Test
    void pageAndSummaryMappingUseABoundedNumberOfStatements() {
        User buyer = persistUser("BUYER", "fav-n1-buyer");
        Property first = persistProperty(PropertyStatus.PUBLISHED);
        Property second = persistProperty(PropertyStatus.PUBLISHED);
        persistFavorite(buyer, first, UUID.randomUUID());
        persistFavorite(buyer, second, UUID.randomUUID());
        entityManager.flush();
        entityManager.clear();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().setStatisticsEnabled(true);
        sessionFactory.getStatistics().clear();
        try {
            Page<Favorite> page = favorites.findPageByBuyer(
                    buyer.getId(), PageRequest.of(0, 12, Sort.by("createdAt")));
            summaryMapper.mapById(page.getContent().stream()
                    .map(Favorite::getProperty)
                    .toList());

            long statements = sessionFactory.getStatistics().getPrepareStatementCount();
            assertThat(page.getContent()).hasSize(2);
            // One page query, one count query, and the two approved set-based
            // summary lookups. No per-row category/subtype lazy load is allowed.
            assertThat(statements).isLessThanOrEqualTo(4);
        } finally {
            sessionFactory.getStatistics().setStatisticsEnabled(false);
        }
    }

    private long countFavorites(UUID buyerId, UUID propertyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE buyer_id = ? AND property_id = ?",
                Long.class, buyerId, propertyId);
        return count == null ? 0L : count;
    }

    private User persistUser(String roleName, String label) {
        Role role = roles.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage62");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com"));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode("Example-Password-123"));
        user.setAccountStatus(AccountStatus.ACTIVE);
        return users.saveAndFlush(user);
    }

    private Property persistProperty(PropertyStatus status) {
        PropertyCategory category = categories.findById(CATEGORY_ID).orElseThrow();
        PropertySubtype subtype = subtypes.findByCategory_Id(CATEGORY_ID, Sort.by("name")).stream()
                .findFirst().orElseGet(() -> {
                    PropertySubtype created = new PropertySubtype();
                    created.setCategory(category);
                    created.setName(unique("Favorite Subtype"));
                    return subtypes.saveAndFlush(created);
                });
        User admin = persistUser("ADMIN", "fav-property-admin");
        User agent = persistUser("AGENT", "fav-property-agent");

        Property property = new Property();
        property.setTitle(unique("Favorite Property"));
        property.setSlug(unique("favorite-property"));
        property.setDescription("Stage 6.2 repository fixture");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Favorite Road");
        property.setLocality("Favorite Locality");
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setStatus(status);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setPublishedAt(status == PropertyStatus.PUBLISHED ? FIXED_TIME : null);
        return properties.saveAndFlush(property);
    }

    private void persistFavorite(User buyer, Property property, UUID id) {
        // The production insert path accepts an explicit ID and is duplicate
        // safe, making deterministic tie-break fixtures possible without
        // Hibernate treating a preassigned generated ID as detached state.
        favorites.insertIgnoringDuplicate(id, buyer.getId(), property.getId());
    }

    private static String unique(String label) {
        return (label + UUID.randomUUID()).toLowerCase(Locale.ROOT);
    }
}
