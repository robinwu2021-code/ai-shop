package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.CommandVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoicePoll;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keeps the FoodStationRuntimeInternalApi protocol scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface FoodStationRuntimeInternalApi {
    String ROOT = "/internal/food/station-runtime";

    record Scope(String entityNo, String storeNo, String stationNo) {}

    record Control(
            String entityNo,
            String storeNo,
            String actorNo,
            String stationNo,
            ControlReq definition,
            String key) {}

    record Poll(String entityNo, String storeNo, String actorNo, String subscriptionNo) {}

    record Receipt(
            String entityNo,
            String storeNo,
            String actorNo,
            String subscriptionNo,
            VoiceReceipt definition) {}

    @PostExchange(ROOT + "/state")
    StateVO state(@RequestBody Scope query);

    @PostExchange(ROOT + "/require-running")
    Boolean requireRunning(@RequestBody Scope query);

    @PostExchange(ROOT + "/control")
    CommandVO control(@RequestBody Control command);

    @PostExchange(ROOT + "/claim")
    VoicePoll claim(@RequestBody Poll query);

    @PostExchange(ROOT + "/acknowledge")
    CommandVO acknowledge(@RequestBody Receipt command);
}
