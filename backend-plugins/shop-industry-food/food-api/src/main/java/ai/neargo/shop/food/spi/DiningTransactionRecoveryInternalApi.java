package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.service.DiningTransactionRecoveryService.Report;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Existing FOOD token-authenticated job transport; the caller cannot supply source identities or financial commands. */
public interface DiningTransactionRecoveryInternalApi {
    String PATH = "/internal/food/transaction-recovery";
    @PostExchange(PATH) Report recoverDue(@RequestBody Scan request);
    record Scan(@JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int limit) {
        public Scan { if (limit < 1 || limit > 200) throw new IllegalArgumentException("Recovery limit must be 1..200"); }
        @JsonAnySetter public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown recovery field");
        }
    }
}
