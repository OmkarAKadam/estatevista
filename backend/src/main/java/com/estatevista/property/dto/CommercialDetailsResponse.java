package com.estatevista.property.dto;

/** Safe projection of the existing commercial detail row. */
public record CommercialDetailsResponse(
        Short parkingSpaces,
        Short floorNumber,
        Short totalFloors,
        Short washrooms,
        Boolean powerBackup,
        String suitableFor) {
}
