package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.CommandVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceClaim;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;

/**
 * Keeps the FoodStationRuntimeService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface FoodStationRuntimeService {
    StateVO state(String entity, String store, String station);

    void requireRunning(String entity, String store, String station);

    CommandVO control(
            String entity,
            String store,
            String actor,
            String station,
            ControlReq request,
            String key);

    VoiceClaim claim(String entity, String store, String actor, String subscription);

    CommandVO acknowledge(
            String entity, String store, String actor, String subscription, VoiceReceipt receipt);
}
