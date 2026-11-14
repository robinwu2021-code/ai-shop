package ai.neargo.shop.portal.port;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.trade.service.ServiceExecutionService;

import org.junit.jupiter.api.Test;

/** Every bridge method reaches its real owner interface; no bridge result is invented. */
class ServiceExecutionPreparationPortImplTest {
    @Test
    void allFourMethodsDelegateToTheExistingOwners() {
        var execution = mock(ServiceExecutionService.class);
        var evidence = mock(ServiceExecutionEvidencePort.class);
        var stock = mock(ExecutionStockRetentionPort.class);
        var observations = mock(ExecutionStockRetentionCoordinator.class);
        var bridge =
                new ServiceExecutionPreparationPortImpl(execution, evidence, stock, observations);
        bridge.referenceOriginal(null);
        bridge.queryOriginalEvidence(null);
        var request = mock(ExecutionStockRetentionPort.RetainExecutionStock.class);
        bridge.retainStock(request);
        bridge.queryStockOriginal(null);
        verify(execution).referenceOriginal(null);
        verify(evidence).queryOriginalEvidence(null);
        verify(stock).retainV2(request);
        verify(observations, org.mockito.Mockito.times(2)).queryPreparationObservation(null);
    }
}
