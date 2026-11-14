package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Reverse inspection is a source-owner proof read and cannot freeze a new human intent. */
public interface ServiceExecutionSourceInternalApi {
    String ENDPOINT = "SERVICE_EXECUTION_SOURCE";
    String INSPECT_PATH = "/internal/beauty/service-execution-source/inspect";
    String INSPECT_ORIGINAL_PATH = "/internal/beauty/service-execution-source/inspect-original";

    /** Current delegated callers use the human-bound schema. */
    @PostExchange(INSPECT_PATH)
    InternalReply<StartIntent> inspect(@RequestBody InternalCall<StartIntentLookup> call);

    /** Recovery is restricted to one complete original binding. */
    @PostExchange(INSPECT_ORIGINAL_PATH)
    ExecutionOriginalProtocol.Reply<StartIntent> inspectOriginal(
            @RequestBody ExecutionOriginalProtocol.Call<StartIntentLookup> call);
}
