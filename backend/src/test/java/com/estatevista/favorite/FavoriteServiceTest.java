package com.estatevista.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.favorite.repository.FavoriteRepository;
import com.estatevista.favorite.service.FavoriteService;
import com.estatevista.favorite.service.FavoriteService.AddResult;
import com.estatevista.property.PropertySummaryMapper;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.user.entity.AccountStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

/** Deterministic business-rule tests; all persistence and identity sources are mocked. */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    private static final UUID BUYER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROPERTY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_PROPERTY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private FavoriteRepository favorites;
    @Mock
    private PropertyRepository properties;
    @Mock
    private PropertySummaryMapper summaryMapper;
    @Mock
    private AccountAuthorizationService accounts;
    @Mock
    private ResourceAuthorizationService resources;
    @Mock
    private Authentication authentication;
    @InjectMocks
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        AuthenticatedAccount account = AuthenticatedAccount.of(
                BUYER_ID, PlatformRole.BUYER, AccountStatus.ACTIVE);
        lenient().when(accounts.requireActive(authentication)).thenReturn(account);
        lenient().when(summaryMapper.mapById(any())).thenReturn(Map.of());
    }

    @Test
    void addCreatesFavoriteOnlyForPublishedProperty() {
        when(properties.findByIdForUpdate(PROPERTY_ID))
                .thenReturn(Optional.of(property(PROPERTY_ID, PropertyStatus.PUBLISHED)));
        when(favorites.insertIgnoringDuplicate(any(UUID.class), eq(BUYER_ID), eq(PROPERTY_ID)))
                .thenReturn(1);

        AddResult result = service.add(authentication, PROPERTY_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.data().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(result.data().isFavorite()).isTrue();
        verify(favorites).insertIgnoringDuplicate(any(UUID.class), eq(BUYER_ID), eq(PROPERTY_ID));
    }

    @Test
    void addTreatsUniqueConstraintAbsorbedDuplicateAsExistingSuccess() {
        when(properties.findByIdForUpdate(PROPERTY_ID))
                .thenReturn(Optional.of(property(PROPERTY_ID, PropertyStatus.PUBLISHED)));
        when(favorites.insertIgnoringDuplicate(any(UUID.class), eq(BUYER_ID), eq(PROPERTY_ID)))
                .thenReturn(0);
        when(favorites.existsByBuyer_IdAndProperty_Id(BUYER_ID, PROPERTY_ID)).thenReturn(true);

        AddResult result = service.add(authentication, PROPERTY_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.data().isFavorite()).isTrue();
        verify(favorites).insertIgnoringDuplicate(any(UUID.class), eq(BUYER_ID), eq(PROPERTY_ID));
    }

    @Test
    void addChecksPublicationBeforeDuplicateSuccessAndDoesNotInsertForNonPublicProperty() {
        when(properties.findByIdForUpdate(PROPERTY_ID))
                .thenReturn(Optional.of(property(PROPERTY_ID, PropertyStatus.DRAFT)));

        assertThatThrownBy(() -> service.add(authentication, PROPERTY_ID))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(favorites, never()).insertIgnoringDuplicate(
                any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void addReturnsGenericNotFoundForMissingProperty() {
        when(properties.findByIdForUpdate(PROPERTY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(authentication, PROPERTY_ID))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(favorites);
    }

    @Test
    void removeIsBuyerScopedAndIdempotent() {
        when(favorites.deleteByBuyer_IdAndProperty_Id(BUYER_ID, PROPERTY_ID)).thenReturn(0L);

        service.remove(authentication, PROPERTY_ID);
        service.remove(authentication, PROPERTY_ID);

        verify(favorites, org.mockito.Mockito.times(2))
                .deleteByBuyer_IdAndProperty_Id(BUYER_ID, PROPERTY_ID);
    }

    @Test
    void listSupportsOnlyNewestAndOldestWithDeterministicTiebreak() {
        when(favorites.findPageByBuyer(eq(BUYER_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(), invocation.getArgument(1), 0));

        service.list(authentication, 0, 12, "newest");
        service.list(authentication, 0, 12, "oldest");

        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(favorites, org.mockito.Mockito.times(2))
                .findPageByBuyer(eq(BUYER_ID), pageables.capture());
        Pageable newest = pageables.getAllValues().get(0);
        Pageable oldest = pageables.getAllValues().get(1);
        assertThat(newest.getPageNumber()).isZero();
        assertThat(newest.getPageSize()).isEqualTo(12);
        assertThat(newest.getSort().getOrderFor("createdAt").getDirection().isDescending()).isTrue();
        assertThat(newest.getSort().getOrderFor("id").getDirection().isDescending()).isTrue();
        assertThat(oldest.getSort().getOrderFor("createdAt").getDirection().isAscending()).isTrue();
        assertThat(oldest.getSort().getOrderFor("id").getDirection().isAscending()).isTrue();
    }

    @Test
    void listRejectsInvalidPageSizeAndSort() {
        assertThatThrownBy(() -> service.list(authentication, -1, 12, "newest"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.list(authentication, 0, 0, "newest"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(authentication, 0, 101, "newest"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(authentication, 0, 12, "price_asc"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(authentication, 0, 12, " "))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verifyNoInteractions(favorites);
    }

    @Test
    void individualStatusUsesPublishedFavoriteStateOnly() {
        when(favorites.existsByBuyer_IdAndProperty_IdAndProperty_Status(
                BUYER_ID, PROPERTY_ID, PropertyStatus.PUBLISHED)).thenReturn(false);

        var result = service.status(authentication, PROPERTY_ID);

        assertThat(result.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(result.isFavorite()).isFalse();
    }

    @Test
    void batchStatusValidatesDeDuplicatesAndPreservesFirstOccurrenceOrder() {
        String raw = PROPERTY_ID + "," + OTHER_PROPERTY_ID + "," + PROPERTY_ID;
        when(favorites.findPublishedPropertyIds(
                BUYER_ID, PropertyStatus.PUBLISHED, List.of(PROPERTY_ID, OTHER_PROPERTY_ID)))
                .thenReturn(List.of(PROPERTY_ID));

        var result = service.batchStatus(authentication, raw);

        assertThat(result.favorites()).hasSize(2);
        assertThat(result.favorites().stream().map(entry -> entry.propertyId()).toList())
                .containsExactly(PROPERTY_ID, OTHER_PROPERTY_ID);
        assertThat(result.favorites().stream().map(entry -> entry.isFavorite()).toList())
                .containsExactly(true, false);
    }

    @Test
    void batchStatusRejectsMoreThanOneHundredSuppliedValuesBeforeDeDuplication() {
        String raw = String.join(",", java.util.Collections.nCopies(101, PROPERTY_ID.toString()));

        assertThatThrownBy(() -> service.batchStatus(authentication, raw))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verifyNoInteractions(favorites);
    }

    @Test
    void batchStatusRequiresCanonicalLowercaseUuids() {
        assertThatThrownBy(() -> service.batchStatus(
                authentication, "abcdefab-cdef-abcd-efab-cdefabcdefab".toUpperCase(Locale.ROOT)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.batchStatus(authentication, "not-a-uuid"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.batchStatus(authentication, "1-1-1-1-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.batchStatus(authentication, " "))
                .isInstanceOf(ApiException.class);
    }

    private static Property property(UUID id, PropertyStatus status) {
        Property property = new Property();
        property.setId(id);
        property.setStatus(status);
        return property;
    }
}
