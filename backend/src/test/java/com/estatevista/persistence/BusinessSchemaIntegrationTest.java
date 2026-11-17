package com.estatevista.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.estatevista.audit.entity.AuditLog;
import com.estatevista.audit.repository.AuditLogRepository;
import com.estatevista.chat.entity.Conversation;
import com.estatevista.chat.entity.ConversationReadState;
import com.estatevista.chat.entity.ConversationReadStateId;
import com.estatevista.chat.entity.Message;
import com.estatevista.chat.repository.ConversationReadStateRepository;
import com.estatevista.chat.repository.ConversationRepository;
import com.estatevista.chat.repository.MessageRepository;
import com.estatevista.favorite.entity.Favorite;
import com.estatevista.favorite.repository.FavoriteRepository;
import com.estatevista.inquiry.entity.Inquiry;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.notification.entity.Notification;
import com.estatevista.notification.repository.NotificationRepository;
import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyCommercialDetails;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.repository.AmenityRepository;
import com.estatevista.property.repository.PropertyAmenityRepository;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyCommercialDetailsRepository;
import com.estatevista.property.repository.PropertyImageRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertyResidentialDetailsRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.AgentProfile;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.AgentProfileRepository;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL verification of the Stage 2.3 schema and Stage 2.4 mappings.
 *
 * <p>Runs only when {@code TEST_DB_URL} points at an isolated PostgreSQL
 * database. Every test participates in a transaction that is rolled back, so
 * reference data seeded by Flyway remains intact.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class BusinessSchemaIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgentProfileRepository agentProfileRepository;

    @Autowired
    private PropertyCategoryRepository propertyCategoryRepository;

    @Autowired
    private PropertySubtypeRepository propertySubtypeRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository propertyResidentialDetailsRepository;

    @Autowired
    private PropertyCommercialDetailsRepository propertyCommercialDetailsRepository;

    @Autowired
    private PropertyImageRepository propertyImageRepository;

    @Autowired
    private AmenityRepository amenityRepository;

    @Autowired
    private PropertyAmenityRepository propertyAmenityRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationReadStateRepository conversationReadStateRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void allApprovedEntitiesAreMapped() {
        Set<Class<?>> mappedTypes = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(mappedTypes).containsExactlyInAnyOrder(
                Role.class,
                User.class,
                AgentProfile.class,
                PropertyCategory.class,
                PropertySubtype.class,
                Property.class,
                PropertyResidentialDetails.class,
                com.estatevista.property.entity.PropertyCommercialDetails.class,
                PropertyImage.class,
                Amenity.class,
                PropertyAmenity.class,
                Favorite.class,
                Inquiry.class,
                Conversation.class,
                Message.class,
                ConversationReadState.class,
                Notification.class,
                AuditLog.class);
    }

    @Test
    void allRepositoryBeansInitialize() {
        List<Class<?>> repositoryTypes = List.of(
                RoleRepository.class,
                UserRepository.class,
                AgentProfileRepository.class,
                PropertyCategoryRepository.class,
                PropertySubtypeRepository.class,
                PropertyRepository.class,
                PropertyResidentialDetailsRepository.class,
                com.estatevista.property.repository.PropertyCommercialDetailsRepository.class,
                PropertyImageRepository.class,
                AmenityRepository.class,
                PropertyAmenityRepository.class,
                FavoriteRepository.class,
                InquiryRepository.class,
                ConversationRepository.class,
                MessageRepository.class,
                ConversationReadStateRepository.class,
                NotificationRepository.class,
                AuditLogRepository.class);

        repositoryTypes.forEach(type ->
                assertThat(applicationContext.getBean(type)).isNotNull());
    }

    @Test
    void flywayHistoryContainsEveryApprovedMigration() {
        List<String> versions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history order by installed_rank",
                String.class);
        List<Boolean> successes = jdbcTemplate.queryForList(
                "select success from flyway_schema_history order by installed_rank",
                Boolean.class);

        assertThat(versions).containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
        assertThat(successes).hasSize(15).containsOnly(true);
    }

    @Test
    void approvedReferenceDataIsSeeded() {
        assertThat(roleRepository.findByName("BUYER")).isPresent();
        assertThat(roleRepository.findByName("AGENT")).isPresent();
        assertThat(roleRepository.findByName("ADMIN")).isPresent();
        assertThat(propertyCategoryRepository.findAll())
                .extracting(PropertyCategory::getName)
                .containsExactlyInAnyOrder("RESIDENTIAL", "COMMERCIAL");
    }

    @Test
    void keyColumnTypesMatchTheApprovedDesign() {
        assertThat(columnType("users", "created_at")).isEqualTo("timestamp with time zone");
        assertThat(columnType("properties", "price")).isEqualTo("numeric(14,2)");
        assertThat(columnType("properties", "currency_code")).isEqualTo("character(3)");
        assertThat(columnType("properties", "latitude")).isEqualTo("numeric(10,7)");
        assertThat(columnType("properties", "area_value")).isEqualTo("numeric(12,2)");
        assertThat(columnType("property_residential_details", "bedrooms")).isEqualTo("smallint");
        assertThat(columnType("property_images", "sort_order")).isEqualTo("integer");
        assertThat(columnType("property_images", "is_primary")).isEqualTo("boolean");
        assertThat(columnType("messages", "content")).isEqualTo("text");
        assertThat(columnType("audit_logs", "before_data")).isEqualTo("jsonb");
        assertThat(columnType("audit_logs", "ip_address")).isEqualTo("inet");
    }

    @Test
    void persistsAndRetrievesTheFullBusinessGraph() throws Exception {
        Role buyerRole = roleRepository.findByName("BUYER").orElseThrow();
        Role agentRole = roleRepository.findByName("AGENT").orElseThrow();
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();

        User buyer = newUser(buyerRole, "buyer");
        User agent = newUser(agentRole, "agent");
        User admin = newUser(adminRole, "admin");
        userRepository.saveAll(List.of(buyer, agent, admin));

        AgentProfile profile = new AgentProfile();
        profile.setUser(agent);
        profile.setAgencyName("EstateVista Realty");
        agentProfileRepository.save(profile);

        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("Apartment " + shortId());
        propertySubtypeRepository.save(subtype);

        Property property = newProperty(residential, subtype, agent, admin, new BigDecimal("250000.00"));
        propertyRepository.save(property);

        PropertyResidentialDetails details = new PropertyResidentialDetails();
        details.setProperty(property);
        details.setBedrooms((short) 3);
        details.setBathrooms((short) 2);
        details.setFurnishingStatus("UNFURNISHED");
        propertyResidentialDetailsRepository.save(details);

        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageProvider("CLOUDINARY");
        image.setImageUrl("https://example.invalid/property.jpg");
        image.setSortOrder(0);
        image.setIsPrimary(true);
        propertyImageRepository.save(image);

        Amenity amenity = new Amenity();
        amenity.setName("Swimming Pool " + shortId());
        amenityRepository.save(amenity);

        PropertyAmenity propertyAmenity = new PropertyAmenity();
        propertyAmenity.setId(new PropertyAmenityId(property.getId(), amenity.getId()));
        propertyAmenity.setProperty(property);
        propertyAmenity.setAmenity(amenity);
        propertyAmenityRepository.save(propertyAmenity);

        Favorite favorite = new Favorite();
        favorite.setBuyer(buyer);
        favorite.setProperty(property);
        favoriteRepository.save(favorite);

        Inquiry inquiry = new Inquiry();
        inquiry.setBuyer(buyer);
        inquiry.setProperty(property);
        inquiry.setAssignedAgent(agent);
        inquiry.setMessage("Is this property still available?");
        inquiry.setStatus("NEW");
        inquiryRepository.save(inquiry);

        Conversation conversation = new Conversation();
        conversation.setBuyer(buyer);
        conversation.setProperty(property);
        conversation.setAssignedAgent(agent);
        conversation.setStatus("OPEN");
        conversationRepository.save(conversation);

        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(buyer);
        message.setMessageType("TEXT");
        message.setContent("Hello, I would like a viewing.");
        messageRepository.save(message);

        ConversationReadState readState = new ConversationReadState();
        readState.setId(new ConversationReadStateId(conversation.getId(), buyer.getId()));
        readState.setConversation(conversation);
        readState.setUser(buyer);
        readState.setLastReadMessage(message);
        readState.setLastReadAt(Instant.now());
        conversationReadStateRepository.save(readState);

        Notification notification = new Notification();
        notification.setRecipient(agent);
        notification.setType("NEW_INQUIRY");
        notification.setTitle("New inquiry");
        notification.setBody("A buyer submitted an inquiry.");
        notification.setResourceType("INQUIRY");
        notification.setResourceId(inquiry.getId());
        notification.setIsRead(false);
        notificationRepository.save(notification);

        AuditLog auditLog = new AuditLog();
        auditLog.setActor(admin);
        auditLog.setAction("PROPERTY_CREATED");
        auditLog.setEntityType("PROPERTY");
        auditLog.setEntityId(property.getId());
        auditLog.setBeforeData("{\"status\":\"DRAFT\"}");
        auditLog.setAfterData("{\"status\":\"PUBLISHED\"}");
        auditLog.setRequestId("request-" + shortId());
        auditLog.setIpAddress(InetAddress.getByName("127.0.0.1"));
        auditLogRepository.save(auditLog);

        entityManager.flush();
        entityManager.clear();

        Property loaded = propertyRepository.findById(property.getId()).orElseThrow();
        assertThat(loaded.getPrice()).isEqualByComparingTo("250000.00");
        assertThat(loaded.getCurrencyCode()).isEqualTo("USD");
        assertThat(loaded.getListingType()).isEqualTo(ListingType.SALE);
        assertThat(loaded.getStatus()).isEqualTo(PropertyStatus.PUBLISHED);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getPrimaryAgent().getId()).isEqualTo(agent.getId());
        assertThat(loaded.getCreatedBy().getId()).isEqualTo(admin.getId());

        assertThat(propertyResidentialDetailsRepository.findById(property.getId()))
                .isPresent()
                .get()
                .extracting(PropertyResidentialDetails::getBedrooms)
                .isEqualTo((short) 3);
        assertThat(propertyImageRepository.findAll())
                .anySatisfy(storedImage -> {
                    assertThat(storedImage.getProperty().getId()).isEqualTo(property.getId());
                    assertThat(storedImage.getIsPrimary()).isTrue();
                });
        assertThat(propertyAmenityRepository.findById(
                new PropertyAmenityId(property.getId(), amenity.getId()))).isPresent();
        assertThat(favoriteRepository.findAll())
                .anySatisfy(storedFavorite -> assertThat(storedFavorite.getProperty().getId())
                        .isEqualTo(property.getId()));
        assertThat(inquiryRepository.findAll())
                .anySatisfy(storedInquiry -> assertThat(storedInquiry.getAssignedAgent().getId())
                        .isEqualTo(agent.getId()));
        assertThat(messageRepository.findAll())
                .anySatisfy(storedMessage -> assertThat(storedMessage.getConversation().getId())
                        .isEqualTo(conversation.getId()));
        assertThat(conversationReadStateRepository.findById(
                new ConversationReadStateId(conversation.getId(), buyer.getId()))).isPresent();
        assertThat(notificationRepository.findAll())
                .anySatisfy(storedNotification -> assertThat(storedNotification.getRecipient().getId())
                        .isEqualTo(agent.getId()));

        AuditLog loadedAudit = auditLogRepository.findById(auditLog.getId()).orElseThrow();
        assertThat(loadedAudit.getBeforeData()).isEqualTo("{\"status\": \"DRAFT\"}");
        assertThat(loadedAudit.getAfterData()).isEqualTo("{\"status\": \"PUBLISHED\"}");
        assertThat(loadedAudit.getIpAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void persistsAndRetrievesCommercialDetailsWithTheSharedPropertyKey() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "commercial-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "commercial-admin");
        userRepository.saveAll(List.of(agent, admin));

        PropertyCategory commercial = categoryNamed("COMMERCIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(commercial);
        subtype.setName("Office Space " + shortId());
        propertySubtypeRepository.save(subtype);

        Property property = newProperty(
                commercial, subtype, agent, admin, new BigDecimal("1800000.00"));
        propertyRepository.save(property);

        PropertyCommercialDetails details = new PropertyCommercialDetails();
        details.setProperty(property);
        details.setParkingSpaces((short) 12);
        details.setFloorNumber((short) 4);
        details.setTotalFloors((short) 10);
        details.setWashrooms((short) 6);
        details.setPowerBackup(true);
        details.setSuitableFor("Corporate office and retail");
        propertyCommercialDetailsRepository.save(details);

        entityManager.flush();
        entityManager.clear();

        PropertyCommercialDetails loaded = propertyCommercialDetailsRepository
                .findById(property.getId())
                .orElseThrow();

        assertThat(loaded.getPropertyId()).isEqualTo(property.getId());
        assertThat(loaded.getProperty().getId()).isEqualTo(property.getId());
        assertThat(loaded.getParkingSpaces()).isEqualTo((short) 12);
        assertThat(loaded.getFloorNumber()).isEqualTo((short) 4);
        assertThat(loaded.getTotalFloors()).isEqualTo((short) 10);
        assertThat(loaded.getWashrooms()).isEqualTo((short) 6);
        assertThat(loaded.getPowerBackup()).isTrue();
        assertThat(loaded.getSuitableFor()).isEqualTo("Corporate office and retail");
        assertThat(jdbcTemplate.queryForObject(
                "select suitable_for from property_commercial_details where property_id = ?",
                String.class,
                property.getId()))
                .isEqualTo("Corporate office and retail");
    }

    @Test
    void enumsArePersistedAsApprovedText() {
        User buyer = newUser(roleRepository.findByName("BUYER").orElseThrow(), "enum");
        userRepository.saveAndFlush(buyer);

        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("Villa " + shortId());
        propertySubtypeRepository.saveAndFlush(subtype);

        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "enum-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "enum-admin");
        userRepository.saveAllAndFlush(List.of(agent, admin));

        Property property = newProperty(residential, subtype, agent, admin, new BigDecimal("1000.00"));
        property.setListingType(ListingType.RENT);
        property.setStatus(PropertyStatus.DRAFT);
        propertyRepository.saveAndFlush(property);

        assertThat(jdbcTemplate.queryForObject(
                "select account_status from users where id = ?", String.class, buyer.getId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select listing_type from properties where id = ?", String.class, property.getId()))
                .isEqualTo("RENT");
        assertThat(jdbcTemplate.queryForObject(
                "select status from properties where id = ?", String.class, property.getId()))
                .isEqualTo("DRAFT");
    }

    @Test
    void rejectsCaseInsensitiveDuplicateEmail() {
        User first = newUser(roleRepository.findByName("BUYER").orElseThrow(), "case");
        first.setEmail("Duplicate.User@Example.com");
        userRepository.saveAndFlush(first);

        User second = newUser(roleRepository.findByName("BUYER").orElseThrow(), "case2");
        second.setEmail("duplicate.user@example.com");

        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPropertySubtypeFromADifferentCategory() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "mismatch-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "mismatch-admin");
        userRepository.saveAll(List.of(agent, admin));

        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertyCategory commercial = categoryNamed("COMMERCIAL");
        PropertySubtype commercialSubtype = new PropertySubtype();
        commercialSubtype.setCategory(commercial);
        commercialSubtype.setName("Office " + shortId());
        propertySubtypeRepository.saveAndFlush(commercialSubtype);

        Property property = newProperty(
                residential, commercialSubtype, agent, admin, new BigDecimal("5000.00"));

        assertThatThrownBy(() -> propertyRepository.saveAndFlush(property))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositivePrice() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "price-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "price-admin");
        userRepository.saveAll(List.of(agent, admin));

        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("Plot " + shortId());
        propertySubtypeRepository.saveAndFlush(subtype);

        Property property = newProperty(residential, subtype, agent, admin, BigDecimal.ZERO);

        assertThatThrownBy(() -> propertyRepository.saveAndFlush(property))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsASecondPrimaryImageForTheSameProperty() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "image-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "image-admin");
        userRepository.saveAll(List.of(agent, admin));

        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("House " + shortId());
        propertySubtypeRepository.saveAndFlush(subtype);

        Property property = newProperty(residential, subtype, agent, admin, new BigDecimal("750000.00"));
        propertyRepository.saveAndFlush(property);

        propertyImageRepository.saveAndFlush(primaryImage(property, 0));
        assertThatThrownBy(() -> propertyImageRepository.saveAndFlush(primaryImage(property, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsASecondAgentProfileForTheSameUser() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "profile");
        userRepository.saveAndFlush(agent);

        AgentProfile first = new AgentProfile();
        first.setUser(agent);
        first.setAgencyName("First Agency");
        agentProfileRepository.saveAndFlush(first);

        AgentProfile second = new AgentProfile();
        second.setUser(agent);
        second.setAgencyName("Second Agency");

        assertThatThrownBy(() -> agentProfileRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateFavoriteForTheSameBuyerAndProperty() {
        User buyer = newUser(roleRepository.findByName("BUYER").orElseThrow(), "favorite");
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "favorite-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "favorite-admin");
        userRepository.saveAllAndFlush(List.of(buyer, agent, admin));

        Property property = propertyRepository.saveAndFlush(newUnsavedProperty(agent, admin));

        Favorite first = new Favorite();
        first.setBuyer(buyer);
        first.setProperty(property);
        favoriteRepository.saveAndFlush(first);

        Favorite second = new Favorite();
        second.setBuyer(buyer);
        second.setProperty(property);

        assertThatThrownBy(() -> favoriteRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateConversationForTheSameBuyerAndProperty() {
        User buyer = newUser(roleRepository.findByName("BUYER").orElseThrow(), "conversation");
        User agent = newUser(
                roleRepository.findByName("AGENT").orElseThrow(), "conversation-agent");
        User admin = newUser(
                roleRepository.findByName("ADMIN").orElseThrow(), "conversation-admin");
        userRepository.saveAllAndFlush(List.of(buyer, agent, admin));

        Property property = propertyRepository.saveAndFlush(newUnsavedProperty(agent, admin));

        Conversation first = new Conversation();
        first.setBuyer(buyer);
        first.setProperty(property);
        first.setAssignedAgent(agent);
        first.setStatus("OPEN");
        conversationRepository.saveAndFlush(first);

        Conversation second = new Conversation();
        second.setBuyer(buyer);
        second.setProperty(property);
        second.setAssignedAgent(agent);
        second.setStatus("OPEN");

        assertThatThrownBy(() -> conversationRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOutOfRangeLatitude() {
        User agent = newUser(roleRepository.findByName("AGENT").orElseThrow(), "latitude-agent");
        User admin = newUser(roleRepository.findByName("ADMIN").orElseThrow(), "latitude-admin");
        userRepository.saveAllAndFlush(List.of(agent, admin));

        Property property = newUnsavedProperty(agent, admin);
        property.setLatitude(new BigDecimal("90.0000001"));

        assertThatThrownBy(() -> propertyRepository.saveAndFlush(property))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Property newUnsavedProperty(User agent, User admin) {
        PropertyCategory residential = categoryNamed("RESIDENTIAL");
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(residential);
        subtype.setName("Constraint " + shortId());
        propertySubtypeRepository.saveAndFlush(subtype);

        return newProperty(residential, subtype, agent, admin, new BigDecimal("100000.00"));
    }

    private PropertyImage primaryImage(Property property, int sortOrder) {
        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageProvider("CLOUDINARY");
        image.setImageUrl("https://example.invalid/" + shortId() + ".jpg");
        image.setSortOrder(sortOrder);
        image.setIsPrimary(true);
        return image;
    }

    private PropertyCategory categoryNamed(String name) {
        return propertyCategoryRepository.findAll().stream()
                .filter(category -> category.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private User newUser(Role role, String label) {
        User user = new User();
        user.setRole(role);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEmail(label + "-" + shortId() + "@example.com");
        user.setPasswordHash("$2a$10$not-a-real-password-hash");
        user.setAccountStatus(AccountStatus.ACTIVE);
        return user;
    }

    private Property newProperty(
            PropertyCategory category,
            PropertySubtype subtype,
            User agent,
            User admin,
            BigDecimal price) {
        Property property = new Property();
        property.setTitle("Test property " + shortId());
        property.setSlug("test-property-" + shortId());
        property.setDescription("Persistence mapping verification property.");
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(price);
        property.setCurrencyCode("USD");
        property.setAreaValue(new BigDecimal("1250.50"));
        property.setAreaUnit("SQFT");
        property.setCity("Bengaluru");
        property.setState("Karnataka");
        property.setCountry("India");
        property.setLatitude(new BigDecimal("12.9715990"));
        property.setLongitude(new BigDecimal("77.5945660"));
        property.setStatus(PropertyStatus.PUBLISHED);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setUpdatedBy(admin);
        property.setPublishedAt(Instant.now());
        return property;
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject("""
                select format_type(attribute.atttypid, attribute.atttypmod)
                from pg_attribute attribute
                join pg_class relation on relation.oid = attribute.attrelid
                join pg_namespace namespace on namespace.oid = relation.relnamespace
                where namespace.nspname = 'public'
                  and relation.relname = ?
                  and attribute.attname = ?
                  and attribute.attnum > 0
                  and not attribute.attisdropped
                """, String.class, tableName, columnName);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
