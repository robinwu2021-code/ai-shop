package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record QuoteComponent(String componentNo, ComponentKind kind, Money listAmount, Money creditAmount, Money cashAmount, int cycleDays, long quantity) {}
