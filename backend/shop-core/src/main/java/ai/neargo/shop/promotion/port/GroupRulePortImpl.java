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

    /** 活动没配成团时限时的缺省（PRD §4.2.2：默认开团后 24 小时） */
    static final int DEFAULT_GROUP_HOURS = 24;

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
        /*
         * ★ **绕开数据域**，与算价那一侧同一条理由（见 ActivityPricingServiceImpl.live）。
         *
         * `pmt_activity` 按 entity_no 登记数据域，而开团时的会话是**商家自己**，
         * 不绕的话查出来恒为空 —— 表现是「这件货没有在跑的团购活动」，
         * **而接口成功、日志干净**。
         *
         * 2026-09-18 被闸门抓到：我先写的两条用例都直接调 service，没有请求上下文，
         * 域根本不生效，于是两条都绿；只有走真 HTTP 的那条（POST /biz/groups）
         * 才暴露出来 —— **替身太干净，盖住了真缺陷**。
         *
         * 安全边界靠下面显式的 entityNo 等值条件，不靠域：这两条查询都钉死了主体。
         */
        List<PmtActivityGoods> scopes = ai.neargo.common.data.scope.DataScopeContext
                .executeWithoutScope(() -> goodsMapper.selectList(
                        Wrappers.<PmtActivityGoods>lambdaQuery()
                                .eq(PmtActivityGoods::getEntityNo, entityNo)
                                .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                                .eq(PmtActivityGoods::getRefNo, goodsNo)));
        long now = System.currentTimeMillis();
        for (PmtActivityGoods s : scopes) {
            PmtActivity a = ai.neargo.common.data.scope.DataScopeContext
                    .executeWithoutScope(() -> activityMapper.selectOne(
                            Wrappers.<PmtActivity>lambdaQuery()
                                    .eq(PmtActivity::getActivityNo, s.getActivityNo())
                                    .eq(PmtActivity::getEntityNo, entityNo)
                                    .last("limit 1")));
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
                    a.getBenefitAmountMinor() == null ? 0L : a.getBenefitAmountMinor(),
                    a.getGroupHours() == null || a.getGroupHours() <= 0
                            ? DEFAULT_GROUP_HOURS : a.getGroupHours()));
        }
        return Optional.empty();
    }
}
