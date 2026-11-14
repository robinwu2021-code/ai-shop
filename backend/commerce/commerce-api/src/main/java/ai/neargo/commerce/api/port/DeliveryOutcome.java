package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record DeliveryOutcome(DeliveryStatus status, Optional<String> providerMessageNo, Optional<String> errorCode) {}
