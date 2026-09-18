package ai.neargo.shop.marketing.group.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.entity.MktGroupMember;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupMemberMapper;
import ai.neargo.shop.spi.marketing.GroupJoinPort;
import ai.neargo.shop.spi.marketing.GroupRulePort;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * {@link GroupJoinPort} 的实现。见接口注释。
 *
 * <p>全部绕数据域：参团的是买家会话、付款回调没有会话，而团是公共内容（分享链接要能打开）。
 * 边界靠团号 / 商品号等值条件与方法内的显式校验，与 {@code GroupServiceImpl#scoped} 同一条理由。
 */
@Component
public class GroupJoinPortImpl implements GroupJoinPort {

    private static final Logger log = LoggerFactory.getLogger(GroupJoinPortImpl.class);

    private final GroupBuyMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final GroupRulePort rulePort;
    private final GoodsQueryPort goodsPort;
    private final UserQueryPort userPort;
    private final PlatformSwitchPort switchPort;

    public GroupJoinPortImpl(GroupBuyMapper groupMapper, GroupMemberMapper memberMapper,
                             GroupRulePort rulePort, GoodsQueryPort goodsPort,
                             UserQueryPort userPort, PlatformSwitchPort switchPort) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.rulePort = rulePort;
        this.goodsPort = goodsPort;
        this.userPort = userPort;
        this.switchPort = switchPort;
    }

    @Override
    public GroupQuote quote(String userNo, String groupNo, boolean openGroup, String goodsNo) {
        if (groupNo != null && !groupNo.isBlank()) {
            MktGroupBuy g = find(groupNo);
            requireJoinable(g, System.currentTimeMillis());
            if (!g.getGoodsNo().equals(goodsNo)) {
                // 团是这件货的团：带着团号买别的货，等于拿团价买任意商品
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (userNo != null && memberOf(groupNo, userNo) != null) {
                // 一人一团只能参一次 —— 否则「还差 N 人」会被同一个人刷满
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return new GroupQuote(g.getGroupNo(), g.getActivityNo(), g.getGoodsNo(), g.getSkuNo(),
                    g.getEntityNo(), nz(g.getGroupPriceMinor()), g.getPickupNo());
        }
        if (!openGroup) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        GoodsQueryPort.SkuSnapshot snap = requireOnSale(goodsNo);
        GroupRulePort.GroupRule rule = requireRule(snap);
        // 开团报价不钉规格：团按商品开，规格随下单那一行
        return new GroupQuote(null, rule.activityNo(), snap.goodsNo(), null,
                snap.merchantNo(), rule.groupPriceMinor(), null);
    }

    @Override
    public String bind(String userNo, GroupQuote quote, String pickupNo) {
        if (quote.groupNo() != null) {
            // 预览到提交之间团可能刚好过期 / 被散：落库前再判一次，付款前就让买家知道
            requireJoinable(find(quote.groupNo()), System.currentTimeMillis());
            return quote.groupNo();
        }
        return openBuyerGroup(userNo, quote.goodsNo(), pickupNo).getGroupNo();
    }

    /**
     * 买家开团：价、人数、时限一律取这件货在跑的拼团活动（设计 D7）。
     *
     * <p><b>不落成员</b>：发起人付了款才算第一人（{@link #onPaid}）。
     * 也给 C 端「送到我家」那条开团路径（{@code GroupService#createGroupBuy}）共用 ——
     * 两条建团路径各算一次价，迟早一条读活动、一条读商品。
     */
    public MktGroupBuy openBuyerGroup(String userNo, String goodsNo, String pickupNo) {
        GoodsQueryPort.SkuSnapshot snap = requireOnSale(goodsNo);
        GroupRulePort.GroupRule rule = requireRule(snap);

        MktGroupBuy g = new MktGroupBuy();
        g.setGroupNo(BizKey.next(BizKey.GROUP_BUY));
        g.setInitiatorUserNo(userNo);
        g.setActivityNo(rule.activityNo());
        g.setGoodsNo(snap.goodsNo());
        g.setSkuNo(null);
        g.setEntityNo(snap.merchantNo());
        g.setTitle(snap.title());
        g.setCover(snap.cover());
        g.setGroupPriceMinor(rule.groupPriceMinor());
        g.setOriginPriceMinor(snap.price());
        g.setMinCount(Math.max(2, rule.minCount()));
        g.setJoinedCount(0);
        g.setPickupNo(pickupNo);
        // 与商家开团走同一个审核开关：两条建团路径少管一条，开关就等于没开
        g.setStatus(switchPort.bool("group.audit", false) ? MktGroupBuy.PENDING : MktGroupBuy.OPEN);
        g.setEndAt(System.currentTimeMillis() + Duration.ofHours(rule.groupHours()).toMillis());
        DataScopeContext.executeWithoutScope(() -> groupMapper.insert(g));
        return g;
    }

    @Override
    public PaidOutcome onPaid(String groupNo, String subOrderNo, String userNo) {
        MktGroupMember replay = DataScopeContext.executeWithoutScope(() ->
                memberMapper.selectOne(Wrappers.<MktGroupMember>lambdaQuery()
                        .eq(MktGroupMember::getSubOrderNo, subOrderNo).last("limit 1")));
        if (replay != null) {
            return PaidOutcome.ALREADY;
        }
        MktGroupBuy g = DataScopeContext.executeWithoutScope(() ->
                groupMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        if (g == null || MktGroupBuy.FAILED.equals(g.getStatus())) {
            return PaidOutcome.CLOSED;
        }
        if (memberOf(groupNo, userNo) != null) {
            // 同一个人在同一个团里付了第二单：货照发，人数不变（成团按人算，不按单算）
            return PaidOutcome.ALREADY;
        }
        /*
         * 先占人数、再落成员。**占人数是一条带状态条件的 UPDATE**：
         * 与到期任务 / 散团并发时，先把团改成 FAILED 的那一边赢，这里 0 行 → 退款。
         *
         * 已成团（FORMED）仍然收：两个人同时为最后一个名额下了单，
         * 后付的那位不该因为慢了几秒被退款 —— 团价照给，人数照加。
         */
        int moved = DataScopeContext.executeWithoutScope(() -> groupMapper.update(null,
                Wrappers.<MktGroupBuy>lambdaUpdate()
                        /*
                         * ⚠️ **status 必须写在 joined_count 前面。** MySQL / MariaDB 的单表 UPDATE
                         * 从左往右求值，后一个赋值读到的是前一个**改过的**值；H2 按标准读旧值。
                         * 反过来写的话生产上差一人就成团，而 H2 测试全绿。
                         */
                        .setSql("status = CASE WHEN status = 'OPEN' AND joined_count + 1 >= min_count"
                                + " THEN 'FORMED' ELSE status END")
                        .setSql("joined_count = joined_count + 1")
                        .eq(MktGroupBuy::getGroupNo, groupNo)
                        .in(MktGroupBuy::getStatus,
                                List.of(MktGroupBuy.OPEN, MktGroupBuy.FORMED, MktGroupBuy.PENDING))));
        if (moved == 0) {
            return PaidOutcome.CLOSED;
        }
        MktGroupMember m = new MktGroupMember();
        m.setGroupNo(groupNo);
        m.setUserNo(userNo);
        m.setSubOrderNo(subOrderNo);
        m.setNickname(userPort.find(userNo).map(UserQueryPort.UserBrief::nickname).orElse(null));
        m.setJoinedAt(System.currentTimeMillis());
        try {
            DataScopeContext.executeWithoutScope(() -> memberMapper.insert(m));
        } catch (DuplicateKeyException e) {
            // 并发重放撞上唯一键：把刚占的人数还回去，结果与「先查到了」一致
            DataScopeContext.executeWithoutScope(() -> groupMapper.update(null,
                    Wrappers.<MktGroupBuy>lambdaUpdate()
                            .setSql("joined_count = joined_count - 1")
                            .eq(MktGroupBuy::getGroupNo, groupNo)));
            log.info("[拼团] 成员已存在 group={} sub={}", groupNo, subOrderNo);
            return PaidOutcome.ALREADY;
        }
        return PaidOutcome.JOINED;
    }

    private MktGroupBuy find(String groupNo) {
        MktGroupBuy g = DataScopeContext.executeWithoutScope(() ->
                groupMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    /** 能参 = 进行中且没过截止。已成团也不再接新人：成了就是成了，再参是另一个团 */
    private static void requireJoinable(MktGroupBuy g, long now) {
        if (!MktGroupBuy.OPEN.equals(g.getStatus()) || nz(g.getEndAt()) <= now) {
            throw BizException.of(ErrorCode.GROUP_CLOSED);
        }
    }

    private MktGroupMember memberOf(String groupNo, String userNo) {
        return DataScopeContext.executeWithoutScope(() ->
                memberMapper.selectOne(Wrappers.<MktGroupMember>lambdaQuery()
                        .eq(MktGroupMember::getGroupNo, groupNo)
                        .eq(MktGroupMember::getUserNo, userNo).last("limit 1")));
    }

    private GoodsQueryPort.SkuSnapshot requireOnSale(String goodsNo) {
        GoodsQueryPort.SkuSnapshot snap = goodsPort.snapshotOfGoods(goodsNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!snap.onSale()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return snap;
    }

    private GroupRulePort.GroupRule requireRule(GoodsQueryPort.SkuSnapshot snap) {
        GroupRulePort.GroupRule rule = rulePort.activeRuleFor(snap.merchantNo(), snap.goodsNo())
                .orElseThrow(() -> BizException.of(ErrorCode.ORDER_STATE_ILLEGAL));
        if (rule.groupPriceMinor() <= 0 || rule.groupPriceMinor() >= snap.price()) {
            // 成团价不低于原价 = 一个不省钱的团。建活动那一步没拦住就在这儿拦
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        return rule;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
