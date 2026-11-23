package com.estatevista.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.estatevista.audit.AuditEventService;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyCategory;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyImageDeletionOutbox;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.entity.PropertySubtype;
import com.estatevista.property.image.ImageCleanupProperties;
import com.estatevista.property.image.ImageDeletionRequested;
import com.estatevista.property.image.ImageStorage;
import com.estatevista.property.image.ProviderAssetNotFoundException;
import com.estatevista.property.image.PropertyImageCleanupWorker;
import com.estatevista.property.image.SanitizedImage;
import com.estatevista.property.image.StoredImage;
import com.estatevista.property.repository.AmenityRepository;
import com.estatevista.property.repository.PropertyCategoryRepository;
import com.estatevista.property.repository.PropertyImageDeletionOutboxRepository;
import com.estatevista.property.repository.PropertyImageRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.property.repository.PropertyResidentialDetailsRepository;
import com.estatevista.property.repository.PropertySubtypeRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Real-PostgreSQL verification for the Stage 4.5 property image endpoints.
 *
 * <p>Only a deterministic, test-only storage adapter is imported. No provider
 * SDK, credential, or production adapter is present. Every mutation uses a real
 * server session and the server-issued CSRF token.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Import(PropertyImageManagementIntegrationTest.FakeStorageConfig.class)
class PropertyImageManagementIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PASSWORD = "Example-Password-123";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String SESSION_COOKIE = "EV_SESSION";
    private static final UUID RESIDENTIAL_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PropertyCategoryRepository categoryRepository;

    @Autowired
    private PropertySubtypeRepository subtypeRepository;

    @Autowired
    private AmenityRepository amenityRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyResidentialDetailsRepository residentialDetailsRepository;

    @Autowired
    private PropertyImageRepository imageRepository;

    @Autowired
    private PropertyImageDeletionOutboxRepository outboxRepository;

    @Autowired
    private PropertyImageCleanupWorker cleanupWorker;

    @Autowired
    private ImageCleanupProperties cleanupProperties;

    @Autowired
    private ObjectProvider<ImageStorage> storageProvider;

    @Autowired
    private AuditEventService audit;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FakeImageStorage fakeStorage;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubtypeIds = new ArrayList<>();
    private final List<UUID> createdPropertyIds = new ArrayList<>();
    private final Set<String> createdSessionIds = new HashSet<>();

    @BeforeEach
    void resetFakeStorage() {
        // Defensive cleanup so an interrupted failure-injection test cannot leak
        // a trigger into later tests or a later run.
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS ev_fail_outbox_completion_trigger "
                + "ON property_image_deletion_outbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS ev_fail_outbox_completion()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS ev_fail_image_delete_audit_trigger ON audit_logs");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS ev_fail_image_delete_audit()");
        fakeStorage.reset();
    }

    @AfterEach
    void cleanUp() {
        Set<UUID> propertyIds = new LinkedHashSet<>(createdPropertyIds);
        for (UUID userId : createdUserIds) {
            propertyIds.addAll(jdbcTemplate.queryForList(
                    "SELECT id FROM properties WHERE created_by = ?", UUID.class, userId));
        }
        for (UUID propertyId : propertyIds) {
            for (UUID imageId : jdbcTemplate.queryForList(
                    "SELECT id FROM property_images WHERE property_id = ?",
                    UUID.class, propertyId)) {
                jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", imageId);
            }
            jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", propertyId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", userId);
        }
        for (UUID propertyId : propertyIds) {
            jdbcTemplate.update(
                    "DELETE FROM property_image_deletion_outbox WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM property_images WHERE property_id = ?", propertyId);
            jdbcTemplate.update(
                    "DELETE FROM property_residential_details WHERE property_id = ?", propertyId);
            jdbcTemplate.update("DELETE FROM properties WHERE id = ?", propertyId);
        }
        for (UUID subtypeId : createdSubtypeIds) {
            jdbcTemplate.update("DELETE FROM property_subtypes WHERE id = ?", subtypeId);
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
        }
        jdbcTemplate.update("DELETE FROM auth_rate_limit_events");
    }

    // ------------------------------------------------------------------
    // Authorization and role coverage
    // ------------------------------------------------------------------

    @Test
    void adminUploadsValidJpegAndRecordsSafeMetadata() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        MvcResult result = upload(admin, fixture.draft().getId(),
                file("photo.jpg", "image/jpeg", jpeg(240, 220)),
                "Front elevation", Boolean.TRUE, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.altText").value("Front elevation"))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.isPrimary").value(true))
                .andExpect(jsonPath("$.data.storagePublicId").doesNotExist())
                .andExpect(jsonPath("$.data.storageProvider").doesNotExist())
                .andReturn();

        UUID imageId = imageId(result);
        PropertyImage stored = imageRepository.findById(imageId).orElseThrow();
        assertThat(stored.getProperty().getId()).isEqualTo(fixture.draft().getId());
        assertThat(stored.getStoragePublicId()).startsWith(
                "estatevista/properties/" + fixture.draft().getId() + "/");
        assertThat(fakeStorage.objects()).containsKey(stored.getStoragePublicId());
        assertThat(countAudit(AuditEventService.ACTION_IMAGE_UPLOAD, imageId)).isEqualTo(1);
    }

    @Test
    void assignedAgentUploadsValidPng() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());

        upload(agent, fixture.draft().getId(),
                file("floor-plan.png", "image/png", png(220, 220)), null, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.altText").doesNotExist());

        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isEqualTo(1);
    }

    @Test
    void unassignedAgentCannotUpload() throws Exception {
        Fixture fixture = seedFixture();
        Session otherAgent = login(fixture.otherAgent().getEmail());

        upload(otherAgent, fixture.draft().getId(),
                file("photo.png", "image/png", png(220, 220)), null, null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(fakeStorage.uploadCalls()).isZero();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void buyerAndAnonymousCannotMutateImages() throws Exception {
        Fixture fixture = seedFixture();
        Session buyer = login(fixture.buyer().getEmail());

        Session anonymous = anonymousSession();
        upload(buyer, fixture.draft().getId(),
                file("photo.png", "image/png", png(220, 220)), null, null, null)
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .file(file("photo.png", "image/png", png(220, 220)))
                        .cookie(anonymous.cookie())
                        .header(CSRF_HEADER, anonymous.csrfToken()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), UUID.randomUUID())
                        .cookie(buyer.cookie()).header(CSRF_HEADER, buyer.csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), UUID.randomUUID())
                        .cookie(anonymous.cookie())
                        .header(CSRF_HEADER, anonymous.csrfToken()))
                .andExpect(status().isUnauthorized());
        assertThat(fakeStorage.uploadCalls()).isZero();
    }

    @Test
    void missingAndInvalidCsrfTokensAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        mockMvc.perform(multipart("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .file(file("photo.png", "image/png", png(220, 220)))
                        .cookie(admin.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(multipart("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .file(file("photo.png", "image/png", png(220, 220)))
                        .cookie(admin.cookie())
                        .header(CSRF_HEADER, "not-a-valid-token"))
                .andExpect(status().isForbidden());

        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), imageId).cookie(admin.cookie()))
                .andExpect(status().isForbidden());
        assertThat(fakeStorage.deleteCalls()).isZero();
    }

    // ------------------------------------------------------------------
    // Upload validation and sanitization
    // ------------------------------------------------------------------

    @Test
    void validWebpIsDecodedReencodedAndStored() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        byte[] source = webp(240, 200);

        upload(admin, fixture.draft().getId(),
                file("photo.webp", "image/webp", source), null, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageUrl").value(
                        org.hamcrest.Matchers.containsString(".webp")));

        StoredObject stored = fakeStorage.lastObject();
        assertThat(stored.bytes()).isNotEqualTo(source);
        assertThat(stored.bytes()).startsWith(
                (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F');
        assertThat(ascii(stored.bytes(), 8)).isEqualTo("WEBP");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored.bytes()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(240);
        assertThat(decoded.getHeight()).isEqualTo(200);
        assertThat(webpMetadataChunks(stored.bytes())).isEmpty();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isEqualTo(1);
    }

    @Test
    void malformedWebpIsRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        byte[] malformed = webp(240, 200).clone();
        putInt(malformed, 4, malformed.length + 16);

        upload(admin, fixture.draft().getId(),
                file("broken.webp", "image/webp", malformed), null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(fakeStorage.uploadCalls()).isZero();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void animatedWebpIsRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("animated.webp", "image/webp", animatedWebp()), null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(fakeStorage.uploadCalls()).isZero();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void webpMetadataIsRemovedBeforeStorage() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String secret = "GPS-LAT-22.31-LON-73.21";

        upload(admin, fixture.draft().getId(),
                file("tagged.webp", "image/webp", webpWithMetadata(secret)),
                null, null, null)
                .andExpect(status().isCreated());

        StoredObject stored = fakeStorage.lastObject();
        assertThat(new String(stored.bytes(), StandardCharsets.ISO_8859_1))
                .doesNotContain(secret);
        assertThat(webpMetadataChunks(stored.bytes())).isEmpty();
    }

    @Test
    void unsupportedFormatsAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("bad.gif", "image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)),
                null, null, null)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        upload(admin, fixture.draft().getId(),
                file("bad.svg", "image/svg+xml",
                        "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)),
                null, null, null)
                .andExpect(status().isUnsupportedMediaType());

        assertThat(fakeStorage.uploadCalls()).isZero();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void spoofedMimeAndExtensionAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("photo.jpg", "image/jpeg", png(220, 220)), null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        upload(admin, fixture.draft().getId(),
                file("photo.png", "image/png", jpeg(240, 220)), null, null, null)
                .andExpect(status().isBadRequest());

        assertThat(fakeStorage.uploadCalls()).isZero();
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        byte[] magic = jpeg(240, 220);
        System.arraycopy(magic, 0, oversized, 0, Math.min(magic.length, oversized.length));

        upload(admin, fixture.draft().getId(),
                file("large.jpg", "image/jpeg", oversized), null, null, null)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(fakeStorage.uploadCalls()).isZero();
    }

    @Test
    void invalidDimensionsAndExcessivePixelsAreRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("small.png", "image/png", png(100, 120)), null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        upload(admin, fixture.draft().getId(),
                file("huge.png", "image/png", pngWithClaimedDimensions(10_000, 10_000)),
                null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(fakeStorage.uploadCalls()).isZero();
    }

    @Test
    void malformedImageIsRejected() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        byte[] malformed = new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x01, 0x02, 0x03
        };

        upload(admin, fixture.draft().getId(),
                file("broken.jpg", "image/jpeg", malformed), null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(fakeStorage.uploadCalls()).isZero();
    }

    @Test
    void embeddedMetadataIsStrippedBeforeStorage() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String secret = "GPS-LAT-22.31-LON-73.21";

        upload(admin, fixture.draft().getId(),
                file("tagged.jpg", "image/jpeg", jpegWithComment(secret)),
                null, null, null)
                .andExpect(status().isCreated());

        String stored = new String(fakeStorage.lastObject().bytes(), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain(secret);
        assertThat(stored).doesNotContain("Exif");
    }

    @Test
    void maximumImageCountIsEnforced() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        for (int index = 0; index < 10; index++) {
            upload(admin, fixture.draft().getId(),
                    file("image-" + index + ".png", "image/png", png(200 + index, 200)),
                    null, null, null)
                    .andExpect(status().isCreated());
        }

        upload(admin, fixture.draft().getId(),
                file("eleventh.png", "image/png", png(210, 210)), null, null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isEqualTo(10);
        assertThat(fakeStorage.uploadCalls()).isEqualTo(10);
    }

    @Test
    void storageKeyIsServerGeneratedAndSafe() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("../../evil(1).png", "image/png", png(220, 220)), null, null, null)
                .andExpect(status().isCreated());

        StoredObject object = fakeStorage.lastObject();
        String expectedPrefix =
                "estatevista/properties/" + fixture.draft().getId() + "/";
        assertThat(object.key()).startsWith(expectedPrefix).endsWith(".png");
        assertThat(object.key()).doesNotContain("evil", "..", "\\");
        assertThat(object.publicId()).startsWith(expectedPrefix)
                .doesNotContain("evil", "..", "\\");
        assertThat(object.publicId().substring(expectedPrefix.length())).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(object.url()).startsWith("https://images.example.invalid/");
    }

    // ------------------------------------------------------------------
    // Metadata, primary, ordering, and deletion
    // ------------------------------------------------------------------

    @Test
    void metadataPersistsAndListIsOrderedBySortOrder() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID first = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png");
        UUID second = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        UUID third = uploadImage(admin, fixture.draft().getId(), png(230, 230), "png");

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), third)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":0,\"altText\":\"Lead image\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.altText").value("Lead image"))
                .andExpect(jsonPath("$.data.sortOrder").value(0));

        JsonNode list = readData(mockMvc.perform(
                        get("/api/v1/properties/{id}/images", fixture.draft().getId())
                                .cookie(admin.cookie()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).at("/id").asText()).isEqualTo(third.toString());
        assertThat(list.get(1).at("/id").asText()).isEqualTo(first.toString());
        assertThat(list.get(2).at("/id").asText()).isEqualTo(second.toString());
        assertThat(imageRepository.findById(third).orElseThrow().getAltText())
                .isEqualTo("Lead image");
    }

    @Test
    void primarySelectionSwitchesAndPartialUniqueIndexIsHeld() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID first = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png", true);
        UUID second = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png", true);

        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .extracting(PropertyImage::getId)
                .containsExactly(second);

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}/primary",
                        fixture.draft().getId(), first)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.isPrimary == true)].id")
                        .value(hasItem(first.toString())));

        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .extracting(PropertyImage::getId)
                .containsExactly(first);
        assertThat(countAudit(AuditEventService.ACTION_IMAGE_PRIMARY, first)).isEqualTo(1);
    }

    @Test
    void deletingPrimaryLeavesNoPrimaryAndNoBody() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID primary = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png", true);

        MvcResult result = mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), primary)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();

        assertThat(imageRepository.findById(primary)).isEmpty();
        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .isEmpty();
        assertThat(onlyOutboxFor(primary).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);
        assertThat(fakeStorage.deleteCalls()).isZero();

        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
        assertThat(onlyOutboxFor(primary).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(countAudit(AuditEventService.ACTION_IMAGE_DELETE, primary)).isEqualTo(1);
    }

    @Test
    void metadataUpdatePreservesPrimaryAndRejectsForbiddenFields() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID primary = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png", true);
        UUID other = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), other)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":0}"))
                .andExpect(status().isOk());

        assertThat(imageRepository.findById(other).orElseThrow().getSortOrder()).isZero();
        assertThat(imageRepository.findById(primary).orElseThrow().getIsPrimary()).isTrue();

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), other)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isPrimary\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("isPrimary")));
        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), other)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altText\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), other)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":-1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), other)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sortOrder\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(hasItem("sortOrder")));
    }

    @Test
    void foreignAndRepeatedImageIdsAreHandledSafely() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID own = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png");
        UUID foreign = uploadImage(admin, fixture.otherProperty().getId(), png(220, 220), "png");

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), foreign)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altText\":\"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}/primary",
                        fixture.draft().getId(), foreign)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), foreign)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isNotFound());
        assertThat(fakeStorage.deleteCalls()).isZero();

        // Repeating a primary selection is idempotent and never creates a second
        // primary row. There is no list-based reorder endpoint, so duplicate IDs
        // cannot be submitted as a batch.
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}/primary",
                            fixture.draft().getId(), own)
                            .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                    .andExpect(status().isOk());
        }
        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .extracting(PropertyImage::getId)
                .containsExactly(own);
    }

    @Test
    void unauthorizedCrossPropertyOperationsAreDenied() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        UUID imageId = uploadImage(agent, fixture.draft().getId(), png(210, 210), "png");

        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}",
                        fixture.otherProperty().getId(), imageId)
                        .cookie(agent.cookie()).header(CSRF_HEADER, agent.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"altText\":\"steal\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}/primary",
                        fixture.otherProperty().getId(), imageId)
                        .cookie(agent.cookie()).header(CSRF_HEADER, agent.csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.otherProperty().getId(), imageId)
                        .cookie(agent.cookie()).header(CSRF_HEADER, agent.csrfToken()))
                .andExpect(status().isForbidden());

        assertThat(imageRepository.findById(imageId).orElseThrow().getAltText()).isNull();
        assertThat(fakeStorage.deleteCalls()).isZero();
    }

    // ------------------------------------------------------------------
    // Failure handling and concurrency
    // ------------------------------------------------------------------

    @Test
    void externalUploadFailureReturnsSafeServiceUnavailable() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        fakeStorage.failUpload = true;

        String body = upload(admin, fixture.draft().getId(),
                file("photo.png", "image/png", png(220, 220)), null, null, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("simulated", "secret", "example.invalid", "api_key");
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void databaseFailureAfterUploadCleansUpStoredObject() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());

        upload(admin, fixture.draft().getId(),
                file("photo.png", "image/png", png(220, 220)),
                "invalid\0value", null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        assertThat(fakeStorage.uploadCalls()).isEqualTo(1);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
        assertThat(fakeStorage.objects()).isEmpty();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
    }

    @Test
    void externalDeleteFailureIsRetriedAfterMetadataCommit() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        fakeStorage.failDelete = true;

        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), imageId)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isNoContent());

        assertThat(imageRepository.findById(imageId)).isEmpty();
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);
        assertThat(fakeStorage.objects()).containsKey(publicId);

        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);
        assertThat(task.getLastError()).isEqualTo("IllegalStateException");
        assertThat(task.getLastError()).doesNotContain("api_secret", "SECRET");
        assertThat(fakeStorage.objects()).containsKey(publicId);
    }

    @Test
    void concurrentPrimaryChangesLeaveExactlyOnePrimary() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID first = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png");
        UUID second = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (UUID target : List.of(first, second)) {
            futures.add(executor.submit(() -> {
                start.await();
                return mockMvc.perform(patch("/api/v1/properties/{id}/images/{imageId}/primary",
                                fixture.draft().getId(), target)
                                .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (var future : futures) {
            assertThat(future.get()).isEqualTo(200);
        }

        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // Visibility, archived properties, and regression
    // ------------------------------------------------------------------

    @Test
    void publicListingRulesHideNonPublishedImages() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Session agent = login(fixture.agent().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());
        uploadImage(admin, fixture.published().getId(), png(220, 220), "png");
        uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.published().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.draft().getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .cookie(agent.cookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .cookie(otherAgent.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/properties/{id}/images", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void archivedPropertyBlocksAgentAndAdminMutations() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Session admin = login(fixture.admin().getEmail());
        // Seeded directly because archived-property mutations are intentionally
        // blocked by the endpoint under test.
        UUID imageId = persistImage(fixture.archived(), false);

        upload(agent, fixture.archived().getId(),
                file("photo.png", "image/png", png(230, 230)), null, null, null)
                .andExpect(status().isForbidden());
        upload(admin, fixture.archived().getId(),
                file("photo.png", "image/png", png(230, 230)), null, null, null)
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.archived().getId(), imageId)
                        .cookie(agent.cookie()).header(CSRF_HEADER, agent.csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.archived().getId(), imageId)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken()))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.archived().getId())
                        .cookie(admin.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        assertThat(imageRepository.findById(imageId)).isPresent();
        assertThat(fakeStorage.deleteCalls()).isZero();
    }

    @Test
    void existingPropertyCrudRemainsFunctional() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        String title = "Regression listing " + UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/properties")
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(title, fixture.subtype().getId(),
                                fixture.agent().getId())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID propertyId = imageId(created);
        createdPropertyIds.add(propertyId);

        mockMvc.perform(patch("/api/v1/properties/{id}", propertyId)
                        .cookie(admin.cookie()).header(CSRF_HEADER, admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + " updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title + " updated"));

        assertThat(propertyRepository.findById(propertyId)).isPresent();
    }

    // ------------------------------------------------------------------
    // Approved durable deletion-outbox scenarios (real PostgreSQL, fake provider)
    // ------------------------------------------------------------------

    @Test
    void deletionScenario01SuccessfulMetadataDeletionThenProviderCleanup() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();

        MvcResult result = deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(imageRepository.findById(imageId)).isEmpty();
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);
        assertThat(task.getStoragePublicId()).isEqualTo(publicId);
        assertThat(fakeStorage.deleteCalls()).isZero();

        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
        assertThat(fakeStorage.deletedPublicIds()).containsExactly(publicId);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(onlyOutboxFor(imageId).getLastAttemptAt()).isNotNull();
        assertThat(onlyOutboxFor(imageId).getCompletedAt()).isNotNull();
        assertThat(countCleanupAudit(imageId, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void deletionScenario02DatabaseFailureBeforeCommitReturnsInternalError() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        PropertyImage image = imageRepository.findById(imageId).orElseThrow();
        seedBlockingOutbox(
                fixture.draft().getId(), imageId, image, fixture.admin().getId());

        String body = deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("simulated", "api_secret", "SECRET");
        assertThat(imageRepository.findById(imageId)).isPresent();
        assertThat(outboxRepository.findAll())
                .filteredOn(task -> imageId.equals(task.getImageId()))
                .hasSize(1);
        assertThat(countAudit(AuditEventService.ACTION_IMAGE_DELETE, imageId)).isZero();
    }

    @Test
    void deletionScenario03RollbackLeavesProviderImageIntact() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        PropertyImage image = imageRepository.findById(imageId).orElseThrow();
        String publicId = image.getStoragePublicId();
        seedBlockingOutbox(
                fixture.draft().getId(), imageId, image, fixture.admin().getId());

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isInternalServerError());

        assertThat(fakeStorage.objects()).containsKey(publicId);
        assertThat(fakeStorage.deletedPublicIds()).isEmpty();
        assertThat(fakeStorage.deleteCalls()).isZero();
        mockMvc.perform(get("/api/v1/properties/{id}/images", fixture.draft().getId())
                        .cookie(admin.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void deletionAuditFailureRollsBackMetadataAndOutbox() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION ev_fail_image_delete_audit() RETURNS trigger AS $$
                BEGIN
                    IF NEW.action = 'PROPERTY_IMAGE_DELETE' THEN
                        RAISE EXCEPTION 'simulated audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER ev_fail_image_delete_audit_trigger
                BEFORE INSERT ON audit_logs
                FOR EACH ROW EXECUTE FUNCTION ev_fail_image_delete_audit()
                """);
        try {
            String body = deleteImage(admin, fixture.draft().getId(), imageId)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("simulated", "audit failure", "SECRET");
            assertThat(imageRepository.findById(imageId)).isPresent();
            assertThat(fakeStorage.objects()).containsKey(publicId);
            assertThat(fakeStorage.deleteCalls()).isZero();
            assertThat(outboxRepository.findAll())
                    .filteredOn(task -> imageId.equals(task.getImageId()))
                    .isEmpty();
            assertThat(countAudit(AuditEventService.ACTION_IMAGE_DELETE, imageId)).isZero();
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS ev_fail_image_delete_audit_trigger ON audit_logs");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS ev_fail_image_delete_audit()");
        }
    }

    @Test
    void deletionScenario04ProviderFailureSchedulesRetryWithoutMetadataRollback() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        fakeStorage.failDelete = true;

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());

        assertThat(imageRepository.findById(imageId)).isEmpty();
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);

        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("IllegalStateException");
        assertThat(task.getLastError()).doesNotContain("api_secret", "SECRET");
        assertThat(task.getLastAttemptAt()).isNotNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(fakeStorage.objects()).containsKey(publicId);
        assertThat(countCleanupAudit(imageId, "RETRY_SCHEDULED")).isEqualTo(1);
    }

    @Test
    void deletionScenario05ProviderTimeoutIsRecordedAsRetryableFailure() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        fakeStorage.timeoutDelete = true;

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);

        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);
        assertThat(task.getLastError()).isEqualTo("SimulatedProviderTimeout");
        assertThat(task.getLastError()).doesNotContain("timed out", "SECRET");
    }

    @Test
    void deletionScenario06RetriedProviderDeletionEventuallyCompletes() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        fakeStorage.failDeleteCalls = 1;

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);

        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getLastError()).isNull();
        assertThat(fakeStorage.deleteCalls()).isEqualTo(2);
        assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
    }

    @Test
    void deletionScenario07AlreadyMissingProviderAssetIsSuccessfulCleanup() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        fakeStorage.objects().remove(publicId);
        fakeStorage.missingAsset = true;

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);

        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(countCleanupAudit(imageId, "ALREADY_MISSING")).isEqualTo(1);
        assertThat(cleanupWorker.processDueTasks()).isZero();
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
    }

    @Test
    void deletionScenario08DuplicateRequestsCreateOneTaskAndOneCleanup() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(outboxRepository.findAll())
                .filteredOn(task -> imageId.equals(task.getImageId()))
                .hasSize(1);
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(cleanupWorker.processDueTasks()).isZero();
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
    }

    @Test
    void deletionScenario09ConcurrentDeletesCreateOneTaskAndOneCleanup() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                return deleteImage(admin, fixture.draft().getId(), imageId)
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<Integer> statuses = new ArrayList<>();
        for (var future : futures) {
            statuses.add(future.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
        assertThat(outboxRepository.findAll())
                .filteredOn(task -> imageId.equals(task.getImageId()))
                .hasSize(1);
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
    }

    @Test
    void deletionScenario10PrimaryDeletionLeavesNoPrimaryAndDurableTask() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID primary = uploadImage(
                admin, fixture.draft().getId(), png(210, 210), "png", true);

        deleteImage(admin, fixture.draft().getId(), primary)
                .andExpect(status().isNoContent());

        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .isEmpty();
        assertThat(imageRepository.countByProperty_Id(fixture.draft().getId())).isZero();
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(onlyOutboxFor(primary).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
    }

    @Test
    void deletionScenario11NonPrimaryDeletionPreservesExistingPrimary() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID primary = uploadImage(
                admin, fixture.draft().getId(), png(210, 210), "png", true);
        UUID secondary = uploadImage(
                admin, fixture.draft().getId(), png(220, 220), "png");

        deleteImage(admin, fixture.draft().getId(), secondary)
                .andExpect(status().isNoContent());
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);

        assertThat(imageRepository.findById(secondary)).isEmpty();
        assertThat(imageRepository.findByProperty_IdAndIsPrimaryTrue(fixture.draft().getId()))
                .extracting(PropertyImage::getId)
                .containsExactly(primary);
        assertThat(onlyOutboxFor(secondary).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
    }

    @Test
    void deletionScenario12PendingCleanupSurvivesFreshWorkerInstance() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);

        // No in-memory queue is used: a fresh worker with the same repositories
        // simulates process restart and recovers the committed database task.
        PropertyImageCleanupWorker restartedWorker = new PropertyImageCleanupWorker(
                outboxRepository, storageProvider, cleanupProperties, audit, transactionManager,
                task -> task.run());
        assertThat(restartedWorker.processDueTasks()).isEqualTo(1);

        assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
    }

    @Test
    void deletionScenario13AuthorizationDenialCreatesNoCleanupTask() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        Session buyer = login(fixture.buyer().getEmail());
        Session otherAgent = login(fixture.otherAgent().getEmail());
        Session anonymous = anonymousSession();
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");

        deleteImage(buyer, fixture.draft().getId(), imageId)
                .andExpect(status().isForbidden());
        deleteImage(otherAgent, fixture.draft().getId(), imageId)
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        fixture.draft().getId(), imageId)
                        .cookie(anonymous.cookie()).header(CSRF_HEADER, anonymous.csrfToken()))
                .andExpect(status().isUnauthorized());

        assertThat(imageRepository.findById(imageId)).isPresent();
        assertThat(outboxRepository.count()).isZero();
        assertThat(fakeStorage.deleteCalls()).isZero();
    }

    @Test
    void deletionScenario14ArchivedPropertyDenialCreatesNoCleanupTask() throws Exception {
        Fixture fixture = seedFixture();
        Session agent = login(fixture.agent().getEmail());
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = persistImage(fixture.archived(), false);

        deleteImage(agent, fixture.archived().getId(), imageId)
                .andExpect(status().isForbidden());
        deleteImage(admin, fixture.archived().getId(), imageId)
                .andExpect(status().isConflict());

        assertThat(imageRepository.findById(imageId)).isPresent();
        assertThat(outboxRepository.count()).isZero();
        assertThat(fakeStorage.deleteCalls()).isZero();
    }

    @Test
    void deletionScenario15DeadLetterCanBeRequeuedAndRecovered() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        fakeStorage.failDelete = true;

        deleteImage(admin, fixture.draft().getId(), imageId)
                .andExpect(status().isNoContent());
        for (int attempt = 0; attempt < cleanupProperties.getMaxAttempts(); attempt++) {
            assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        }

        PropertyImageDeletionOutbox deadLettered = onlyOutboxFor(imageId);
        assertThat(deadLettered.getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_DEAD_LETTER);
        assertThat(deadLettered.getAttempts()).isEqualTo(cleanupProperties.getMaxAttempts());
        assertThat(deadLettered.getLastError()).isEqualTo("IllegalStateException");
        assertThat(countCleanupAudit(imageId, "DEAD_LETTER")).isEqualTo(1);
        assertThat(fakeStorage.objects()).containsKey(publicId);

        fakeStorage.failDelete = false;
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE property_image_deletion_outbox
                SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                    locked_until = NULL, last_error = NULL, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), deadLettered.getId());

        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
    }

    // ------------------------------------------------------------------
    // Additional required failure-path, concurrency, and migration coverage
    // ------------------------------------------------------------------

    @Test
    void outboxUniquenessRejectsDuplicateProviderAsset() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO property_image_deletion_outbox
                    (id, property_id, image_id, storage_provider, storage_public_id,
                     actor_id, status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now(), now())
                """,
                UUID.randomUUID(), fixture.draft().getId(), UUID.randomUUID(),
                task.getStorageProvider(), task.getStoragePublicId(), fixture.admin().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expiredClaimLeaseIsRecoveredByAnotherWorker() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);

        // Simulate a worker that claimed the row and crashed before completion.
        Instant stale = Instant.now().minusSeconds(120);
        jdbcTemplate.update("""
                UPDATE property_image_deletion_outbox
                SET status = 'PROCESSING', attempts = 1, next_attempt_at = ?,
                    locked_until = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(stale), Timestamp.from(stale), Timestamp.from(stale),
                task.getId());
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);

        assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
    }

    @Test
    void afterCommitDispatchIsNonBlockingAndClaimsEachTaskOnce() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());
        PropertyImageDeletionOutbox task = onlyOutboxFor(imageId);
        assertThat(task.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);

        ImageCleanupProperties enabled = new ImageCleanupProperties();
        enabled.setEnabled(true);
        enabled.setPollMs(3_600_000);
        enabled.setBatchSize(20);
        enabled.setMaxAttempts(3);
        enabled.setBackoffMs(0);
        enabled.setMaxBackoffMs(60_000);
        enabled.setLeaseSeconds(60);
        List<Runnable> dispatched = new CopyOnWriteArrayList<>();
        PropertyImageCleanupWorker dispatchWorker = new PropertyImageCleanupWorker(
                outboxRepository, storageProvider, enabled, audit, transactionManager,
                dispatched::add);

        dispatchWorker.on(new ImageDeletionRequested(
                task.getId(), fixture.draft().getId(), imageId, fixture.admin().getId()));
        assertThat(dispatched).hasSize(1);
        assertThat(fakeStorage.deleteCalls()).isZero();
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);

        dispatched.forEach(Runnable::run);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);

        // A second, concurrent dispatch pass cannot claim the finished task.
        dispatchWorker.on(new ImageDeletionRequested(
                task.getId(), fixture.draft().getId(), imageId, fixture.admin().getId()));
        dispatched.forEach(Runnable::run);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(1);
    }

    @Test
    void databaseFailureAfterProviderCleanupLeavesRecoverableTask() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        String publicId = imageRepository.findById(imageId).orElseThrow().getStoragePublicId();
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());

        jdbcTemplate.execute("""
                CREATE FUNCTION ev_fail_outbox_completion() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated completion failure';
                END; $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER ev_fail_outbox_completion_trigger
                BEFORE UPDATE ON property_image_deletion_outbox
                FOR EACH ROW WHEN (NEW.status = 'COMPLETED')
                EXECUTE FUNCTION ev_fail_outbox_completion()
                """);
        try {
            // The worker treats a completion-write failure as a retryable task
            // failure: the provider object is already gone and no metadata was
            // restored. The retry remains safe because deletion is idempotent.
            cleanupWorker.processDueTasks();
            assertThat(fakeStorage.objects()).doesNotContainKey(publicId);
            assertThat(onlyOutboxFor(imageId).getStatus())
                    .isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);
            assertThat(onlyOutboxFor(imageId).getLastError())
                    .isEqualTo("JpaSystemException");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS ev_fail_outbox_completion_trigger "
                    + "ON property_image_deletion_outbox");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS ev_fail_outbox_completion()");
        }

        // The provider object is now absent; the retry is idempotent and completes.
        Instant stale = Instant.now().minusSeconds(120);
        jdbcTemplate.update("""
                UPDATE property_image_deletion_outbox
                SET status = 'PENDING', attempts = 0, locked_until = NULL,
                    next_attempt_at = ?, updated_at = ?
                WHERE image_id = ?
                """, Timestamp.from(stale), Timestamp.from(stale), imageId);
        assertThat(cleanupWorker.processDueTasks()).isEqualTo(1);
        PropertyImageDeletionOutbox recovered = onlyOutboxFor(imageId);
        assertThat(recovered.getStatus()).isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(countCleanupAudit(imageId, "ALREADY_MISSING")).isEqualTo(1);
    }

    @Test
    void multipleConcurrentWorkersClaimEachTaskExactlyOnce() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID first = uploadImage(admin, fixture.draft().getId(), png(210, 210), "png");
        UUID second = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        deleteImage(admin, fixture.draft().getId(), first).andExpect(status().isNoContent());
        deleteImage(admin, fixture.draft().getId(), second).andExpect(status().isNoContent());

        PropertyImageCleanupWorker secondWorker = new PropertyImageCleanupWorker(
                outboxRepository, storageProvider, cleanupProperties, audit, transactionManager,
                task -> task.run());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (PropertyImageCleanupWorker worker : List.of(cleanupWorker, secondWorker)) {
            futures.add(executor.submit(() -> {
                start.await();
                return worker.processDueTasks();
            }));
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int claimed = 0;
        for (var future : futures) {
            claimed += future.get();
        }
        assertThat(claimed).isEqualTo(2);
        assertThat(fakeStorage.deleteCalls()).isEqualTo(2);
        assertThat(onlyOutboxFor(first).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
        assertThat(onlyOutboxFor(second).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_COMPLETED);
    }

    @Test
    void retryBackoffIsBoundedByConfiguredMaximum() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        fakeStorage.failDelete = true;
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());

        ImageCleanupProperties bounded = new ImageCleanupProperties();
        bounded.setEnabled(false);
        bounded.setBackoffMs(1000);
        bounded.setMaxBackoffMs(2500);
        bounded.setMaxAttempts(10);
        PropertyImageCleanupWorker boundedWorker = new PropertyImageCleanupWorker(
                outboxRepository, storageProvider, bounded, audit, transactionManager,
                task -> task.run());

        long[] expectedDelay = {1000L, 2000L, 2500L, 2500L};
        for (long expected : expectedDelay) {
            jdbcTemplate.update("""
                    UPDATE property_image_deletion_outbox
                    SET locked_until = NULL, next_attempt_at = now()
                    WHERE image_id = ?
                    """, imageId);
            Instant before = Instant.now();
            assertThat(boundedWorker.processDueTasks()).isEqualTo(1);
            long observed = java.time.Duration.between(
                    before, onlyOutboxFor(imageId).getNextAttemptAt()).toMillis();
            assertThat(observed).isBetween(expected - 1500L, expected + 1500L);
        }
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_FAILED);
    }

    @Test
    void outboxRowsAreRetainedAndBlockUnexpectedPropertyDeletion() throws Exception {
        Fixture fixture = seedFixture();
        Session admin = login(fixture.admin().getEmail());
        UUID imageId = uploadImage(admin, fixture.draft().getId(), png(220, 220), "png");
        deleteImage(admin, fixture.draft().getId(), imageId).andExpect(status().isNoContent());
        assertThat(imageRepository.findById(imageId)).isEmpty();
        assertThat(onlyOutboxFor(imageId).getStatus())
                .isEqualTo(PropertyImageDeletionOutbox.STATUS_PENDING);

        jdbcTemplate.update("DELETE FROM property_residential_details WHERE property_id = ?",
                fixture.draft().getId());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM properties WHERE id = ?", fixture.draft().getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("property_image_deletion_outbox");
    }

    // ------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        FakeImageStorage fakeImageStorage() {
            return new FakeImageStorage();
        }
    }

    static final class FakeImageStorage implements ImageStorage {
        private final Map<String, StoredObject> objects = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> deletedPublicIds = new CopyOnWriteArrayList<>();
        private volatile boolean failUpload;
        private volatile boolean failDelete;
        private volatile boolean timeoutDelete;
        private volatile boolean missingAsset;
        private volatile int failDeleteCalls;
        private volatile int uploadCalls;
        private volatile int deleteCalls;
        private volatile StoredObject lastObject;

        void reset() {
            objects.clear();
            deletedPublicIds.clear();
            failUpload = false;
            failDelete = false;
            timeoutDelete = false;
            missingAsset = false;
            failDeleteCalls = 0;
            uploadCalls = 0;
            deleteCalls = 0;
            lastObject = null;
        }

        @Override
        public StoredImage upload(
                UUID propertyId, String storagePublicId, SanitizedImage image) {
            uploadCalls++;
            if (failUpload) {
                throw new IllegalStateException("simulated provider failure with api_key=SECRET");
            }
            // Mimic the production adapter: the service supplies only a
            // server-generated UUID and the adapter owns the full public ID.
            String publicId = "estatevista/properties/" + propertyId + "/" + storagePublicId;
            String key = publicId + "." + image.extension();
            StoredObject object = new StoredObject(
                    key,
                    publicId,
                    image.bytes().clone(),
                    image.contentType(),
                    "https://images.example.invalid/" + key);
            objects.put(publicId, object);
            lastObject = object;
            return new StoredImage("TEST_FAKE", publicId, object.url());
        }

        @Override
        public void delete(String storageProvider, String storagePublicId) {
            deleteCalls++;
            deletedPublicIds.add(storagePublicId);
            if (missingAsset) {
                throw new ProviderAssetNotFoundException("simulated missing provider asset");
            }
            if (timeoutDelete) {
                throw new SimulatedProviderTimeout("simulated provider timeout");
            }
            if (failDelete || failDeleteCalls > 0) {
                if (failDeleteCalls > 0) {
                    failDeleteCalls--;
                }
                throw new IllegalStateException("simulated delete failure with api_secret=SECRET");
            }
            if (!objects.containsKey(storagePublicId)) {
                throw new ProviderAssetNotFoundException("simulated missing provider asset");
            }
            objects.remove(storagePublicId);
        }

        Map<String, StoredObject> objects() {
            return objects;
        }

        int uploadCalls() {
            return uploadCalls;
        }

        int deleteCalls() {
            return deleteCalls;
        }

        StoredObject lastObject() {
            return lastObject;
        }

        List<String> deletedPublicIds() {
            return deletedPublicIds;
        }
    }

    private static final class SimulatedProviderTimeout extends RuntimeException {
        SimulatedProviderTimeout(String message) {
            super(message);
        }
    }

    private record StoredObject(
            String key,
            String publicId,
            byte[] bytes,
            String contentType,
            String url) {
    }

    private record Fixture(
            User admin,
            User agent,
            User otherAgent,
            User buyer,
            PropertySubtype subtype,
            Property draft,
            Property published,
            Property archived,
            Property otherProperty) {
    }

    private record Session(Cookie cookie, String csrfToken) {
    }

    private Fixture seedFixture() {
        User admin = persistUser("ADMIN", "admin");
        User agent = persistUser("AGENT", "agent");
        User otherAgent = persistUser("AGENT", "other-agent");
        User buyer = persistUser("BUYER", "buyer");
        PropertySubtype subtype = persistSubtype(residentialCategory(), "Residential Flat");
        Property draft = persistProperty(admin, agent, PropertyStatus.DRAFT, null, subtype);
        Property published = persistProperty(
                admin, agent, PropertyStatus.PUBLISHED, Instant.now().minusSeconds(600), subtype);
        Property archived = persistProperty(admin, agent, PropertyStatus.ARCHIVED, null, subtype);
        Property otherProperty = persistProperty(
                admin, otherAgent, PropertyStatus.DRAFT, null, subtype);
        return new Fixture(
                admin, agent, otherAgent, buyer, subtype,
                draft, published, archived, otherProperty);
    }

    private User persistUser(String roleName, String label) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setRole(role);
        user.setFirstName("Stage45");
        user.setLastName(label);
        user.setEmail(unique(label + "@example.com").toLowerCase(Locale.ROOT));
        user.setPhone("+919876543210");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private PropertySubtype persistSubtype(PropertyCategory category, String label) {
        PropertySubtype subtype = new PropertySubtype();
        subtype.setCategory(category);
        subtype.setName(unique(label));
        subtypeRepository.saveAndFlush(subtype);
        createdSubtypeIds.add(subtype.getId());
        return subtype;
    }

    private PropertyCategory residentialCategory() {
        return categoryRepository.findById(RESIDENTIAL_CATEGORY_ID).orElseThrow();
    }

    private Property persistProperty(
            User admin, User agent, PropertyStatus status,
            Instant publishedAt, PropertySubtype subtype) {
        Property property = new Property();
        property.setTitle(unique("Image Listing"));
        property.setSlug(unique("image-listing"));
        property.setDescription("Stage 4.5 integration fixture");
        property.setCategory(subtype.getCategory());
        property.setSubtype(subtype);
        property.setListingType(ListingType.SALE);
        property.setPrice(new BigDecimal("2500000.00"));
        property.setCurrencyCode("INR");
        property.setAreaValue(new BigDecimal("1200.00"));
        property.setAreaUnit("SQ_FT");
        property.setAddressLine("1 Image Road");
        property.setLocality(unique("Locality"));
        property.setCity("Vadodara");
        property.setState("Gujarat");
        property.setCountry("India");
        property.setPostalCode("390001");
        property.setLatitude(new BigDecimal("22.3000000"));
        property.setLongitude(new BigDecimal("73.2000000"));
        property.setStatus(status);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(admin);
        property.setPublishedAt(publishedAt);
        property.setArchivedAt(status == PropertyStatus.ARCHIVED ? Instant.now() : null);
        propertyRepository.saveAndFlush(property);
        createdPropertyIds.add(property.getId());

        PropertyResidentialDetails details = new PropertyResidentialDetails();
        details.setProperty(property);
        details.setBedrooms((short) 2);
        details.setBathrooms((short) 2);
        residentialDetailsRepository.saveAndFlush(details);
        return property;
    }

    private Session anonymousSession() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(
                requireSessionCookie(csrfResult),
                csrfResult.getResponse().getHeader(CSRF_HEADER));
    }

    private UUID persistImage(Property property, boolean primary) {
        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageProvider("TEST_FAKE");
        image.setStoragePublicId("estatevista/properties/" + property.getId() + "/"
                + UUID.randomUUID());
        image.setImageUrl("https://images.example.invalid/" + UUID.randomUUID() + ".png");
        image.setSortOrder(0);
        image.setIsPrimary(primary);
        imageRepository.saveAndFlush(image);
        return image.getId();
    }

    private Session login(String email) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = requireSessionCookie(csrfResult);
        String csrfToken = csrfResult.getResponse().getHeader(CSRF_HEADER);
        assertThat(csrfToken).isNotBlank();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(csrfCookie)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = requireSessionCookie(login);
        String rotatedToken = login.getResponse().getHeader(CSRF_HEADER);
        assertThat(rotatedToken).isNotBlank();
        return new Session(sessionCookie, rotatedToken);
    }

    private ResultActions upload(
            Session session, UUID propertyId, MockMultipartFile file,
            String altText, Boolean isPrimary, Integer sortOrder) throws Exception {
        MockMultipartHttpServletRequestBuilder builder =
                multipart("/api/v1/properties/{id}/images", propertyId)
                        .file(file)
                        .cookie(session.cookie())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(CSRF_HEADER, session.csrfToken());
        if (altText != null) {
            builder.param("altText", altText);
        }
        if (isPrimary != null) {
            builder.param("isPrimary", isPrimary.toString());
        }
        if (sortOrder != null) {
            builder.param("sortOrder", sortOrder.toString());
        }
        return mockMvc.perform(builder);
    }

    private UUID uploadImage(
            Session session, UUID propertyId, byte[] bytes, String extension) throws Exception {
        return uploadImage(session, propertyId, bytes, extension, false);
    }

    private UUID uploadImage(
            Session session, UUID propertyId, byte[] bytes, String extension, boolean primary)
            throws Exception {
        MvcResult result = upload(session, propertyId,
                        file("fixture." + extension, contentType(extension), bytes),
                        null, primary ? Boolean.TRUE : null, null)
                .andExpect(status().isCreated())
                .andReturn();
        return imageId(result);
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    private static byte[] jpegWithComment(String marker) throws Exception {
        byte[] base = jpeg(240, 200);
        byte[] content = marker.getBytes(StandardCharsets.US_ASCII);
        byte[] segment = new byte[4 + content.length];
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xfe;
        int length = content.length + 2;
        segment[2] = (byte) (length >>> 8);
        segment[3] = (byte) length;
        System.arraycopy(content, 0, segment, 4, content.length);
        byte[] result = new byte[base.length + segment.length];
        System.arraycopy(base, 0, result, 0, 2);
        System.arraycopy(segment, 0, result, 2, segment.length);
        System.arraycopy(base, 2, result, 2 + segment.length, base.length - 2);
        return result;
    }

    private static byte[] pngWithClaimedDimensions(int width, int height) throws Exception {
        byte[] result = png(200, 200).clone();
        putInt(result, 16, width);
        putInt(result, 20, height);
        CRC32 crc = new CRC32();
        crc.update(result, 12, 17);
        putInt(result, 29, (int) crc.getValue());
        return result;
    }

    private static byte[] webp(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        assertThat(writers.hasNext()).as("WebP ImageIO writer is available").isTrue();
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), null);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static byte[] webpWithMetadata(String marker) throws Exception {
        byte[] base = webp(240, 200);
        byte[] payload = marker.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeAscii(chunk, "EXIF");
        writeInt(chunk, payload.length);
        chunk.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            chunk.write(0);
        }
        byte[] chunkBytes = chunk.toByteArray();

        // WebP metadata chunks belong after the image data.
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeAscii(result, "RIFF");
        writeInt(result, 4 + base.length - 12 + chunkBytes.length);
        writeAscii(result, "WEBP");
        result.write(base, 12, base.length - 12);
        result.writeBytes(chunkBytes);
        return result.toByteArray();
    }

    private static byte[] animatedWebp() {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        writeAscii(result, "RIFF");
        writeInt(result, 0);
        writeAscii(result, "WEBP");

        // VP8X with the animation flag set, followed by the animation chunks.
        writeAscii(result, "VP8X");
        writeInt(result, 10);
        result.write(0x02);
        for (int index = 0; index < 9; index++) {
            result.write(0);
        }
        writeAscii(result, "ANIM");
        writeInt(result, 6);
        for (int index = 0; index < 6; index++) {
            result.write(0);
        }
        writeAscii(result, "ANMF");
        writeInt(result, 16);
        for (int index = 0; index < 16; index++) {
            result.write(0);
        }

        byte[] bytes = result.toByteArray();
        putInt(bytes, 4, bytes.length - 8);
        return bytes;
    }

    private static List<String> webpMetadataChunks(byte[] bytes) {
        List<String> names = new ArrayList<>();
        Set<String> metadata = Set.of("EXIF", "XMP ", "ICCP", "JUMB");
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String name = ascii(bytes, offset);
            long size = readInt(bytes, offset + 4) & 0xffff_ffffL;
            long padded = size + (size & 1L);
            if (size > Integer.MAX_VALUE || offset + 8L + padded > bytes.length) {
                break;
            }
            if (metadata.contains(name)) {
                names.add(name);
            }
            offset += 8 + (int) padded;
        }
        return names;
    }

    private static String ascii(byte[] bytes, int offset) {
        StringBuilder value = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            value.append((char) (bytes[offset + index] & 0xff));
        }
        return value.toString();
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        for (int index = 0; index < value.length(); index++) {
            output.write(value.charAt(index) & 0xff);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private Cookie requireSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        createdSessionIds.add(sessionIdFromCookie(cookie));
        return cookie;
    }

    private static String sessionIdFromCookie(Cookie cookie) {
        String value = cookie.getValue();
        if (value.matches("[0-9a-fA-F-]{36}")) {
            return value;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            decoded = Base64.getMimeDecoder().decode(value);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private UUID imageId(MvcResult result) throws Exception {
        String id = readData(result).at("/id").asText();
        assertThat(id).isNotBlank();
        return UUID.fromString(id);
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private ResultActions deleteImage(Session session, UUID propertyId, UUID imageId)
            throws Exception {
        return mockMvc.perform(delete("/api/v1/properties/{id}/images/{imageId}",
                        propertyId, imageId)
                        .cookie(session.cookie()).header(CSRF_HEADER, session.csrfToken()));
    }

    private PropertyImageDeletionOutbox onlyOutboxFor(UUID imageId) {
        List<PropertyImageDeletionOutbox> tasks = outboxRepository.findAll().stream()
                .filter(task -> imageId.equals(task.getImageId()))
                .toList();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private void seedBlockingOutbox(
            UUID propertyId, UUID imageId, PropertyImage image, UUID actorId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO property_image_deletion_outbox (
                    id, property_id, image_id, storage_provider, storage_public_id,
                    actor_id, status, attempts, next_attempt_at, locked_until,
                    last_error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                propertyId,
                imageId,
                image.getStorageProvider(),
                image.getStoragePublicId(),
                actorId,
                PropertyImageDeletionOutbox.STATUS_PENDING,
                0,
                Timestamp.from(now),
                null,
                null,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private long countCleanupAudit(UUID imageId, String outcome) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE action = ? AND entity_id = ? AND after_data ->> 'outcome' = ?
                """,
                Long.class,
                AuditEventService.ACTION_IMAGE_CLEANUP,
                imageId,
                outcome);
        return count == null ? 0L : count;
    }

    private long countAudit(String action, UUID entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
        return count == null ? 0L : count;
    }

    private static String unique(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private static String createJson(String title, UUID subtypeId, UUID agentId) {
        return """
                {
                  "title": "%s",
                  "description": "Stage 4.5 regression request",
                  "categoryId": "%s",
                  "subtypeId": "%s",
                  "listingType": "SALE",
                  "primaryAgentId": "%s",
                  "price": 2500000.00,
                  "currencyCode": "INR",
                  "areaValue": 1200.00,
                  "areaUnit": "SQ_FT",
                  "location": {
                    "addressLine": "1 Test Road",
                    "locality": "Regression Locality",
                    "city": "Vadodara",
                    "state": "Gujarat",
                    "country": "India",
                    "postalCode": "390001",
                    "latitude": 22.3,
                    "longitude": 73.2
                  },
                  "residentialDetails": {
                    "bedrooms": 2,
                    "bathrooms": 2,
                    "balconies": 1,
                    "floorNumber": 3,
                    "totalFloors": 10,
                    "parkingSpaces": 1,
                    "furnishingStatus": "FURNISHED"
                  }
                }
                """.formatted(title, RESIDENTIAL_CATEGORY_ID, subtypeId, agentId);
    }
}
