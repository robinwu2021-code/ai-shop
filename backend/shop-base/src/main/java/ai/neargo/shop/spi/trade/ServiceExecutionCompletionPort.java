package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompleteExecution;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompletionReceipt;
import jakarta.validation.Valid;

/** Machine reconciliation of a source-persisted END; never authorizes human work or monetary reversal. */
public interface ServiceExecutionCompletionPort {
    CompletionReceipt complete(@Valid CompleteExecution request);
    CompletionReceipt queryOriginal(@Valid ExecutionEvidenceLookup request);
}
