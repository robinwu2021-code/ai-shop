package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.spi.marketing.GroupRulePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** {@link GroupRulePort} 的实现：从 {@code pmt_activity} 读团购规则。 */
@Component
public class GroupRulePortImpl implements GroupRulePort {

    /**
     * 排期按**市场时区**判，与活动那一侧一致。
     * 用系统时区的话，「今晚 8 点开始」在服务器上可能已经是明天。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityGoodsMapper goodsMapper;

    public GroupRulePortImpl(ActivityMapper activityMapper, ActivityGoodsMapper goodsMapper) {
        this.activityMapper = activityMapper;
        this.goodsMapper = goodsMapper;
    }

    @Override
    public Optional<GroupRule> activeRuleFor(String entityNo, String goodsNo) {
        if (entityNo == null || goodsNo == null) {
            return Optional.empty();
        }
        List<PmtActivityGoods> scopes = goodsMapper.selectList(
                Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getEntityNo, entityNo)
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .eq(PmtActivityGoods::getRefNo, goodsNo));
        long now = System.currentTimeMillis();
        for (PmtActivityGoods s : scopes) {
            PmtActivity a = activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                    .eq(PmtActivity::getActivityNo, s.getActivityNo()).last("limit 1"));
            if (a == null || !PmtActivity.TRIGGER_GROUP.equals(a.getTriggerType())) {
                continue;
            }
            /*
             * **排期判断只有 isActiveAt 一处。** 在这儿另写一份「状态是不是 RUNNING」
             * 的话，「为什么开不出团」会有两个互相说不通的答案 ——
             * 而那正是 PmtActivity 那个方法的类注释里点名要避免的事。
             */
            if (!a.isActiveAt(now, ZONE)) {
                continue;
            }
            return Optional.of(new GroupRule(a.getActivityNo(),
                    a.getTriggerQty() == null ? 2 : a.getTriggerQty(),
                    a.getBenefitAmountMinor() == null ? 0L : a.getBenefitAmountMinor()));
        }
        return Optional.empty();
    }
}
