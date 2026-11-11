package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.api.replenishment.ReplenishmentContracts.*;

public interface ReplenishmentQueryService {
    Checkpoint checkpoint(String ownerId);
    Outbound outbound(String ownerId, String documentNo, int lineNo);
    Inbound inbound(String ownerId, String documentNo, int lineNo);
}
