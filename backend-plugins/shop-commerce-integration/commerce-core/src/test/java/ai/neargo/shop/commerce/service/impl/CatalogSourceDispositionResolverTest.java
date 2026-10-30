package ai.neargo.shop.commerce.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class CatalogSourceDispositionResolverTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ChannelSourceValidationPort positive = mock(ChannelSourceValidationPort.class);
    final ChannelSourceWithdrawalProvider nativeOwner = mock(ChannelSourceWithdrawalProvider.class);
    final SourceReq source = new SourceReq(Owner.FOOD, "MENU", "REV", 0);
    final ChannelSourceWithdrawalProvider.Query query =
            new ChannelSourceWithdrawalProvider.Query("E", "S", Owner.FOOD, "MENU", "REV");
    final ChannelSourceWithdrawalProvider.Data disabled =
            new ChannelSourceWithdrawalProvider.Data(
                    query,
                    "DISABLED:2:ACTION",
                    "a".repeat(64),
                    "ACTION",
                    "b".repeat(64),
                    now.minusSeconds(1));
    CatalogSourceDispositionResolver resolver;

    @BeforeEach
    void owners() {
        when(nativeOwner.owner()).thenReturn(Owner.FOOD);
        when(nativeOwner.capture(query)).thenReturn(proof(disabled, now.plusSeconds(25)));
        when(positive.validate(any()))
                .thenAnswer(
                        call -> {
                            ChannelSourceValidationPort.Source q = call.getArgument(0);
                            return new Validation(
                                    Status.VALID,
                                    q.sourceRef(),
                                    q.sourceRevision(),
                                    "a".repeat(64),
                                    now.plusSeconds(25),
                                    "ORIGINAL_POSITIVE");
                        });
        resolver = new CatalogSourceDispositionResolver(positive, List.of(nativeOwner), clock);
    }

    ChannelSourceWithdrawalProvider.Facts proof(
            ChannelSourceWithdrawalProvider.Data data, Instant until) {
        return new ChannelSourceWithdrawalProvider.Facts(
                data, PricingSnapshotHash.of(data), now, until, "ORIGINAL_DISABLED_RECEIPT");
    }

    @Test
    void ordinaryValidationExceptionNeverFallsBackToAWithdrawalOwner() {
        doThrow(new IllegalStateException("Unknown original owner")).when(positive).validate(any());
        assertThatThrownBy(
                        () -> resolver.partition("E", "S", Owner.FOOD, List.of(source), List.of()))
                .isInstanceOf(IllegalStateException.class);
        Mockito.verify(nativeOwner, Mockito.never()).capture(any());
    }

    @Test
    void staleOrUnsupportedPositiveStatusNeverMeansEmpty() {
        for (var status : List.of(Status.STALE, Status.UNKNOWN, Status.UNSUPPORTED)) {
            doReturn(new Validation(status, null, null, null, null, null))
                    .when(positive)
                    .validate(any());
            assertThatThrownBy(
                            () ->
                                    resolver.partition(
                                            "E", "S", Owner.FOOD, List.of(source), List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
        Mockito.verify(nativeOwner, Mockito.never()).capture(any());
    }

    @Test
    void onlySeparatelyRecordedAndFreshlyMatchingDisabledProofCanRemoveSource() {
        var result = resolver.partition("E", "S", Owner.FOOD, List.of(source), List.of(disabled));
        assertThat(result.active()).isEmpty();
        assertThat(result.withdrawn()).hasSize(1);
        verifyNoInteractions(positive);
    }

    @Test
    void partialAggregateRetainsEveryActualActiveOtherSource() {
        var other = new SourceReq(Owner.FOOD, "OTHER", "OR", 1);
        var result =
                resolver.partition("E", "S", Owner.FOOD, List.of(source, other), List.of(disabled));
        assertThat(result.active()).extracting(s -> s.sourceRef()).containsExactly("OTHER");
        assertThat(result.withdrawn())
                .extracting(p -> p.data().query().sourceRef())
                .containsExactly("MENU");
    }

    @Test
    void reenabledOrMissingNativeSourceCannotReuseRecordedWithdrawal() {
        when(nativeOwner.capture(query))
                .thenThrow(new IllegalStateException("Original menu is active again"));
        assertThatThrownBy(
                        () ->
                                resolver.partition(
                                        "E", "S", Owner.FOOD, List.of(source), List.of(disabled)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(positive);
    }

    @Test
    void foreignScopeOrChangedActionCannotBorrowOldDisabledReceipt() {
        var altered =
                new ChannelSourceWithdrawalProvider.Data(
                        query,
                        "DISABLED:3:OTHER",
                        "a".repeat(64),
                        "OTHER",
                        "c".repeat(64),
                        now.minusSeconds(1));
        when(nativeOwner.capture(query)).thenReturn(proof(altered, now.plusSeconds(25)));
        assertThatThrownBy(
                        () ->
                                resolver.partition(
                                        "E", "S", Owner.FOOD, List.of(source), List.of(disabled)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredNativeEvidenceCannotAuthorizeEvenRecordedRealDisable() {
        when(nativeOwner.capture(query)).thenReturn(proof(disabled, now));
        assertThatThrownBy(
                        () ->
                                resolver.partition(
                                        "E", "S", Owner.FOOD, List.of(source), List.of(disabled)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyUnknownConfigurationAndDuplicateOwnerAreRejected() {
        assertThatThrownBy(() -> resolver.partition("E", "S", Owner.FOOD, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new CatalogSourceDispositionResolver(
                                        positive, List.of(nativeOwner, nativeOwner), clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
