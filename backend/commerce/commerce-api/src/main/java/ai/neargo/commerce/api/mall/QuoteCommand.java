package ai.neargo.commerce.api.mall;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record QuoteCommand(StoreRef store, List<QuoteLine> lines, String currency) {}
