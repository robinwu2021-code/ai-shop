package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Fixed consumed routes keep original machine recovery distinct from current human authority. */
public interface ServiceExecutionInternalApi {
    String ENDPOINT = "SERVICE_EXECUTION";
    String PREPARE_PATH = "/internal/trade/service-execution/prepare";
    String QUERY_PATH = "/internal/trade/service-execution/query";
    String QUERY_ORIGINAL_PATH = "/internal/trade/service-execution/query-original";

    /** Human preparation must bind the complete current delegated namespace. */
    @PostExchange(PREPARE_PATH)
    InternalReply<Grant> prepare(@RequestBody InternalCall<PrepareExecution> call);

    /** Human query retains its own route and access policy. */
    @PostExchange(QUERY_PATH)
    InternalReply<Grant> query(@RequestBody InternalCall<ExecutionLookup> call);

    /** Recovery cannot substitute its machine envelope into a human route. */
    @PostExchange(QUERY_ORIGINAL_PATH)
    ExecutionOriginalProtocol.Reply<Grant> queryOriginal(
            @RequestBody ExecutionOriginalProtocol.Call<ExecutionLookup> call);
}
