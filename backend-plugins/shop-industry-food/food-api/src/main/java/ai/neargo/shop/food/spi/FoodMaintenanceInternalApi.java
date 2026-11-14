package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.service.FoodMaintenanceService.Report;
import ai.neargo.shop.food.service.FoodMaintenanceService.Task;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Existing FOOD token-authenticated job transport; the caller names a fixed task and a bounded batch only. */
public interface FoodMaintenanceInternalApi {
    String PATH = "/internal/food/maintenance";
    @PostExchange(PATH) Report run(@RequestBody Run request);
    record Run(Task task, @JsonDeserialize(using = InventoryReservationPort.IntegerValue.class) int limit) {
        public Run {
            if (task == null) throw new IllegalArgumentException("Maintenance task is required");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("Maintenance limit must be 1..200");
        }
        @JsonAnySetter public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown maintenance field");
        }
    }
}
