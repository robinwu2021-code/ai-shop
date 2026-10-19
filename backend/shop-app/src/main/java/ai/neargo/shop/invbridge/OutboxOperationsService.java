package ai.neargo.shop.invbridge;

import java.util.Map;

/** Internal operations boundary. Controllers must provide authenticated actor and write authorization. */
public interface OutboxOperationsService {
    Map<String, Object> inspect(String source);
    void replay(String source, String eventNo, String handlerId, String actor, String reason, String requestId);
    void skip(String source, String eventNo, String handlerId, String actor, String reason, String requestId);
}
