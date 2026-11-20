package ai.neargo.shop.portal.port;

import ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.trade.service.ServiceExecutionService;

import org.springframework.stereotype.Component;

/**
 * Real HOST owners compose here; the bridge neither manufactures evidence nor accepts route
 * authority.
 */
@Component
public final class ServiceExecutionPreparationPortImpl implements ServiceExecutionPreparationPort {
    private final ServiceExecutionService execution;
    private final ServiceExecutionEvidencePort evidence;
    private final ExecutionStockRetentionPort stock;
    private final ExecutionStockRetentionCoordinator observations;

    public ServiceExecutionPreparationPortImpl(
            ServiceExecutionService execution,
            ServiceExecutionEvidencePort evidence,
            ExecutionStockRetentionPort stock,
            ExecutionStockRetentionCoordinator observations) {
        this.execution = execution;
        this.evidence = evidence;
        this.stock = stock;
        this.observations = observations;
    }

    @Override
    public ExecutionReference referenceOriginal(ExecutionLookup request) {
        return execution.referenceOriginal(request);
    }

    @Override
    public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request) {
        return evidence.queryOriginalEvidence(request);
    }

    @Override
    public ExecutionStockRetentionPreparationObservation retainStock(RetainExecutionStock request) {
        stock.retainV2(request);
        return observations.queryPreparationObservation(request.lookup());
    }

    @Override
    public ExecutionStockRetentionPreparationObservation queryStockOriginal(
            RetentionLookup request) {
        return observations.queryPreparationObservation(request);
    }
}
