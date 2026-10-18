package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.mapper.PmtEffectProjectionMapper;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Store-limited staff cannot discover another store's amounts through merchant-wide totals. */
@Service
public class ActivityMarketingSummaryServiceImpl implements ActivityMarketingSummaryService {
    private final PmtEffectProjectionMapper effects;
    private final MerchantScopeFactsPort merchants;
    public ActivityMarketingSummaryServiceImpl(PmtEffectProjectionMapper effects, MerchantScopeFactsPort merchants) {
        this.effects = effects; this.merchants = merchants;
    }
    @Override public Summary summarize(ActivityActor actor, String storeNo, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).toDays() > 366) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> stores = actor.allowedStores() == null ? null : new ArrayList<>(actor.allowedStores());
        if (storeNo != null) {
            if ((stores != null && !stores.contains(storeNo))
                    || !merchants.storesOwned(actor.tenantNo(), actor.entityNo(), Set.of(storeNo))) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            stores = List.of(storeNo);
        }
        if (stores != null && stores.isEmpty()) return new Summary(from, to, List.of());
        List<Metric> metrics = effects.summary(actor.tenantNo(), actor.entityNo(), stores,
                LocalDateTime.ofInstant(from, ZoneOffset.UTC), LocalDateTime.ofInstant(to, ZoneOffset.UTC)).stream()
                .map(row -> new Metric("ORDER_DISCOUNT", row.currency(),
                        row.amountMinor().longValueExact(), row.events())).toList();
        return new Summary(from, to, metrics);
    }
}
