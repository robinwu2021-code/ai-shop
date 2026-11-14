package ai.neargo.commerce.api.reminder;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record NoticeQuery(StoreRef store, Optional<String> entitlementCode, Optional<NoticeStatus> status, PageQuery page) {}
