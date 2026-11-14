package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompleteExecution;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompletionReceipt;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Routes completion to the same Core unit owner as PREPARE and START_ACK. */
@Component
@Validated
public class ServiceExecutionCompletionPortImpl implements ServiceExecutionCompletionPort {
    private final ServiceExecutionService service;

    public ServiceExecutionCompletionPortImpl(ServiceExecutionService service) { this.service = service; }

    @Override
    public CompletionReceipt complete(CompleteExecution request) { return service.completeExecution(request); }

    @Override
    public CompletionReceipt queryOriginal(ExecutionEvidenceLookup request) {
        return service.queryCompletionOriginal(request);
    }
}
