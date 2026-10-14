package ai.neargo.shop.promotion.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.spi.marketing.PeriodPort;
import ai.neargo.shop.spi.marketing.SaleGatePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「仅活动」商品的可买判定。口径见 {@link SaleGatePort}。
 *
 * <p><b>「活动进行中」与算价同一把尺</b>：{@code isActiveAt} 且还有余量
 * （{@code ActivityPricingServiceImpl.liveByGoods} 用的就是这两条）。
 * 两边各判一套的下场是「这里说能买、那边不给活动价」—— 一件仅活动的货按原价卖了出去。
 *
 * <p>集单不看活动行本身，看 {@link PeriodPort#viewFor}：活动在跑不等于此刻有一期可下
 * （当天截了单、下一期还没开），而下单时挂期用的也是这条路。
 *
 * <p>读活动表一律 {@code executeWithoutScope}：调用方可能在 C 端（没有商家上下文）
 * 也可能在 B 端（商家上下文 ≠ 平台活动的主体）—— 带域查询在这两种情况下都会静默返回空。
 */
@Component
public class SaleGatePortImpl implements SaleGatePort {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityGoodsMapper goodsMapper;
    private final PeriodPort periodPort;

    public SaleGatePortImpl(ActivityMapper activityMapper, ActivityGoodsMapper goodsMapper, PeriodPort periodPort) {
        this.activityMapper = activityMapper;
        this.goodsMapper = goodsMapper;
        this.periodPort = periodPort;
    }

    @Override
    public Live live(Collection<String> goodsNos, long now) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Live.none();
        }
        List<PmtActivityGoods> refs = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .in(PmtActivityGoods::getRefNo, goodsNos)));
        if (refs.isEmpty()) {
            return Live.none();
        }
        List<String> activityNos = refs.stream().map(PmtActivityGoods::getActivityNo).distinct().toList();
        Map<String, PmtActivity> byNo = DataScopeContext.executeWithoutScope(() ->
                        activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                                .in(PmtActivity::getActivityNo, activityNos)))
                .stream().collect(Collectors.toMap(PmtActivity::getActivityNo, Function.identity(), (a, b) -> a));

        Set<String> direct = new HashSet<>();
        Set<String> any = new HashSet<>();
        for (PmtActivityGoods ref : refs) {
            PmtActivity a = byNo.get(ref.getActivityNo());
            if (a == null || !a.isActiveAt(now, MARKET_ZONE) || !a.hasQuotaLeft()) {
                continue;
            }
            String goodsNo = ref.getRefNo();
            String trigger = a.getTriggerType();
            if (PmtActivity.TRIGGER_GROUP.equals(trigger)) {
                // 拼团：只开「开团 / 参团」这条路，普通下单不开 —— 这正是要拦的单买
                any.add(goodsNo);
            } else if (PmtActivity.TRIGGER_CUTOFF.equals(trigger)) {
                // 集单：此刻要真有一期可下（当天截了单、下一期未开时活动仍是 RUNNING）
                if (periodPort.viewFor(a.getEntityNo(), goodsNo, now).isPresent()) {
                    direct.add(goodsNo);
                    any.add(goodsNo);
                }
            } else if (PmtActivity.BENEFIT_PRICE.equals(a.getBenefitType())
                    || PmtActivity.BENEFIT_GIFT.equals(a.getBenefitType())) {
                // 特价 / 买赠：点名了这件货，走的就是普通下单
                direct.add(goodsNo);
                any.add(goodsNo);
            }
            // 其余（满减 / 立减 / 组合 / 送券）是整单级，不让一件仅活动的货变得可买
        }
        return new Live(Set.copyOf(direct), Set.copyOf(any));
    }
}
