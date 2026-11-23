package com.estatevista.property.dto;

/** Safe projection of the existing residential detail row. */
public record ResidentialDetailsResponse(
        Short bedrooms,
        Short bathrooms,
        Short balconies,
        Short floorNumber,
        Short totalFloors,
        Short parkingSpaces,
        String furnishingStatus) {
}
