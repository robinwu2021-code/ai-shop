package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

/** Operations are idempotent by original orderComponentNo; inventory authority stays in the host. */
public interface HardwareInventoryPort {
 InventoryOutcome reserve(InventoryCommand command);
  InventoryOutcome query(InventoryQuery query);
  InventoryOutcome release(InventoryQuery query);
  InventoryOutcome ship(InventoryCommand command);

}
