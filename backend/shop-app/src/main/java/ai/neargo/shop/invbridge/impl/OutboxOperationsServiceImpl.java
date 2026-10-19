package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.invbridge.OutboxOperationsService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;

/** Audit and state transition stay in the selected source's local transaction. */
@Service
public class OutboxOperationsServiceImpl implements OutboxOperationsService {
    private final UnifiedOutboxFacade facade;
    public OutboxOperationsServiceImpl(UnifiedOutboxFacade facade) { this.facade = facade; }
    public Map<String, Object> inspect(String source) { return facade.store(source).health(Instant.now()); }
    public void replay(String source, String event, String handler, String actor, String reason, String request) {
        facade.store(source).operate(request, event, handler, actor, reason, false, Instant.now());
        facade.signal(source);
    }
    public void skip(String source, String event, String handler, String actor, String reason, String request) {
        facade.store(source).operate(request, event, handler, actor, reason, true, Instant.now());
        facade.signal(source);
    }
}
