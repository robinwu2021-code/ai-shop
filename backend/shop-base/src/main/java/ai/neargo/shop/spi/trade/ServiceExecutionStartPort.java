package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.ExecutionStartContract.AcknowledgeResourceStart;
import ai.neargo.shop.spi.trade.ExecutionStartContract.StartAcknowledgement;
import jakarta.validation.Valid;

/** Core records actual resource effects; this machine contract never starts human participation. */
public interface ServiceExecutionStartPort {
    StartAcknowledgement markStarted(@Valid AcknowledgeResourceStart request);
    StartAcknowledgement queryStartOriginal(@Valid ExecutionEvidenceLookup request);
}
