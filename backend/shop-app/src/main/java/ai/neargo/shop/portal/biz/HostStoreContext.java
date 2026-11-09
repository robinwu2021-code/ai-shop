package ai.neargo.shop.portal.biz;

import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.Optional;

/**
 * 门店经营上下文（{@code GET /biz/stores/{storeNo}/context}）的宿主视图：可移植 commerce 的 {@link StoreContext}
 * 原样平铺，再加 B 端建店核验要的宿主字段（TDD-B端建店默认经营市场 AC6，Flutter 契约 CurrentStoreCommercialContext）。
 *
 * <p>门店名、地址、经营市场是宿主的事实，不进可移植模块；取不到就为空，不编造。行业与子业态取门店存的码，
 * 没存时按默认映射等于经营类型。
 */
public record HostStoreContext(
        @JsonUnwrapped StoreContext context,
        String storeName,
        String address,
        String market,
        String currency,
        Integer currencyScale,
        String timeZone,
        String industryCode,
        String subIndustryCode) {

    static HostStoreContext of(StoreContext context, Optional<MerchantQueryPort.StoreProfile> profile,
                               Optional<MarketPort.MarketBrief> market) {
        var p = profile.orElse(null);
        var m = market.orElse(null);
        String type = context.businessTypeCode();
        return new HostStoreContext(context,
                p == null ? null : p.name(),
                p == null ? null : p.address(),
                p == null ? null : p.market(),
                m == null ? null : m.currency(),
                m == null ? null : m.currencyScale(),
                m == null ? null : m.timeZone(),
                // 建店存了行业码（V643，TDD-子行业与能力端验收 B6）就用存的，否则按默认映射等于经营类型
                p != null && p.industryCode() != null ? p.industryCode() : type,
                p != null && p.subIndustryCode() != null ? p.subIndustryCode() : type);
    }
}
