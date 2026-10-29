package ai.neargo.shop.beauty.service;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;

/** Local application orchestration is distinct from both cross-owner execution SPIs. */
public interface ServiceTaskExecutionService {
    /** Persist the authentic original before quantity-owner IO; preparation is never actual service start. */
    ExecutionPreparation prepareTask(FreezeStart request);

    /** Reconcile one existing original under current SELF without creating a replacement intent. */
    ExecutionPreparation queryTask(StartIntentLookup request);

    /** Source SPI delegates through the application contract, retaining separate HUMAN/ORIGINAL purposes. */
    StartIntent freeze(FreezeStart request);
    StartIntent inspect(StartIntentLookup request);
    StartIntent inspectOriginal(StartIntentLookup request);

    /** Recover only persisted originals, without freezing a new intent or restoring an employee identity. */
    int recoverPending(int limit);

    record ExecutionPreparation(ExecutionBinding binding, Grant grant, String pendingReason) {}
}
