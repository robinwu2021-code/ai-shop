package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Pure public-projection controls; no owner facts or database readiness are fabricated. */
class ServiceSourceObservationTest {
    @Test
    void manualProjectionUsesItsOwnTicketAndTime() throws Exception {
        var json = JsonMapper.builder().build();
        var observation = new ServiceSourceObservation(null, null, null, json, Clock.systemUTC(), mock(ServiceExecutionFenceCoordinator.class));
        var row = new BtySourceObservation();
        Instant financialTime = Instant.parse("2026-01-01T00:00:00Z");
        Instant manualTime = financialTime.plusSeconds(20);
        row.setState("UNKNOWN");
        row.setObservationEpoch(19L);
        row.setObservedAt(LocalDateTime.ofInstant(financialTime, ZoneOffset.UTC));
        row.setAdmissionKind("MANUAL_OFFLINE");
        row.setManualEpoch(3L);
        row.setManualObservedAt(LocalDateTime.ofInstant(manualTime, ZoneOffset.UTC));
        row.setManualState("READY");
        row.setManualRevision(5L);
        row.setManualHash("a".repeat(64));
        var view = json.readTree(json.writeValueAsString(observation.manualProjection(row, true)));
        var unavailable =
                json.readTree(json.writeValueAsString(observation.manualProjection(row, false)));
        assertThat(unavailable.path("observationEpoch").asLong()).isEqualTo(3);
        assertThat(unavailable.path("observedAt").asText()).isEqualTo(manualTime.toString());
        assertThat(unavailable.path("manualState").asText()).isEqualTo("UNKNOWN");
        assertThat(unavailable.path("reasonCode").asText()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(view.path("observationEpoch").asLong())
                .as("manual public ticket must not expose financial epoch")
                .isEqualTo(3);
        assertThat(view.path("observedAt").asText()).isEqualTo(manualTime.toString());
        assertThat(view.path("manualState").asText()).isEqualTo("READY");
        assertThat(view.path("canStart").asBoolean(true)).isFalse();
        assertThat(row.getObservedAt())
                .isEqualTo(LocalDateTime.ofInstant(financialTime, ZoneOffset.UTC));
        assertThat(row.getObservationEpoch()).isEqualTo(19L);
    }

    @Test
    void unavailableLegacyPlaceholderHasNoManualTicketOrProjection() {
        var observation =
                new ServiceSourceObservation(
                        null, null, null, JsonMapper.builder().build(), Clock.systemUTC(), mock(ServiceExecutionFenceCoordinator.class));
        var legacy = new BtySourceObservation();
        legacy.setAdmissionKind("LEGACY_FUNDED");
        legacy.setState("UNKNOWN");
        legacy.setObservationEpoch(17L);
        legacy.setFailureCode("SOURCE_OBSERVATION_IN_FLIGHT");
        assertThat(observation.manualProjection(legacy, false)).isNull();
        assertThat(legacy.getManualEpoch()).isNull();
        assertThat(legacy.getObservationEpoch()).isEqualTo(17L);
        assertThat(legacy.getFailureCode()).isEqualTo("SOURCE_OBSERVATION_IN_FLIGHT");
    }

    @Test
    void financialBindingReportsManualDomainMismatchBeforeReadiness() {
        var row = new BtySourceObservation();
        row.setAdmissionKind("MANUAL_OFFLINE");
        row.setState("UNKNOWN");
        row.setObservationEpoch(8L);
        var binding =
                new ServiceSourceBinding(
                        null, null, null, null, null, null, null, null, Clock.systemUTC(), mock(ServiceExecutionFenceCoordinator.class));
        var observed = new ServiceSourceObservation.Observed(row, null, -1);
        assertThat(binding.bind(null, null, observed, "A").reason())
                .isEqualTo("SOURCE_IDENTITY_MISMATCH");
        assertThat(row.getObservationEpoch()).isEqualTo(8L);
    }

    @Test
    void fundedPlaceholderRejectionCarriesSafeDomainReasonWithoutClaiming() {
        for (String state : new String[] {"READY", "UNKNOWN"}) {
            var mapper = mock(BeautyMappers.SourceObservationMapper.class);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            var observation =
                    new ServiceSourceObservation(
                            mapper, null, manager, JsonMapper.builder().build(), Clock.systemUTC(), mock(ServiceExecutionFenceCoordinator.class));
            var row = new BtySourceObservation();
            row.setTenantNo("MAIN");
            row.setEntityNo("E");
            row.setStoreNo("S");
            row.setSourceKind("STORE_ORDER");
            row.setSourceNo("SO");
            row.setCheckoutCaller("BEAUTY");
            row.setCheckoutOperationNo("OP");
            row.setOrderNo("O");
            row.setBuyerNo("U");
            row.setDeleted(0);
            row.setVersion(4L);
            row.setObservationEpoch(7L);
            row.setState(state);
            row.setSourceRevision(3L);
            row.setSourceHash("a".repeat(64));
            row.setVerifiedRevision(3L);
            row.setVerifiedHash("a".repeat(64));
            row.setVerifiedLines("[]");
            row.setVerifiedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
            row.setObservedAt(LocalDateTime.parse("2026-01-01T00:00:01"));
            row.setFailureCode("UNKNOWN".equals(state) ? "SOURCE_UNAVAILABLE" : null);
            when(mapper.selectCanonicalForUpdate("E", "SO")).thenReturn(row);
            var before = JsonMapper.builder().build().writeValueAsString(row);
            var canonical =
                    new ServiceSourceObservation.ManualCanonical(
                            "E", "S", new CustomerParty("USER", "U", null, 1L), "OP", "O", "SO");
            assertThatThrownBy(() -> observation.claimManualPlaceholder(canonical, "A"))
                    .isInstanceOfSatisfying(
                            BizException.class,
                            failure -> {
                                assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                                assertThat(
                                                assertDoesNotThrow(
                                                        () ->
                                                                failure.getClass()
                                                                        .getMethod("reasonCode")
                                                                        .invoke(failure)))
                                        .isEqualTo("SOURCE_DOMAIN_CONFLICT");
                            });
            assertThat(JsonMapper.builder().build().writeValueAsString(row)).isEqualTo(before);
            verify(mapper, never()).completeObservation(any(), anyLong());
            verify(mapper, never()).hasAllocations(any(), any());
        }
    }
}
