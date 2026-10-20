package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.payclient.StoredValueConsumerQueryAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer-facing stored value surface is intentionally read-only and exposes no balance-pool split. */
@Profile("api")
@RestController
public class MpStoredValueController {
    private final StoredValueRechargeAppService storedValues;

    private final StoredValueConsumerQueryAppService reads;
    private final MarketPort markets;
    private final MerchantQueryPort merchants;
    public MpStoredValueController(StoredValueRechargeAppService storedValues,
            StoredValueConsumerQueryAppService reads, MarketPort markets, MerchantQueryPort merchants) {
        this.storedValues = storedValues; this.reads=reads; this.markets=markets; this.merchants=merchants;
    }

    @GetMapping("/mp/stored-value/stores")
    public CursorPage<StoredValueConsumerViews.Store> stores(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        var ctx = context("stored-value-stores", cursor == null ? "first" : cursor);
        ContractChecks.require(size>=1 && size<=50);
        var page=storedValues.stores(ctx, new StoreQuery(ctx.actorNo(), cursor, size));
        return new CursorPage<>(page.records().stream().map(s->new StoredValueConsumerViews.Store(s.storeNo(),s.entityNo(),
                s.storeName(),s.storeStatus(),s.lastActivityType().name(),s.lastActivityAt(),s.lastActivityAt(),"CONSUMPTION".equals(s.lastActivityType().name())?"REDEMPTION":s.lastActivityType().name()))
                .toList(),page.nextCursor());
    }

    @GetMapping("/mp/stored-value/stores/{storeNo}/records")
    public StoredValueConsumerViews.Records records(@PathVariable String storeNo,
            @RequestParam(defaultValue="ALL") String type,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) String focusDocumentNo, @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int size) {
        var ctx=context("stored-value-records",storeNo);
        ContractChecks.require(startDate==null || endDate==null || !startDate.isAfter(endDate));
        var store=reads.store(ctx,storeNo);
        var market=markets.find(merchants.marketOf(store.entityNo()))
                .orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        var zone=ZoneId.of(market.timeZone());
        var from=startDate==null?null:startDate.atStartOfDay(zone).toInstant();
        var to=endDate==null?null:endDate.plusDays(1).atStartOfDay(zone).toInstant();
        var page=reads.records(ctx,storeNo,type,from,to,focusDocumentNo,cursor,size);
        return new StoredValueConsumerViews.Records(new StoredValueConsumerViews.StoreHeader(storeNo,
                store.storeName(),zone.getId()),new StoredValueConsumerViews.Filter(type,startDate,endDate),
                page.records(),page.nextCursor(),page.hasMore());
    }

    @GetMapping("/mp/stored-value/consumptions")
    public CursorPage<UsageItem> consumptions(@RequestParam String entityNo, @RequestParam(required=false) String storeNo,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        var ctx = context("stored-value-consumptions", entityNo + ":" + storeNo + ":" + cursor);
        return storedValues.consumptions(ctx,
                new ConsumptionQuery(ctx.actorNo(), entityNo, storeNo, cursor, size));
    }

    private EntitlementContext context(String request, String payload) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) throw BizException.of(ErrorCode.FORBIDDEN);
        return new EntitlementContext(user.tenantNo(), user.userNo(), ActivityCommandDeduplicator.hash(request),
                ActivityCommandDeduplicator.hash(payload));
    }
}
