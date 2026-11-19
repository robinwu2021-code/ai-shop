package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.AppOpsTicketVOs.TicketVO;

/** Issues AppOps installation enrollment tickets for configured apps (anonymous, rate limited). */
public interface AppOpsTicketService {

    TicketVO issue(String app, String publicKey, String clientIp);
}
