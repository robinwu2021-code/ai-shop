package ai.neargo.shop.pay.points.type.impl;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Activation is a unique database claim, not a check-then-insert race. */
@Service
@Transactional("payTxManager")
public class PointTypeServiceImpl implements PointTypeService {
    private final MarketService markets;
    private final PointsConfigurationStore config;
    private final PointsReadStore read;
    private final TypeRetirementService retirement;
    public PointTypeServiceImpl(MarketService markets, PointsConfigurationStore config, PointsReadStore read,
                                TypeRetirementService retirement) {
        this.markets=markets; this.config=config; this.read=read;
        this.retirement=retirement;
    }
    @Override public PointType retire(String no,String actor) { return retirement.retire(no,actor); }
    @Override public PointType create(CreateType c, String actor) {
        if (c.typeCode()==null || c.typeCode().isBlank() || c.typeCode().length()>40 || actor==null || actor.isBlank()
                || c.rate()==null || c.deductRatioBp()<0 || c.deductRatioBp()>10000 || c.idleDays()<=0) {
            throw new IllegalArgumentException("POINTS_TYPE_INVALID");
        }
        MarketService.MarketRow market = markets.find(c.market()).filter(MarketService.MarketRow::enabled)
                .orElseThrow(() -> new IllegalArgumentException("POINTS_MARKET_UNKNOWN"));
        if (!"CN".equals(market.market()) || !"CNY".equals(market.currency())) {
            throw new IllegalArgumentException("POINTS_MARKET_UNSUPPORTED");
        }
        PointType type = new PointType(c.typeCode(), market.market(), market.currency(), market.currencyScale(),
                c.rate(), c.deductRatioBp(), c.idleDays(), PointType.Status.DRAFT);
        config.createType(type,actor);
        return type;
    }
    @Override public PointType activate(String no, String actor) {
        if (actor==null || actor.isBlank()) { throw new IllegalArgumentException("POINTS_OPERATOR_REQUIRED"); }
        config.lockTypeAndPool(no);
        PointType type = read.type(no).orElseThrow();
        config.changeTypeStatus(no, PointType.Status.DRAFT, PointType.Status.ACTIVE, type.market(), actor);
        return read.type(no).orElseThrow();
    }
    @Override public PointType drain(String no, String actor) {
        if (actor==null || actor.isBlank()) { throw new IllegalArgumentException("POINTS_OPERATOR_REQUIRED"); }
        config.lockTypeAndPool(no);
        PointType type = read.type(no).orElseThrow();
        config.changeTypeStatus(no, PointType.Status.ACTIVE, PointType.Status.DRAINING, type.market(), actor);
        return read.type(no).orElseThrow();
    }
}
