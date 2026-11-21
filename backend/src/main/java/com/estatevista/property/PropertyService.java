package com.estatevista.property;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;
import com.estatevista.property.dto.AmenityResponse;
import com.estatevista.property.dto.CommercialDetailsRequest;
import com.estatevista.property.dto.CreatePropertyRequest;
import com.estatevista.property.dto.PagedResponse;
import com.estatevista.property.dto.PropertyDetailResponse;
import com.estatevista.property.dto.PropertyLocationRequest;
import com.estatevista.property.dto.PropertyLocationResponse;
import com.estatevista.property.dto.PropertySummaryResponse;
import com.estatevista.property.dto.PropertyUpdateRequest;
import com.estatevista.property.dto.PublicAgentResponse;
import com.estatevista.property.dto.ResidentialDetailsRequest;
import com.estatevista.property.entity.Amenity;
import com.estatevista.property.entity.ListingType;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyAmenity;
import com.estatevista.property.entity.PropertyAmenityId;
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
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 4.2 property CRUD service.
 *
 * <p>All DTO mapping and lazy-association access happens inside a transaction
 * because {@code spring.jpa.open-in-view} is disabled. Public reads are filtered
 * to {@code PUBLISHED} in repository specifications before pagination, and no
 * JPA entity is ever returned to a controller.</p>
 */
@Service
@Transactional(readOnly = true)
public class PropertyService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int PUBLIC_DEFAULT_SIZE = 12;
    private static final int MANAGEMENT_DEFAULT_SIZE = 20;
    private static final int MAX_SLUG_ATTEMPTS = 5;
    private static final int MAX_SLUG_LENGTH = 220;
    private static final String CATEGORY_RESIDENTIAL = "RESIDENTIAL";
    private static final String CATEGORY_COMMERCIAL = "COMMERCIAL";
    private static final String ROLE_AGENT = "AGENT";

    private final PropertyRepository propertyRepository;
    private final PropertyCategoryRepository categoryRepository;
    private final PropertySubtypeRepository subtypeRepository;
    private final PropertyResidentialDetailsRepository residentialDetailsRepository;
    private final PropertyCommercialDetailsRepository commercialDetailsRepository;
    private final PropertyAmenityRepository propertyAmenityRepository;
    private final AmenityRepository amenityRepository;
    private final PropertyImageRepository imageRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;
    private final AuditEventService audit;

    public PropertyService(
            PropertyRepository propertyRepository,
            PropertyCategoryRepository categoryRepository,
            PropertySubtypeRepository subtypeRepository,
            PropertyResidentialDetailsRepository residentialDetailsRepository,
            PropertyCommercialDetailsRepository commercialDetailsRepository,
            PropertyAmenityRepository propertyAmenityRepository,
            AmenityRepository amenityRepository,
            PropertyImageRepository imageRepository,
            UserRepository userRepository,
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources,
            AuditEventService audit) {
        this.propertyRepository = propertyRepository;
        this.categoryRepository = categoryRepository;
        this.subtypeRepository = subtypeRepository;
        this.residentialDetailsRepository = residentialDetailsRepository;
        this.commercialDetailsRepository = commercialDetailsRepository;
        this.propertyAmenityRepository = propertyAmenityRepository;
        this.amenityRepository = amenityRepository;
        this.imageRepository = imageRepository;
        this.userRepository = userRepository;
        this.accounts = accounts;
        this.resources = resources;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    @Transactional
    public PropertyDetailResponse create(
            Authentication authentication, CreatePropertyRequest request) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        resources.requireAdmin(actor);
        validateCreateRequest(request);

        var category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> notFound("categoryId"));
        var subtype = subtypeRepository
                .findByIdAndCategory_Id(request.getSubtypeId(), request.getCategoryId())
                .orElseThrow(() -> validation("subtypeId", "must belong to the selected category"));
        User agent = requireActiveAgent(request.getPrimaryAgentId());
        validateDetailShape(category.getName(),
                request.getResidentialDetails() != null,
                request.getCommercialDetails() != null,
                true);
        List<Amenity> amenities = resolveAmenities(request.getAmenityIds());

        Property property = new Property();
        property.setTitle(trimRequired(request.getTitle()));
        property.setDescription(trimRequired(request.getDescription()));
        property.setCategory(category);
        property.setSubtype(subtype);
        property.setListingType(request.getListingType());
        property.setPrice(request.getPrice());
        property.setCurrencyCode(request.getCurrencyCode().trim());
        property.setAreaValue(request.getAreaValue());
        property.setAreaUnit(trimToNull(request.getAreaUnit()));
        applyLocation(property, request.getLocation(), true);
        property.setStatus(PropertyStatus.DRAFT);
        property.setPrimaryAgent(agent);
        property.setCreatedBy(referenceUser(actor.id()));
        property.setUpdatedBy(null);
        property.setPublishedAt(null);
        property.setArchivedAt(null);
        property.setSlug(generateSlug(request.getTitle()));

        propertyRepository.saveAndFlush(property);
        saveCategoryDetails(property, category.getName(),
                request.getResidentialDetails(), request.getCommercialDetails(), true);
        saveAmenities(property, amenities);

        audit.recordPropertyCreated(property.getId(), actor.id());
        return toDetail(property, amenities);
    }

    @Transactional
    public PropertyDetailResponse update(
            UUID propertyId, Authentication authentication,
            PropertyUpdateRequest request, boolean fullReplacement) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        Property property = requireProperty(propertyId);
        resources.requirePropertyManagement(actor, propertyId);
        rejectArchived(property);

        String categoryName = property.getCategory().getName();
        if (fullReplacement) {
            requireFullUpdateFields(request);
        }
        validateUpdateRequest(request, property, categoryName);

        if (request.hasField("title")) {
            property.setTitle(trimRequired(request.getTitle()));
        }
        if (request.hasField("description")) {
            property.setDescription(trimRequired(request.getDescription()));
        }
        if (request.hasField("listingType")) {
            property.setListingType(request.getListingType());
        }
        if (request.hasField("price")) {
            property.setPrice(request.getPrice());
        }
        if (request.hasField("currencyCode")) {
            property.setCurrencyCode(request.getCurrencyCode().trim());
        }
        if (request.hasField("areaValue")) {
            property.setAreaValue(request.getAreaValue());
        }
        if (request.hasField("areaUnit")) {
            property.setAreaUnit(trimToNull(request.getAreaUnit()));
        }
        if (request.getLocation() != null) {
            applyLocation(property, request.getLocation(), fullReplacement);
        }
        if (fullReplacement || request.hasField("residentialDetails")
                || request.hasField("commercialDetails")) {
            validateDetailShape(categoryName,
                    request.getResidentialDetails() != null,
                    request.getCommercialDetails() != null,
                    fullReplacement);
            saveCategoryDetails(property, categoryName,
                    request.getResidentialDetails(), request.getCommercialDetails(), false);
        }

        List<Amenity> amenities;
        if (request.hasField("amenityIds")) {
            amenities = resolveAmenities(request.getAmenityIds());
            replaceAmenities(property, amenities);
        } else {
            amenities = loadAmenities(property);
        }

        property.setUpdatedBy(referenceUser(actor.id()));
        propertyRepository.saveAndFlush(property);

        audit.recordPropertyUpdated(property.getId(), actor.id());
        return toDetail(property, amenities);
    }

    @Transactional
    public PropertyDetailResponse archive(UUID propertyId, Authentication authentication) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        resources.requireAdmin(actor);
        Property property = requireProperty(propertyId);
        if (property.getStatus() == PropertyStatus.ARCHIVED) {
            throw conflict("Property is already archived");
        }

        property.setStatus(PropertyStatus.ARCHIVED);
        property.setArchivedAt(Instant.now());
        property.setUpdatedBy(referenceUser(actor.id()));
        propertyRepository.saveAndFlush(property);

        audit.recordPropertyArchived(property.getId(), actor.id());
        return toDetail(property, loadAmenities(property));
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PropertyDetailResponse get(UUID propertyId, Authentication authentication) {
        var account = accounts.resolve(authentication);
        Property property;
        if (account.isEmpty()) {
            property = propertyRepository
                    .findByIdAndStatus(propertyId, PropertyStatus.PUBLISHED)
                    .orElseThrow(PropertyService::notFound);
        } else {
            property = requireProperty(propertyId);
            AuthenticatedAccount trusted = account.get();
            boolean manager = trusted.role() == PlatformRole.ADMIN
                    || (trusted.role() == PlatformRole.AGENT
                        && isAssigned(property, trusted.id()));
            if (property.getStatus() != PropertyStatus.PUBLISHED && !manager) {
                throw notFound();
            }
        }
        return toDetail(property, loadAmenities(property));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySummaryResponse> search(
            String keyword, UUID categoryId, UUID subtypeId, String listingType,
            BigDecimal minPrice, BigDecimal maxPrice, String city, String locality,
            Integer minBedrooms, Integer maxBedrooms, List<UUID> amenityIds,
            String sort, int page, int size) {
        validateRange(minPrice, maxPrice, "minPrice", "maxPrice");
        validateRange(minBedrooms, maxBedrooms, "minBedrooms", "maxBedrooms");
        List<UUID> amenities = deduplicateIds(amenityIds, "amenityIds");
        ListingType parsedListingType = parseListingType(listingType);
        Pageable pageable = pageable(page, size, publicSort(sort));
        Specification<Property> spec = publishedSpecification(keyword, categoryId, subtypeId,
                parsedListingType, minPrice, maxPrice, city, locality,
                minBedrooms, maxBedrooms, amenities);
        return PagedResponse.of(summarize(propertyRepository.findAll(spec, pageable)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySummaryResponse> recent(int page, int size) {
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "publishedAt")
                .and(Sort.by(Sort.Direction.DESC, "createdAt"))
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<Property> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), PropertyStatus.PUBLISHED),
                cb.isNotNull(root.get("publishedAt")));
        return PagedResponse.of(summarize(propertyRepository.findAll(spec, pageable)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySummaryResponse> related(UUID propertyId, int page, int size) {
        Property source = propertyRepository
                .findByIdAndStatus(propertyId, PropertyStatus.PUBLISHED)
                .orElseThrow(PropertyService::notFound);
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<Property> spec = relatedSpecification(source);
        return PagedResponse.of(summarize(propertyRepository.findAll(spec, pageable)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySummaryResponse> listAgent(
            Authentication authentication, int page, int size, String status) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        resources.requireAgent(actor);
        PropertyStatus parsedStatus = parseStatus(status);
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<Property> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("primaryAgent").get("id"), actor.id()));
            if (parsedStatus != null) {
                predicates.add(cb.equal(root.get("status"), parsedStatus));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PagedResponse.of(summarize(propertyRepository.findAll(spec, pageable)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PropertySummaryResponse> listAdmin(
            Authentication authentication, int page, int size, String status,
            UUID primaryAgentId, UUID categoryId, String city, String keyword) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        resources.requireAdmin(actor);
        PropertyStatus parsedStatus = parseStatus(status);
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Specification<Property> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (parsedStatus != null) {
                predicates.add(cb.equal(root.get("status"), parsedStatus));
            }
            if (primaryAgentId != null) {
                predicates.add(cb.equal(root.get("primaryAgent").get("id"), primaryAgentId));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (hasText(city)) {
                predicates.add(cb.equal(cb.lower(root.<String>get("city")),
                        city.trim().toLowerCase(Locale.ROOT)));
            }
            if (hasText(keyword)) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), pattern),
                        cb.like(cb.lower(root.<String>get("city")), pattern),
                        cb.like(cb.lower(root.<String>get("locality")), pattern)));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(Predicate[]::new));
        };
        return PagedResponse.of(summarize(propertyRepository.findAll(spec, pageable)));
    }

    // ------------------------------------------------------------------
    // Repository specifications
    // ------------------------------------------------------------------

    private static Specification<Property> publishedSpecification(
            String keyword, UUID categoryId, UUID subtypeId, ListingType listingType,
            BigDecimal minPrice, BigDecimal maxPrice, String city, String locality,
            Integer minBedrooms, Integer maxBedrooms, List<UUID> amenityIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Visibility is the first SQL predicate, before pagination and counting.
            predicates.add(cb.equal(root.get("status"), PropertyStatus.PUBLISHED));

            if (hasText(keyword)) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("title")), pattern),
                        cb.like(cb.lower(root.<String>get("description")), pattern),
                        cb.like(cb.lower(root.<String>get("city")), pattern),
                        cb.like(cb.lower(root.<String>get("locality")), pattern)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            if (subtypeId != null) {
                predicates.add(cb.equal(root.get("subtype").get("id"), subtypeId));
            }
            if (listingType != null) {
                predicates.add(cb.equal(root.get("listingType"), listingType));
            }
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("price"), minPrice));
            }
            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("price"), maxPrice));
            }
            if (hasText(city)) {
                predicates.add(cb.equal(cb.lower(root.<String>get("city")),
                        city.trim().toLowerCase(Locale.ROOT)));
            }
            if (hasText(locality)) {
                predicates.add(cb.equal(cb.lower(root.<String>get("locality")),
                        locality.trim().toLowerCase(Locale.ROOT)));
            }
            if (minBedrooms != null || maxBedrooms != null) {
                Subquery<UUID> bedroomQuery = query.subquery(UUID.class);
                Root<PropertyResidentialDetails> detail =
                        bedroomQuery.from(PropertyResidentialDetails.class);
                List<Predicate> detailPredicates = new ArrayList<>();
                detailPredicates.add(cb.equal(detail.get("propertyId"), root.get("id")));
                if (minBedrooms != null) {
                    detailPredicates.add(cb.ge(detail.<Short>get("bedrooms"),
                            minBedrooms.shortValue()));
                }
                if (maxBedrooms != null) {
                    detailPredicates.add(cb.le(detail.<Short>get("bedrooms"),
                            maxBedrooms.shortValue()));
                }
                bedroomQuery.select(detail.<UUID>get("propertyId"));
                bedroomQuery.where(detailPredicates.toArray(Predicate[]::new));
                predicates.add(cb.exists(bedroomQuery));
            }
            for (UUID amenityId : amenityIds) {
                Subquery<Integer> amenityQuery = query.subquery(Integer.class);
                Root<PropertyAmenity> link = amenityQuery.from(PropertyAmenity.class);
                amenityQuery.select(cb.literal(1));
                amenityQuery.where(
                        cb.equal(link.get("property").get("id"), root.get("id")),
                        cb.equal(link.get("amenity").get("id"), amenityId));
                predicates.add(cb.exists(amenityQuery));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<Property> relatedSpecification(Property source) {
        UUID categoryId = source.getCategory().getId();
        UUID subtypeId = source.getSubtype().getId();
        String locality = source.getLocality();
        String city = source.getCity();
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), PropertyStatus.PUBLISHED));
            predicates.add(cb.notEqual(root.get("id"), source.getId()));
            List<Predicate> matches = new ArrayList<>();
            matches.add(cb.equal(root.get("category").get("id"), categoryId));
            matches.add(cb.equal(root.get("subtype").get("id"), subtypeId));
            if (hasText(locality)) {
                matches.add(cb.equal(cb.lower(root.<String>get("locality")),
                        locality.trim().toLowerCase(Locale.ROOT)));
            }
            if (hasText(city)) {
                matches.add(cb.equal(cb.lower(root.<String>get("city")),
                        city.trim().toLowerCase(Locale.ROOT)));
            }
            predicates.add(cb.or(matches.toArray(Predicate[]::new)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // ------------------------------------------------------------------
    // Mapping and enrichment
    // ------------------------------------------------------------------

    private Page<PropertySummaryResponse> summarize(Page<Property> properties) {
        List<UUID> ids = properties.getContent().stream()
                .map(Property::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, PropertyImage> primaryImages = ids.isEmpty()
                ? Map.of()
                : imageRepository.findByProperty_IdInAndIsPrimaryTrue(ids).stream()
                        .collect(Collectors.toMap(
                                image -> image.getProperty().getId(),
                                Function.identity(),
                                (first, second) -> first));
        Map<UUID, PropertyResidentialDetails> residential = ids.isEmpty()
                ? Map.of()
                : residentialDetailsRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(
                                PropertyResidentialDetails::getPropertyId,
                                Function.identity(),
                                (first, second) -> first));
        return properties.map(property -> toSummary(
                property,
                primaryImages.get(property.getId()),
                residential.get(property.getId())));
    }

    private static PropertySummaryResponse toSummary(
            Property property, PropertyImage primaryImage,
            PropertyResidentialDetails residential) {
        return new PropertySummaryResponse(
                property.getId(),
                property.getTitle(),
                property.getSlug(),
                property.getCategory().getName(),
                property.getSubtype().getName(),
                property.getListingType(),
                property.getPrice(),
                property.getCurrencyCode(),
                property.getCity(),
                property.getLocality(),
                property.getAreaValue(),
                property.getAreaUnit(),
                residential == null ? null : residential.getBedrooms(),
                residential == null ? null : residential.getBathrooms(),
                primaryImage == null ? null : primaryImage.getImageUrl(),
                property.getStatus());
    }

    private PropertyDetailResponse toDetail(Property property, List<Amenity> amenities) {
        UUID id = property.getId();
        PropertyResidentialDetails residential =
                residentialDetailsRepository.findById(id).orElse(null);
        PropertyCommercialDetails commercial =
                commercialDetailsRepository.findById(id).orElse(null);
        String primaryImageUrl = imageRepository
                .findByProperty_IdInAndIsPrimaryTrue(List.of(id)).stream()
                .findFirst()
                .map(PropertyImage::getImageUrl)
                .orElse(null);
        User agent = property.getPrimaryAgent();

        return new PropertyDetailResponse(
                id,
                property.getTitle(),
                property.getSlug(),
                property.getDescription(),
                property.getCategory().getName(),
                property.getSubtype().getName(),
                property.getListingType(),
                property.getPrice(),
                property.getCurrencyCode(),
                property.getAreaValue(),
                property.getAreaUnit(),
                new PropertyLocationResponse(
                        property.getAddressLine(),
                        property.getLocality(),
                        property.getCity(),
                        property.getState(),
                        property.getCountry(),
                        property.getPostalCode(),
                        property.getLatitude(),
                        property.getLongitude()),
                residential == null ? null : new com.estatevista.property.dto.ResidentialDetailsResponse(
                        residential.getBedrooms(),
                        residential.getBathrooms(),
                        residential.getBalconies(),
                        residential.getFloorNumber(),
                        residential.getTotalFloors(),
                        residential.getParkingSpaces(),
                        residential.getFurnishingStatus()),
                commercial == null ? null : new com.estatevista.property.dto.CommercialDetailsResponse(
                        commercial.getParkingSpaces(),
                        commercial.getFloorNumber(),
                        commercial.getTotalFloors(),
                        commercial.getWashrooms(),
                        commercial.getPowerBackup(),
                        commercial.getSuitableFor()),
                amenities.stream()
                        .map(amenity -> new AmenityResponse(amenity.getId(), amenity.getName()))
                        .toList(),
                agent == null ? null : new PublicAgentResponse(
                        agent.getId(), agent.getFirstName(), agent.getLastName()),
                primaryImageUrl,
                property.getStatus(),
                property.getPublishedAt(),
                property.getCreatedAt(),
                property.getUpdatedAt(),
                property.getArchivedAt());
    }

    private List<Amenity> loadAmenities(Property property) {
        List<UUID> ids = propertyAmenityRepository.findByProperty_Id(property.getId()).stream()
                .map(link -> link.getAmenity().getId())
                .toList();
        return resolveAmenities(ids);
    }

    private List<Amenity> resolveAmenities(List<UUID> requestedIds) {
        List<UUID> ids = deduplicateIds(requestedIds, "amenityIds");
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Amenity> found = amenityRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            throw notFound("amenityIds");
        }
        Map<UUID, Amenity> byId = found.stream()
                .collect(Collectors.toMap(Amenity::getId, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }

    private void saveAmenities(Property property, List<Amenity> amenities) {
        List<PropertyAmenity> links = amenities.stream()
                .map(amenity -> {
                    PropertyAmenity link = new PropertyAmenity();
                    link.setId(new PropertyAmenityId(property.getId(), amenity.getId()));
                    link.setProperty(property);
                    link.setAmenity(amenity);
                    return link;
                })
                .toList();
        propertyAmenityRepository.saveAll(links);
    }

    private void replaceAmenities(Property property, List<Amenity> amenities) {
        propertyAmenityRepository.deleteAll(
                propertyAmenityRepository.findByProperty_Id(property.getId()));
        saveAmenities(property, amenities);
    }

    private void saveCategoryDetails(
            Property property, String categoryName,
            ResidentialDetailsRequest residentialRequest,
            CommercialDetailsRequest commercialRequest,
            boolean allFields) {
        if (CATEGORY_RESIDENTIAL.equals(categoryName) && residentialRequest != null) {
            PropertyResidentialDetails details = residentialDetailsRepository
                    .findById(property.getId())
                    .orElseGet(() -> {
                        PropertyResidentialDetails created = new PropertyResidentialDetails();
                        created.setProperty(property);
                        return created;
                    });
            if (allFields || residentialRequest.hasField("bedrooms")) {
                details.setBedrooms(residentialRequest.getBedrooms());
            }
            if (allFields || residentialRequest.hasField("bathrooms")) {
                details.setBathrooms(residentialRequest.getBathrooms());
            }
            if (allFields || residentialRequest.hasField("balconies")) {
                details.setBalconies(residentialRequest.getBalconies());
            }
            if (allFields || residentialRequest.hasField("floorNumber")) {
                details.setFloorNumber(residentialRequest.getFloorNumber());
            }
            if (allFields || residentialRequest.hasField("totalFloors")) {
                details.setTotalFloors(residentialRequest.getTotalFloors());
            }
            if (allFields || residentialRequest.hasField("parkingSpaces")) {
                details.setParkingSpaces(residentialRequest.getParkingSpaces());
            }
            if (allFields || residentialRequest.hasField("furnishingStatus")) {
                details.setFurnishingStatus(trimToNull(residentialRequest.getFurnishingStatus()));
            }
            residentialDetailsRepository.save(details);
        } else if (CATEGORY_COMMERCIAL.equals(categoryName) && commercialRequest != null) {
            PropertyCommercialDetails details = commercialDetailsRepository
                    .findById(property.getId())
                    .orElseGet(() -> {
                        PropertyCommercialDetails created = new PropertyCommercialDetails();
                        created.setProperty(property);
                        return created;
                    });
            if (allFields || commercialRequest.hasField("parkingSpaces")) {
                details.setParkingSpaces(commercialRequest.getParkingSpaces());
            }
            if (allFields || commercialRequest.hasField("floorNumber")) {
                details.setFloorNumber(commercialRequest.getFloorNumber());
            }
            if (allFields || commercialRequest.hasField("totalFloors")) {
                details.setTotalFloors(commercialRequest.getTotalFloors());
            }
            if (allFields || commercialRequest.hasField("washrooms")) {
                details.setWashrooms(commercialRequest.getWashrooms());
            }
            if (allFields || commercialRequest.hasField("powerBackup")) {
                details.setPowerBackup(commercialRequest.getPowerBackup());
            }
            if (allFields || commercialRequest.hasField("suitableFor")) {
                details.setSuitableFor(trimToNull(commercialRequest.getSuitableFor()));
            }
            commercialDetailsRepository.save(details);
        }
    }

    private static void applyLocation(
            Property property, PropertyLocationRequest location, boolean allFields) {
        if (allFields || location.hasField("addressLine")) {
            property.setAddressLine(trimToNull(location.getAddressLine()));
        }
        if (allFields || location.hasField("locality")) {
            property.setLocality(trimToNull(location.getLocality()));
        }
        if (allFields || location.hasField("city")) {
            property.setCity(trimRequired(location.getCity()));
        }
        if (allFields || location.hasField("state")) {
            property.setState(trimRequired(location.getState()));
        }
        if (allFields || location.hasField("country")) {
            property.setCountry(trimRequired(location.getCountry()));
        }
        if (allFields || location.hasField("postalCode")) {
            property.setPostalCode(trimToNull(location.getPostalCode()));
        }
        if (allFields || location.hasField("latitude")) {
            property.setLatitude(location.getLatitude());
        }
        if (allFields || location.hasField("longitude")) {
            property.setLongitude(location.getLongitude());
        }
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static void validateCreateRequest(CreatePropertyRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        validateText(request.getTitle(), "title", 200, errors);
        validateText(request.getDescription(), "description", 10000, errors);
        validateCurrency(request.getCurrencyCode(), errors);
        validateAreaPair(request.getAreaValue(), trimToNull(request.getAreaUnit()), errors);
        PropertyLocationRequest location = request.getLocation();
        if (location == null) {
            errors.add(fieldError("location", "is required"));
        } else {
            validateRequiredLocation(location, errors);
            validateCoordinatePair(location.getLatitude(), location.getLongitude(), errors);
        }
        validateAmenityIds(request.getAmenityIds(), errors);
        throwIfInvalid(errors);
    }

    private static void validateUpdateRequest(
            PropertyUpdateRequest request, Property property, String categoryName) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request.hasField("title")) {
            validateText(request.getTitle(), "title", 200, errors);
        }
        if (request.hasField("description")) {
            validateText(request.getDescription(), "description", 10000, errors);
        }
        if (request.hasField("currencyCode")) {
            validateCurrency(request.getCurrencyCode(), errors);
        }
        BigDecimal areaValue = request.hasField("areaValue")
                ? request.getAreaValue() : property.getAreaValue();
        String areaUnit = request.hasField("areaUnit")
                ? trimToNull(request.getAreaUnit()) : property.getAreaUnit();
        validateAreaPair(areaValue, areaUnit, errors);

        PropertyLocationRequest location = request.getLocation();
        BigDecimal latitude = property.getLatitude();
        BigDecimal longitude = property.getLongitude();
        if (location != null) {
            if (location.hasField("latitude")) {
                latitude = location.getLatitude();
            }
            if (location.hasField("longitude")) {
                longitude = location.getLongitude();
            }
        }
        validateCoordinatePair(latitude, longitude, errors);
        validateAmenityIds(request.getAmenityIds(), errors);
        throwIfInvalid(errors);
    }

    private static void requireFullUpdateFields(PropertyUpdateRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        requirePresent(request.hasField("title"), "title", errors);
        requirePresent(request.hasField("description"), "description", errors);
        requirePresent(request.hasField("listingType"), "listingType", errors);
        requirePresent(request.hasField("price"), "price", errors);
        requirePresent(request.hasField("currencyCode"), "currencyCode", errors);
        PropertyLocationRequest location = request.getLocation();
        if (location == null) {
            errors.add(fieldError("location", "is required"));
        } else {
            requirePresent(location.hasField("city"), "location.city", errors);
            requirePresent(location.hasField("state"), "location.state", errors);
            requirePresent(location.hasField("country"), "location.country", errors);
        }
        throwIfInvalid(errors);
    }

    private static void validateText(
            String value, String field, int maxLength, List<FieldErrorDetail> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(fieldError(field, "must not be blank"));
        } else if (value.trim().length() > maxLength) {
            errors.add(fieldError(field, "must be at most " + maxLength + " characters"));
        }
    }

    private static void validateRequiredLocation(
            PropertyLocationRequest location, List<FieldErrorDetail> errors) {
        validateText(location.getCity(), "location.city", 100, errors);
        validateText(location.getState(), "location.state", 100, errors);
        validateText(location.getCountry(), "location.country", 100, errors);
    }

    private static void validateAreaPair(
            BigDecimal areaValue, String areaUnit, List<FieldErrorDetail> errors) {
        boolean hasValue = areaValue != null;
        boolean hasUnit = hasText(areaUnit);
        if (hasValue && !hasUnit) {
            errors.add(fieldError("areaUnit", "is required when areaValue is present"));
        } else if (!hasValue && hasUnit) {
            errors.add(fieldError("areaValue", "is required when areaUnit is present"));
        }
    }

    private static void validateCoordinatePair(
            BigDecimal latitude, BigDecimal longitude, List<FieldErrorDetail> errors) {
        boolean hasLatitude = latitude != null;
        boolean hasLongitude = longitude != null;
        if (hasLatitude != hasLongitude) {
            errors.add(fieldError("location", "latitude and longitude must be supplied together"));
            return;
        }
        if (hasLatitude) {
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                errors.add(fieldError("location.latitude", "must be between -90 and 90"));
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                errors.add(fieldError("location.longitude", "must be between -180 and 180"));
            }
        }
    }

    private static void validateCurrency(String code, List<FieldErrorDetail> errors) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            errors.add(fieldError("currencyCode", "must be an uppercase ISO 4217 alpha-3 code"));
            return;
        }
        try {
            Currency currency = Currency.getInstance(code);
            if (!code.equals(currency.getCurrencyCode())
                    || !Currency.getAvailableCurrencies().contains(currency)) {
                errors.add(fieldError("currencyCode",
                        "must be an active ISO 4217 alpha-3 code"));
            }
        } catch (IllegalArgumentException exception) {
            errors.add(fieldError("currencyCode",
                    "must be an active ISO 4217 alpha-3 code"));
        }
    }

    private static void validateDetailShape(
            String categoryName, boolean hasResidential, boolean hasCommercial,
            boolean required) {
        if (CATEGORY_RESIDENTIAL.equals(categoryName)) {
            if (hasCommercial) {
                throw validation("commercialDetails", "is not valid for a RESIDENTIAL property");
            }
            if (required && !hasResidential) {
                throw validation("residentialDetails", "is required for a RESIDENTIAL property");
            }
        } else if (CATEGORY_COMMERCIAL.equals(categoryName)) {
            if (hasResidential) {
                throw validation("residentialDetails", "is not valid for a COMMERCIAL property");
            }
            if (required && !hasCommercial) {
                throw validation("commercialDetails", "is required for a COMMERCIAL property");
            }
        } else {
            throw validation("categoryId", "is not a supported property category");
        }
    }

    private static void validateAmenityIds(
            List<UUID> amenityIds, List<FieldErrorDetail> errors) {
        if (amenityIds == null) {
            return;
        }
        if (amenityIds.stream().anyMatch(Objects::isNull)) {
            errors.add(fieldError("amenityIds", "must not contain null values"));
        }
    }

    private static void validateRange(
            Comparable<?> min, Comparable<?> max, String minField, String maxField) {
        if (min != null && max != null && compare(min, max) > 0) {
            throw validation(minField, "must be less than or equal to " + maxField);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable<?> first, Comparable<?> second) {
        return ((Comparable) first).compareTo(second);
    }

    private static void requirePresent(boolean present, String field,
            List<FieldErrorDetail> errors) {
        if (!present) {
            errors.add(fieldError(field, "is required for a full replacement"));
        }
    }

    private static void throwIfInvalid(List<FieldErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    errors);
        }
    }

    private User requireActiveAgent(UUID agentId) {
        User agent = userRepository.findById(agentId)
                .orElseThrow(() -> notFound("primaryAgentId"));
        if (agent.getRole() == null
                || !ROLE_AGENT.equals(agent.getRole().getName())
                || agent.getAccountStatus() != AccountStatus.ACTIVE) {
            throw validation("primaryAgentId", "must reference an ACTIVE user with the AGENT role");
        }
        return agent;
    }

    private static boolean isAssigned(Property property, UUID accountId) {
        return property.getPrimaryAgent() != null
                && accountId.equals(property.getPrimaryAgent().getId());
    }

    private static void rejectArchived(Property property) {
        if (property.getStatus() == PropertyStatus.ARCHIVED) {
            throw conflict("Archived properties cannot be modified");
        }
    }

    private Property requireProperty(UUID propertyId) {
        return propertyRepository.findById(propertyId).orElseThrow(PropertyService::notFound);
    }

    private User referenceUser(UUID userId) {
        return userRepository.getReferenceById(userId);
    }

    private String generateSlug(String title) {
        String base = title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "property";
        }
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String suffix = attempt == 0 ? "" : "-" + attempt;
            String candidate = base;
            if (candidate.length() + suffix.length() > MAX_SLUG_LENGTH) {
                candidate = candidate.substring(0, MAX_SLUG_LENGTH - suffix.length());
                candidate = candidate.replaceAll("-+$", "");
            }
            if (candidate.isEmpty()) {
                candidate = "property";
            }
            candidate = candidate + suffix;
            if (!propertyRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw conflict("A unique property slug could not be generated");
    }

    private static List<UUID> deduplicateIds(List<UUID> ids, String field) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static Pageable pageable(int page, int size, Sort sort) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(fieldError("page", "must be zero or greater"));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            errors.add(fieldError("size", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        throwIfInvalid(errors);
        return PageRequest.of(page, size, sort);
    }

    private static Sort publicSort(String sort) {
        String value = sort == null || sort.isBlank() ? "newest" : sort.trim();
        return switch (value) {
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt")
                    .and(Sort.by(Sort.Direction.DESC, "id"));
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt")
                    .and(Sort.by(Sort.Direction.ASC, "id"));
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "price")
                    .and(Sort.by(Sort.Direction.ASC, "id"));
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "price")
                    .and(Sort.by(Sort.Direction.DESC, "id"));
            default -> throw validation("sort",
                    "must be one of newest, oldest, price_asc, price_desc");
        };
    }

    private static PropertyStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PropertyStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw validation("status", "must be a supported property status");
        }
    }

    private static ListingType parseListingType(String listingType) {
        if (listingType == null || listingType.isBlank()) {
            return null;
        }
        try {
            return ListingType.valueOf(listingType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw validation("listingType", "must be SALE or RENT");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimRequired(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static FieldErrorDetail fieldError(String field, String message) {
        return new FieldErrorDetail(field, message);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }

    private static ApiException notFound(String field) {
        return new ApiException(
                ErrorCode.NOT_FOUND,
                ErrorCode.NOT_FOUND.defaultMessage(),
                List.of(fieldError(field, "was not found")));
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(fieldError(field, message)));
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }
}
