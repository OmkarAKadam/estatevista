package com.estatevista.property.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PropertyAmenityIdTest {

    private final UUID propertyId = UUID.randomUUID();
    private final UUID amenityId = UUID.randomUUID();

    @Test
    void isEqualToAnotherInstanceWithTheSameValues() {
        PropertyAmenityId id = new PropertyAmenityId(propertyId, amenityId);
        PropertyAmenityId same = new PropertyAmenityId(propertyId, amenityId);

        assertThat(id).isEqualTo(same).hasSameHashCodeAs(same);
    }

    @Test
    void differsWhenEitherComponentDiffers() {
        PropertyAmenityId id = new PropertyAmenityId(propertyId, amenityId);

        assertThat(id)
                .isNotEqualTo(new PropertyAmenityId(UUID.randomUUID(), amenityId))
                .isNotEqualTo(new PropertyAmenityId(propertyId, UUID.randomUUID()));
    }

    @Test
    void isOnlyEqualToItselfAndTheSameType() {
        PropertyAmenityId id = new PropertyAmenityId(propertyId, amenityId);

        assertThat(id).isEqualTo(id).isNotEqualTo(null).isNotEqualTo("not-an-id");
    }

    @Test
    void supportsTheNoArgumentContractRequiredByJpa() {
        PropertyAmenityId id = new PropertyAmenityId();
        id.setPropertyId(propertyId);
        id.setAmenityId(amenityId);

        assertThat(id.getPropertyId()).isEqualTo(propertyId);
        assertThat(id.getAmenityId()).isEqualTo(amenityId);
        assertThat(id).isEqualTo(new PropertyAmenityId(propertyId, amenityId));
    }
}
