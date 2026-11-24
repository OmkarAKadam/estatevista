package com.estatevista.property;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.estatevista.property.dto.PropertySummaryResponse;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyResidentialDetails;
import com.estatevista.property.repository.PropertyImageRepository;
import com.estatevista.property.repository.PropertyResidentialDetailsRepository;

import org.springframework.stereotype.Component;

/**
 * Shared mapping for the approved API §7.2 public property summary.
 *
 * <p>Primary images and residential details are loaded in two bounded
 * set-based queries, so this mapper can be reused by public discovery and by
 * the favorites collection without N+1 behavior.</p>
 */
@Component
public class PropertySummaryMapper {

    private final PropertyImageRepository imageRepository;
    private final PropertyResidentialDetailsRepository residentialDetailsRepository;

    public PropertySummaryMapper(
            PropertyImageRepository imageRepository,
            PropertyResidentialDetailsRepository residentialDetailsRepository) {
        this.imageRepository = imageRepository;
        this.residentialDetailsRepository = residentialDetailsRepository;
    }

    /** Maps a collection of properties keyed by property ID. */
    public Map<UUID, PropertySummaryResponse> mapById(Collection<Property> properties) {
        List<Property> source = properties == null ? List.of() : List.copyOf(properties);
        if (source.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = source.stream()
                .map(Property::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PropertyImage> primaryImages = imageRepository
                .findByProperty_IdInAndIsPrimaryTrue(ids).stream()
                .collect(Collectors.toMap(
                        image -> image.getProperty().getId(),
                        Function.identity(),
                        (first, second) -> first));
        Map<UUID, PropertyResidentialDetails> residential = residentialDetailsRepository
                .findAllById(ids).stream()
                .collect(Collectors.toMap(
                        PropertyResidentialDetails::getPropertyId,
                        Function.identity(),
                        (first, second) -> first));

        Map<UUID, PropertySummaryResponse> result = new LinkedHashMap<>();
        for (Property property : source) {
            if (property.getId() == null) {
                continue;
            }
            result.put(property.getId(), toSummary(
                    property,
                    primaryImages.get(property.getId()),
                    residential.get(property.getId())));
        }
        return result;
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
}
