package ai.neargo.shop.invbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute;
import ai.neargo.shop.invbridge.impl.ExecutionStockRetentionCoordinatorImpl;
import ai.neargo.shop.invbridge.mapper.ExecutionRetentionRouteMapper;
import ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Exhausted metadata is diagnostic only and cannot become owner IO or a fabricated valid receipt.
 */
class ExecutionStockRetentionExhaustionTest {
    @SuppressWarnings("unchecked")
    @Test
    void exhaustionProducesVisibleFailureWithoutDecodeRetryMutationOrOwnerIo() {
        var routes = mock(ExecutionRetentionRouteMapper.class);
        var schema = mock(ExecutionRetentionRouteSchemaVerifier.class);
        when(schema.installation())
                .thenReturn(ExecutionRetentionRouteSchemaVerifier.Installation.INSTALLED);
        var row = new PrdExecutionRetentionRoute();
        row.setId(1L);
        row.setRevision(Long.MAX_VALUE - 1);
        row.setObservationEpoch(0L);
        row.setVersion(0L);
        row.setNextRetryAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        row.setRouteJson("{");
        when(routes.dueGroup(any(), any(), anyLong(), anyBoolean(), anyInt()))
                .thenReturn(List.of(row));
        ObjectProvider evidence = mock(ObjectProvider.class);
        ObjectProvider platform = mock(ObjectProvider.class);
        ObjectProvider inventory = mock(ObjectProvider.class);
        var coordinator =
                new ExecutionStockRetentionCoordinatorImpl(
                        routes,
                        schema,
                        evidence,
                        platform,
                        inventory,
                        mock(ExecutionStockMirrorEvidenceService.class),
                        new ObjectMapper(),
                        mock(PlatformTransactionManager.class));
        var result = coordinator.reconcileAcceptedRoutes(null, 100);
        assertEquals("RETRY_REVISION_EXHAUSTED", result.failures().getFirst().reason());
        assertEquals(0, result.receipts().size());
        assertEquals(true, result.next().exhaustedGroup());
        verify(routes, never()).deferFailure(any(), any(), any());
        verifyNoInteractions(evidence, platform, inventory);
    }
}
