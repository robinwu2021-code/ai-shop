package ai.neargo.shop.merchant.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Exercises the same owner-local guard consumed by booking, advisory and maintenance entry points. */
class ResourceOccupationGuardTest {
    private static final ResourceCommandScopeRow S = new ResourceCommandScopeRow("MAIN", "E", "S");
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 29, 0, 0);

    @Test
    void projectedUnavailableCannotReadMissingNativeTablesOrIgnoreNonemptyCommonEvidence() {
        var schema = mock(ResourceOccupationSchemaVerifier.class);
        var rows = mock(ResourceOccupationMapper.class);
        when(schema.installation()).thenReturn(ResourceOccupationSchemaVerifier.Installation.UNSUPPORTED);
        when(rows.countCommonArtifacts(S)).thenReturn(0L);
        var guard = new ResourceOccupationGuard(schema, rows);
        assertThat(guard.used(S, "R", T, empty(), 0, 0)).isZero();
        verify(rows, never()).countInvalid(S);
        verify(rows, never()).projected(S, "R", T, "OCCUPIED");
        verify(rows, never()).projected(S, "R", T, "CLEANING");
        verify(rows, never()).missing(S, "R", T, T.plusMinutes(5));
        when(rows.countCommonArtifacts(S)).thenReturn(1L);
        assertThatThrownBy(() -> guard.used(S, "R", T, empty(), 0, 0))
                .isInstanceOfSatisfying(BizException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void corruptNativeInstallationNeverFallsBackToLegacyCapacity() {
        var schema = mock(ResourceOccupationSchemaVerifier.class);
        var rows = mock(ResourceOccupationMapper.class);
        when(schema.installation()).thenReturn(ResourceOccupationSchemaVerifier.Installation.CORRUPT);
        assertThatThrownBy(() -> new ResourceOccupationGuard(schema, rows)
                .used(S, "R", T, empty(), 0, 0)).isInstanceOfSatisfying(BizException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        verifyNoInteractions(rows);
    }

    @Test
    void persistedCountersAndMissingOverlayPreserveResidualCapacityAndTruthfulOverrun() {
        var schema = mock(ResourceOccupationSchemaVerifier.class);
        var rows = mock(ResourceOccupationMapper.class);
        when(schema.installation()).thenReturn(ResourceOccupationSchemaVerifier.Installation.INSTALLED);
        when(rows.countInvalid(S)).thenReturn(0L);
        when(rows.projected(S, "R", T, "OCCUPIED")).thenReturn(1L);
        when(rows.missing(S, "R", T, T.plusMinutes(5))).thenReturn(2L);
        var bucket = empty();
        bucket.setOccupiedQty(1);
        assertThat(new ResourceOccupationGuard(schema, rows).used(S, "R", T, bucket, 1, 0)).isEqualTo(4);
        bucket.setOccupiedQty(0);
        assertThatThrownBy(() -> new ResourceOccupationGuard(schema, rows).used(S, "R", T, bucket, 1, 0))
                .isInstanceOfSatisfying(BizException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void genuineAbsentCannotIssueAnyNewColumnMapperBeforeTypedRejection() {
        var schema = mock(ResourceOccupationSchemaVerifier.class);
        var rows = mock(ResourceOccupationMapper.class);
        when(schema.installation()).thenReturn(ResourceOccupationSchemaVerifier.Installation.ABSENT);
        assertThatThrownBy(() -> new ResourceOccupationGuard(schema, rows).requireCommon(S))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(rows);
    }

    @Test
    void overflowOrLostEvidenceQueryIsTypedCommonUnavailableRatherThanZeroOrRawStorageFailure() {
        var schema = mock(ResourceOccupationSchemaVerifier.class);
        var rows = mock(ResourceOccupationMapper.class);
        when(schema.installation()).thenReturn(ResourceOccupationSchemaVerifier.Installation.INSTALLED);
        when(rows.missing(S, "R", T, T.plusMinutes(5))).thenReturn(Long.MAX_VALUE);
        var guard = new ResourceOccupationGuard(schema, rows);
        assertThatThrownBy(() -> guard.used(S, "R", T, empty(), 1, 0))
                .isInstanceOfSatisfying(BizException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
        when(rows.countInvalid(S)).thenThrow(new IllegalStateException("owner read lost"));
        assertThatThrownBy(() -> guard.inspect(S))
                .isInstanceOfSatisfying(BizException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE));
    }

    private static MchResourceBucket empty() {
        var bucket = new MchResourceBucket();
        bucket.setOccupiedQty(0);
        bucket.setCleaningQty(0);
        return bucket;
    }
}
