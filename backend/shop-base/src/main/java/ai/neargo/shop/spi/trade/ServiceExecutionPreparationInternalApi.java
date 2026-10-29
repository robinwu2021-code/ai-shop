package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Only consumed preparation routes are remotely reachable; continuation remains HOST local. */
public interface ServiceExecutionPreparationInternalApi {
    String ENDPOINT = "SERVICE_EXECUTION_PREPARATION";
    String ROOT = "/internal/trade/service-execution-preparation";
    String REFERENCE_PATH = ROOT + "/reference-original";
    String EVIDENCE_PATH = ROOT + "/query-original-evidence";
    String RETAIN_PATH = ROOT + "/retain-stock";
    String STOCK_PATH = ROOT + "/query-stock-original";

    @PostExchange(REFERENCE_PATH)
    InternalReply<ExecutionReference> referenceOriginal(
            @RequestBody InternalCall<ExecutionLookup> call);

    @PostExchange(EVIDENCE_PATH)
    EvidenceReply queryOriginalEvidence(@RequestBody EvidenceCall call);

    @PostExchange(RETAIN_PATH)
    InternalReply<ExecutionStockRetentionPreparationObservation> retainStock(
            @RequestBody InternalCall<RetainExecutionStock> call);

    @PostExchange(STOCK_PATH)
    StockReply queryStockOriginal(@RequestBody StockCall call);
}
