package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimVO;
import ai.neargo.shop.food.dto.ServiceSlotContracts.CreateReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.SlotVO;

public interface ServiceSlotService {
    SlotVO create(String entity, String store, String actor, CreateReq request, String key);

    SlotVO get(String entity, String store, String slot);

    ClaimVO reserve(String entity, String store, String actor, ClaimReq request, String key);

    ClaimVO transition(
            String entity,
            String store,
            String actor,
            String claim,
            long version,
            String action,
            String key);

    /** Uses the persisted claim, not client supplied schedule. */
    boolean dispatchDue(String entity, String store, String sourceNo);
}
