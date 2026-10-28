package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.product.service.ExecutionStockRetentionService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import org.springframework.stereotype.Component;

/** Single exported aggregate provider. B1 component validates frozen PLATFORM authority before any mutation. */
@Component
public final class ExecutionStockRetentionPortImpl implements ExecutionStockRetentionPort {
    private final ExecutionStockRetentionService platform;
    public ExecutionStockRetentionPortImpl(ExecutionStockRetentionService platform) {
        this.platform = platform;
    }

    @Override
    public Receipt retain(RetainExecutionStock request) {
        return platform.retain(request);
    }

    @Override
    public Receipt queryOriginal(RetentionLookup request) {
        return platform.queryOriginal(request);
    }

    @Override
    public Receipt releaseAfterNoEffect(ReleaseAfterNoEffect request) {
        return platform.releaseAfterNoEffect(request);
    }

    @Override
    public Receipt acknowledgeIssued(AcknowledgeIssued request) {
        return platform.acknowledgeIssued(request);
    }
}
