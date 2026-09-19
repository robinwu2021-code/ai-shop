package ai.neargo.shop.marketing.group.impl;

import ai.neargo.shop.marketing.group.GroupService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.fulfillment.GroupPickupPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.group.dto.GroupVOs;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupPickupOrderVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteRevisionVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;
import ai.neargo.shop.marketing.group.entity.MktGroupBuy;
import ai.neargo.shop.marketing.group.entity.MktGroupMember;
import ai.neargo.shop.marketing.group.entity.MktQuote;
import ai.neargo.shop.marketing.group.entity.MktQuoteRevision;
import ai.neargo.shop.marketing.group.dto.OpsGroupVOs;
import ai.neargo.shop.marketing.group.entity.MktRequest;
import ai.neargo.shop.marketing.group.entity.MktRequestInterest;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupBuyMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.GroupMemberMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.QuoteMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.QuoteRevisionMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.RequestInterestMapper;
import ai.neargo.shop.marketing.group.mapper.GroupMappers.RequestMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 团购与求团。
 *
 * <p>ADR-003 的三件事都落在这里：
 * <ul>
 *   <li>{@link #choose} —— 选定即**锁价**（写 {@code lockedPrice} 快照）</li>
 *   <li>{@link #quote} / {@link #revise} —— 改价**留痕**，涨价单独标记</li>
 *   <li>{@link #toQuoteVO} —— 报价卡上**公示毁约次数**</li>
 * </ul>
 * 少任何一件，「报价不做事前审核」都会退化成「随便报低价钓单再涨价」。
 */
@Service
public class GroupServiceImpl implements GroupService {

    private final GroupBuyMapper groupBuyMapper;
    private final ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;
    private final GroupMemberMapper memberMapper;
    private final RequestMapper requestMapper;
    private final RequestInterestMapper interestMapper;
    private final QuoteMapper quoteMapper;
    private final QuoteRevisionMapper revisionMapper;
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final PickupQueryPort pickupPort;
    private final ai.neargo.shop.spi.user.MerchantGovernPort governPort;
    private final ObjectMapper json;

    private final GroupPickupPort groupPickupPort;
    private final FulfillmentQueryPort fulfillmentPort;
    private final GoodsQueryPort goodsPort;
    private final ai.neargo.shop.spi.marketing.GroupRulePort groupRulePort;
    private final ai.neargo.shop.spi.trade.GroupOrderPort groupOrderPort;
    private final ai.neargo.shop.marketing.group.port.GroupJoinPortImpl joinPort;
    public GroupServiceImpl(GroupBuyMapper groupBuyMapper, GroupMemberMapper memberMapper,
                            RequestMapper requestMapper, RequestInterestMapper interestMapper,
                            QuoteMapper quoteMapper, QuoteRevisionMapper revisionMapper,
                            MerchantQueryPort merchantPort, UserQueryPort userPort,
                            PickupQueryPort pickupPort,
                            ObjectMapper json,
                            GroupPickupPort groupPickupPort, FulfillmentQueryPort fulfillmentPort, GoodsQueryPort goodsPort,
                            ai.neargo.shop.spi.user.MerchantGovernPort governPort,
                            ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort,
                            ai.neargo.shop.spi.marketing.GroupRulePort groupRulePort,
                            ai.neargo.shop.spi.trade.GroupOrderPort groupOrderPort,
                            ai.neargo.shop.marketing.group.port.GroupJoinPortImpl joinPort) {
        this.groupRulePort = groupRulePort;
        this.groupOrderPort = groupOrderPort;
        this.joinPort = joinPort;
        this.switchPort = switchPort;
        this.governPort = governPort;
        this.pickupPort = pickupPort;
        this.groupPickupPort = groupPickupPort;
        this.fulfillmentPort = fulfillmentPort;
        this.goodsPort = goodsPort;
        this.groupBuyMapper = groupBuyMapper;
        this.memberMapper = memberMapper;
        this.requestMapper = requestMapper;
        this.interestMapper = interestMapper;
        this.quoteMapper = quoteMapper;
        this.revisionMapper = revisionMapper;
        this.merchantPort = merchantPort;
        this.userPort = userPort;
        this.json = json;
    }

    // ---------------------------------------------------------------- 商家团

    @Override
    public List<GroupBuyVO> groupBuyList() {
        return scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                        .in(MktGroupBuy::getStatus, List.of(MktGroupBuy.OPEN, MktGroupBuy.FORMED))
                        /*
                         * 买家开的团要等发起人付了款（成员 ≥ 1）才露出来：
                         * 下单即建团，而下了单不付的人会留下一个零人的团 —— 挂在列表里没人能懂。
                         * 商家开的团一开出来就该被看见，那是它存在的意义。
                         */
                        .and(w -> w.isNull(MktGroupBuy::getInitiatorUserNo)
                                .or().gt(MktGroupBuy::getJoinedCount, 0))
                        .orderByDesc(MktGroupBuy::getId))).stream()
                .map(g -> toGroupBuyVO(g, false)).toList();
    }

    @Override
    public java.util.Optional<GroupVOs.GoodsGroupVO> goodsGroup(String goodsNo) {
        var snap = goodsPort.snapshotOfGoods(goodsNo).filter(s -> s.onSale()).orElse(null);
        if (snap == null) {
            return java.util.Optional.empty();
        }
        var rule = groupRulePort.activeRuleFor(snap.merchantNo(), goodsNo).orElse(null);
        if (rule == null || rule.groupPriceMinor() <= 0 || rule.groupPriceMinor() >= snap.price()) {
            // 与开团同一道校验：开不出来的团，详情页就不给「开团」按钮
            return java.util.Optional.empty();
        }
        long now = System.currentTimeMillis();
        List<GroupBuyVO> open = scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getGoodsNo, goodsNo)
                        .eq(MktGroupBuy::getStatus, MktGroupBuy.OPEN)
                        .gt(MktGroupBuy::getEndAt, now)
                        // 零人的买家团不露出来，理由同 groupBuyList
                        .and(w -> w.isNull(MktGroupBuy::getInitiatorUserNo)
                                .or().gt(MktGroupBuy::getJoinedCount, 0))
                        .last("limit 20"))).stream()
                // 差人最少的在前：离成团最近的那个最值得点
                .sorted(java.util.Comparator.comparingInt(g -> nz(g.getMinCount()) - nz(g.getJoinedCount())))
                .limit(3)
                .map(g -> toGroupBuyVO(g, false)).toList();
        return java.util.Optional.of(new GroupVOs.GoodsGroupVO(goodsNo, rule.activityNo(),
                rule.groupPriceMinor(), Math.max(2, rule.minCount()), rule.groupHours(), open));
    }

    @Override
    public GroupBuyVO groupBuyDetail(String groupNo) {
        MktGroupBuy g = requireGroupBuy(groupNo);
        String userNo = SecurityUtils.currentUserNoOrNull();
        return toGroupBuyVO(g, userNo != null && findMember(groupNo, userNo) != null);
    }

    /**
     * 到期未成团的团置为 {@code FAILED}，<b>并把参团的钱退回去</b>（设计 F3 ③）。
     *
     * <p>逐团一条带状态条件的 UPDATE（OPEN → FAILED）：与付款落成员并发时，
     * 先成团的那一边赢，这里不会把刚成团的团改回失败；改成功的那一个才退款。
     *
     * <p><b>补扫</b>：近 3 天失败的团再退一遍。退款逐张独立、幂等 ——
     * 某张在分账回退上失败的，或团失败之后才付进来的，下一轮会被接住。
     */
    @Override
    public int expireOverdue(long now) {
        List<MktGroupBuy> due = scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(MktGroupBuy::getStatus, MktGroupBuy.OPEN)
                .lt(MktGroupBuy::getEndAt, now)
                .last("limit 200")));
        int failed = 0;
        for (MktGroupBuy g : due) {
            if (fail(g.getGroupNo(), List.of(MktGroupBuy.OPEN))) {
                failed++;
                groupOrderPort.refundAll(g.getGroupNo(), "拼团未成团，自动退款");
            }
        }
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(3);
        scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getStatus, MktGroupBuy.FAILED)
                        .ge(MktGroupBuy::getUpdatedAt, since)
                        .select(MktGroupBuy::getGroupNo)))
                .forEach(g -> groupOrderPort.refundAll(g.getGroupNo(), "拼团未成团，自动退款"));
        return failed;
    }

    /**
     * 把团改成 FAILED。**带状态条件**：只有 {@code from} 里的状态能改，
     * 并发时后到的一方 0 行、不退款 —— 同一个团不会被两条路径各退一次。
     * 显式写 updated_at：补扫按它找近几天失败的团，而条件更新不经过实体的自动填充。
     */
    private boolean fail(String groupNo, List<String> from) {
        return scoped(() -> groupBuyMapper.update(null, Wrappers.<MktGroupBuy>lambdaUpdate()
                .set(MktGroupBuy::getStatus, MktGroupBuy.FAILED)
                .set(MktGroupBuy::getUpdatedAt, java.time.LocalDateTime.now())
                .eq(MktGroupBuy::getGroupNo, groupNo)
                .in(MktGroupBuy::getStatus, from))) > 0;
    }

    /**
     * 旧版「直接参团」。参团已改成带团号下单、付款成功才算成员（设计 D1）——
     * 这里不再落成员行：没付钱的成员会让「还差 N 人」变少，而到期也没有钱可退。
     * 旧版 C 端收到这条会提示升级，而不是以为自己参上了。
     */
    @Override
    public GroupVOs.JoinResultVO join(String groupNo) {
        // 仍然要求登录：路由是放行的，登录与否靠这一句判 —— 匿名调用该拿 401，而不是一句「请升级」
        SecurityUtils.currentUserNo();
        throw BizException.of(ErrorCode.GROUP_JOIN_NEEDS_UPGRADE);
    }

    // ---------------------------------------------------------------- 求团

    @Override
    @Transactional
    public RequestVO createRequest(CreateRequestCommand cmd) {
        MktRequest r = new MktRequest();
        r.setRequestNo(BizKey.next(BizKey.GROUP_REQUEST));
        r.setOwnerId(SecurityUtils.currentUserNo());
        r.setTitle(cmd.title());
        r.setDescription(cmd.description());
        r.setImages(writeJson(cmd.images()));
        r.setExpectCount(Math.max(cmd.expectCount(), 1));
        r.setInterestCount(0);
        r.setStatus(MktRequest.COLLECTING);
        r.setEndAt(System.currentTimeMillis()
                + Duration.ofDays(Math.max(cmd.days(), 1)).toMillis());
        scoped(() -> requestMapper.insert(r));
        return toRequestVO(r, false);
    }

    @Override
    public List<RequestVO> requestList() {
        String userNo = SecurityUtils.currentUserNoOrNull();
        return scoped(() -> requestMapper.selectList(Wrappers.<MktRequest>lambdaQuery()
                        .ne(MktRequest::getStatus, MktRequest.CLOSED)
                        .orderByDesc(MktRequest::getId))).stream()
                .map(r -> toRequestVO(r, userNo != null && findInterest(r.getRequestNo(), userNo) != null))
                .toList();
    }

    @Override
    public RequestVO requestDetail(String requestNo) {
        MktRequest r = requireRequest(requestNo);
        String userNo = SecurityUtils.currentUserNoOrNull();
        return toRequestVO(r, userNo != null && findInterest(requestNo, userNo) != null);
    }

    @Override
    @Transactional
    public RequestVO toggleInterest(String requestNo) {
        String userNo = SecurityUtils.currentUserNo();
        MktRequest r = requireRequest(requestNo);

        MktRequestInterest existing = findInterest(requestNo, userNo);
        if (existing != null) {
            scoped(() -> interestMapper.deleteById(existing.getId()));
            r.setInterestCount(Math.max(nz(r.getInterestCount()) - 1, 0));
            scoped(() -> requestMapper.updateById(r));
            return toRequestVO(r, false);
        }
        MktRequestInterest i = new MktRequestInterest();
        i.setRequestNo(requestNo);
        i.setUserNo(userNo);
        i.setAt(System.currentTimeMillis());
        scoped(() -> interestMapper.insert(i));
        r.setInterestCount(nz(r.getInterestCount()) + 1);
        scoped(() -> requestMapper.updateById(r));
        return toRequestVO(r, true);
    }

    @Override
    public List<QuoteVO> quotes(String requestNo) {
        // 按单价升序：用户第一眼该看到最便宜的
        return scoped(() -> quoteMapper.selectList(Wrappers.<MktQuote>lambdaQuery()
                        .eq(MktQuote::getRequestNo, requestNo)
                        .eq(MktQuote::getStatus, "ACTIVE"))).stream()
                .sorted(Comparator.comparingLong(q -> nz(q.getUnitPriceMinor())))
                .map(this::toQuoteVO).toList();
    }

    @Override
    @Transactional
    public RequestVO choose(String requestNo, String quoteNo) {
        MktRequest r = requireRequest(requestNo);
        // ownerId 是团实例上的字段：只有发起人能选定（ADR-004，不是身份）
        if (!r.getOwnerId().equals(SecurityUtils.currentUserNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (r.getChosenQuoteNo() != null) {
            // 锁价即定局。要换得先关单重发 —— 允许改选等于允许发起人来回压价
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        MktQuote q = requireQuote(quoteNo);
        if (!q.getRequestNo().equals(requestNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        r.setChosenQuoteNo(quoteNo);
        // ★ 锁价：存的是**此刻的价格快照**，之后商家改价不影响这一单
        r.setLockedPrice(q.getUnitPriceMinor());
        r.setStatus(MktRequest.LOCKED);
        scoped(() -> requestMapper.updateById(r));

        q.setChosen(true);
        scoped(() -> quoteMapper.updateById(q));
        return toRequestVO(r, false);
    }

    @Override
    public List<QuoteRevisionVO> priceHistory(String requestNo) {
        return scoped(() -> revisionMapper.selectList(Wrappers.<MktQuoteRevision>lambdaQuery()
                        .eq(MktQuoteRevision::getRequestNo, requestNo)
                        .orderByAsc(MktQuoteRevision::getId))).stream()
                .map(rev -> new QuoteRevisionVO(rev.getQuoteNo(),
                        merchantNameOf(rev.getEntityNo()),
                        nz(rev.getFromPriceMinor()), nz(rev.getToPriceMinor()),
                        Boolean.TRUE.equals(rev.getRaised()), nz(rev.getAt())))
                .toList();
    }

    // ---------------------------------------------------------------- B 端报价

    @Override
    public List<RequestVO> pool() {
        return scoped(() -> requestMapper.selectList(Wrappers.<MktRequest>lambdaQuery()
                        .in(MktRequest::getStatus, List.of(MktRequest.COLLECTING, MktRequest.QUOTED))
                        .orderByDesc(MktRequest::getId))).stream()
                .map(r -> toRequestVO(r, false)).toList();
    }

    @Override
    @Transactional
    public QuoteVO quote(String merchantNo, String requestNo, QuoteCommand cmd) {
        MktRequest r = requireRequest(requestNo);
        if (r.getChosenQuoteNo() != null || MktRequest.CLOSED.equals(r.getStatus())) {
            /*
             * 已锁价的单不再接受报价：接受了也没意义，只会让商家以为还有机会。
             *
             * ⚠️ **{@link #revise} 在同样的情况下是放行的，这是有意的** ——
             * 那条路改的是「我这张报价的挂牌价」，对后续邻居仍然有效；
             * 这条路问的是「这张需求单还收不收报价」，答案是不收。
             * 两个入口做同一件事而口径相反，看着像不一致，实际是两个问题。
             * `M6cGroupFlowTest.quoteAndReviseDifferAfterLock` 把这个差异钉住了。
             */
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        MktQuote existing = scoped(() -> quoteMapper.selectOne(Wrappers.<MktQuote>lambdaQuery()
                .eq(MktQuote::getRequestNo, requestNo)
                .eq(MktQuote::getEntityNo, merchantNo).last("limit 1")));
        if (existing != null) {
            // 二次报价 = 改价，同样要留痕
            return doRevise(existing, cmd.unitPriceMinor(), cmd);
        }

        MktQuote q = new MktQuote();
        q.setQuoteNo(BizKey.next(BizKey.QUOTE));
        q.setRequestNo(requestNo);
        q.setEntityNo(merchantNo);
        q.setUnitPriceMinor(cmd.unitPriceMinor());
        q.setMinQty(Math.max(cmd.minQty(), 1));
        q.setNote(cmd.note());
        q.setValidUntil(System.currentTimeMillis()
                + Duration.ofDays(Math.max(cmd.validDays(), 1)).toMillis());
        q.setRevisionCount(0);
        q.setChosen(false);
        q.setStatus("ACTIVE");
        scoped(() -> quoteMapper.insert(q));

        if (MktRequest.COLLECTING.equals(r.getStatus())) {
            r.setStatus(MktRequest.QUOTED);
            scoped(() -> requestMapper.updateById(r));
        }
        return toQuoteVO(q);
    }

    @Override
    @Transactional
    public QuoteVO revise(String merchantNo, String quoteNo, QuoteCommand cmd) {
        MktQuote q = requireQuote(quoteNo);
        if (!q.getEntityNo().equals(merchantNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * ⚠️ **这里刻意不判「已锁价」，与 {@link #quote} 不同** ——
         * 锁价保护的是**快照**（被选中那一刻的价写进 chosenQuote，此后成交按它算），
         * 不是禁止商家改自己的挂牌价：那张报价对**后续**邻居仍然有效。
         * `M6cGroupFlowTest.chosenQuoteLocksPrice` 锁住了这个行为。
         *
         * 而 quote() 在锁价后直接拒（「接受了也没意义，只会让商家以为还有机会」）。
         * 两个入口对同一件事口径不同，**目前没有实际后果**（端上只走 quote），
         * 但它是一条等着被踩的缝：哪天有人接了 revise，改价的规则会随入口而变。
         * 见 docs/technical/design/B端权限对接-整改清单.md B1。
         */
        return doRevise(q, cmd.unitPriceMinor(), cmd);
    }

    /** 改价：**先留痕再改值**。反过来的话，异常中断时会留下改了价却没有记录的报价。 */
    private QuoteVO doRevise(MktQuote q, long newPrice, QuoteCommand cmd) {
        long oldPrice = nz(q.getUnitPriceMinor());
        if (newPrice != oldPrice) {
            MktQuoteRevision rev = new MktQuoteRevision();
            rev.setQuoteNo(q.getQuoteNo());
            rev.setRequestNo(q.getRequestNo());
            rev.setEntityNo(q.getEntityNo());
            rev.setFromPriceMinor(oldPrice);
            rev.setToPriceMinor(newPrice);
            rev.setRaised(newPrice > oldPrice);
            rev.setAt(System.currentTimeMillis());
            rev.setTenantNo("MAIN");
            rev.setCreatedAt(LocalDateTime.now());
            scoped(() -> revisionMapper.insert(rev));

            q.setUnitPriceMinor(newPrice);
            q.setRevisionCount(nz(q.getRevisionCount()) + 1);
        }
        if (cmd.minQty() > 0) {
            q.setMinQty(cmd.minQty());
        }
        if (cmd.note() != null) {
            q.setNote(cmd.note());
        }
        scoped(() -> quoteMapper.updateById(q));
        return toQuoteVO(q);
    }

    // ---------------------------------------------------------------- 装配


    // ---------------------------------------------------------------- C 端发起团（C-GB-05/06）

    @Override
    @Transactional
    public GroupBuyVO createGroupBuy(CreateGroupBuyCommand cmd) {
        String userNo = SecurityUtils.currentUserNo();
        /*
         * 价、人数、时限取这件货在跑的拼团活动（设计 D7），与下单开团（openGroup）同一个实现。
         * 此前读的是商品上的两列，而那两列已经挪进活动、恒为空 —— 这条路一律「未开放拼团」。
         *
         * **不再自动把发起人算成第一人**：成员只在付款成功时落。
         * 开完团端上紧接着带团号去下单，付了款他才是第一人。
         */
        MktGroupBuy g = joinPort.openBuyerGroup(userNo, cmd.goodsNo(), cmd.pickupNo());

        /*
         * 勾了「送到我家」→ 建团粒度临时自提点（ADR-005）。
         * **承接人恒为发起人本人**，Port 的签名里没有「指定他人」这个可能 ——
         * 一旦能指定别人，就是团长招募换了个名字。
         */
        if (cmd.neighborAddress() != null && !cmd.neighborAddress().isBlank()) {
            groupPickupPort.createForGroup(g.getGroupNo(), userNo,
                    nicknameOf(userNo) + "家", cmd.neighborAddress(), cmd.neighborTimeSlot());
        }
        return toGroupBuyVO(g, false);
    }

    @Override
    public List<GroupBuyVO> myJoinedGroups() {
        String userNo = SecurityUtils.currentUserNo();
        List<String> nos = scoped(() -> memberMapper.selectList(Wrappers.<MktGroupMember>lambdaQuery()
                        .eq(MktGroupMember::getUserNo, userNo)
                        .orderByDesc(MktGroupMember::getId))).stream()
                .map(MktGroupMember::getGroupNo).distinct().toList();
        if (nos.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, MktGroupBuy> byNo = scoped(() -> groupBuyMapper.selectList(
                        Wrappers.<MktGroupBuy>lambdaQuery().in(MktGroupBuy::getGroupNo, nos))).stream()
                .collect(java.util.stream.Collectors.toMap(MktGroupBuy::getGroupNo, g -> g, (a, b) -> a));
        // 顺序按我参团的先后（最近在前），不按 IN 查出来的顺序
        return nos.stream().map(byNo::get).filter(java.util.Objects::nonNull)
                .map(g -> toGroupBuyVO(g, true)).toList();
    }

    @Override
    public List<GroupBuyVO> myHostedGroups() {
        String userNo = SecurityUtils.currentUserNo();
        return scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getInitiatorUserNo, userNo)
                        .orderByDesc(MktGroupBuy::getId)))
                .stream().map(g -> toGroupBuyVO(g, true)).toList();
    }

    // ---------------------------------------------------------------- 发起人的履约动作（E16）

    @Override
    public List<GroupPickupOrderVO> groupPickupOrders(String groupNo) {
        requireOwner(groupNo);
        return fulfillmentPort.ordersOfGroup(groupNo, null).stream()
                .map(o -> new GroupPickupOrderVO(o.subOrderNo(), o.buyerNickname(),
                        o.verifyCode(), o.status(),
                        o.items().stream()
                                .map(i -> new GroupVOs.ItemVO(i.goodsNo(), i.title(), i.spec(), i.qty()))
                                .toList()))
                .toList();
    }

    @Override
    @Transactional
    public GroupBuyVO confirmGroupBatch(String groupNo) {
        MktGroupBuy g = requireOwner(groupNo);
        // 验收清单：「团不存在」由 requireOwner 里的查询负责；这里管「没有邻里自提点」
        if (!groupPickupPort.receive(groupNo, SecurityUtils.currentUserNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toGroupBuyVO(g, true);
    }

    @Override
    @Transactional
    public GroupPickupOrderVO verifyGroupPickup(String groupNo, String verifyCode) {
        String userNo = SecurityUtils.currentUserNo();
        requireOwner(groupNo);

        /*
         * 未签收不许核销：货还没到发起人手里就逐单核销，等于替商家确认了一件没发生的事，
         * 真出了少件谁也说不清是路上丢的还是没送到。
         */
        var pickup = groupPickupPort.findByGroup(groupNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (pickup.receivedAt() == null) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        // 验收清单：「核销码无效」
        var order = fulfillmentPort.findByVerifyCode(verifyCode)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        /*
         * 验收清单：「这单不属于本团」。**核销的作用域就是 groupNo**（ADR-005 / E16）——
         * 不按团裁剪的话，任何开过团的人都能拿别人团的码来核销。
         * 这条在 mock 里实测过：拿别单的码会被拒。
         */
        if (!groupNo.equals(order.groupNo())) {
            throw BizException.of(ErrorCode.NOT_THIS_PICKUP_POINT);
        }
        // 验收清单：「该订单已核销」
        if (!fulfillmentPort.complete(order.subOrderNo(), userNo, "邻里自提核销")) {
            throw BizException.of(ErrorCode.ALREADY_VERIFIED);
        }
        return new GroupPickupOrderVO(order.subOrderNo(), order.buyerNickname(),
                order.verifyCode(), "COMPLETED",
                order.items().stream()
                        .map(i -> new GroupVOs.ItemVO(i.goodsNo(), i.title(), i.spec(), i.qty()))
                        .toList());
    }

    @Override
    @Transactional
    public RequestVO confirmRequest(String requestNo) {
        String userNo = SecurityUtils.currentUserNo();
        MktRequest r = requireRequest(requestNo);
        // 只有发起人能收口自己的需求单
        if (!userNo.equals(r.getOwnerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // 没选定报价就没有可确认的东西
        if (r.getChosenQuoteNo() == null || r.getChosenQuoteNo().isBlank()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // LOCKED（已锁价）→ CONFIRMED（发起人确认收货），需求单到此收口
        r.setStatus(MktRequest.CONFIRMED);
        scoped(() -> requestMapper.updateById(r));
        return toRequestVO(r, false);
    }

    /**
     * 取团并校验当前用户是发起人。
     * 验收清单里「只有发起人能签收」「只有发起人能核销本团」两条都落在这里 ——
     * 写成一个方法而不是两处各判一次，避免其中一处将来被改松。
     */
    private MktGroupBuy requireOwner(String groupNo) {
        MktGroupBuy g = requireGroupBuy(groupNo);
        String userNo = SecurityUtils.currentUserNo();
        if (!userNo.equals(g.getInitiatorUserNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return g;
    }

    private GroupBuyVO toGroupBuyVO(MktGroupBuy g, boolean joined) {
        String me = SecurityUtils.currentUserNoOrNull();
        String initiator = g.getInitiatorUserNo();
        boolean owner = initiator != null && initiator.equals(me);
        var neighbor = groupPickupPort.findByGroup(g.getGroupNo())
                .map(np -> new GroupVOs.NeighborPickupVO(np.pickupNo(), np.groupNo(), np.name(),
                        // 成团前只到楼栋，成团后才给完整门牌（B13）——
                        // 未成团的团不该暴露发起人住址，哪怕参团者已经付了钱
                        MktGroupBuy.FORMED.equals(g.getStatus()) ? np.address() : maskAddress(np.address()),
                        np.timeSlot(), np.receivedAt()))
                .orElse(null);
        int minCount = nz(g.getMinCount());
        int joinedCount = nz(g.getJoinedCount());
        var m = merchantPort.find(g.getEntityNo());
        var pickup = g.getPickupNo() == null ? java.util.Optional.<PickupQueryPort.PickupBrief>empty()
                : pickupPort.find(g.getPickupNo());
        return new GroupBuyVO(g.getGroupNo(), g.getGoodsNo(), g.getTitle(), g.getCover(),
                new GroupVOs.MerchantBriefVO(g.getEntityNo(),
                        m.map(MerchantQueryPort.MerchantBrief::merchantName).orElse(""),
                        m.map(MerchantQueryPort.MerchantBrief::logo).orElse(""),
                        m.map(MerchantQueryPort.MerchantBrief::rating).orElse(0d),
                        m.map(MerchantQueryPort.MerchantBrief::ratingCount).orElse(0),
                        m.map(MerchantQueryPort.MerchantBrief::verified).orElse(false),
                        m.map(MerchantQueryPort.MerchantBrief::breachCount).orElse(0),
                        m.map(MerchantQueryPort.MerchantBrief::selfOperated).orElse(false)),
                initiator == null ? null : nicknameOf(initiator),
                initiator == null ? "" : avatarOf(initiator),
                g.getPickupNo(), pickup.map(PickupQueryPort.PickupBrief::name).orElse(""),
                nz(g.getOriginPriceMinor()), nz(g.getGroupPriceMinor()),
                minCount, joinedCount,
                // 「差几人」由后端算：它是成团规则的一部分，端上再算一遍迟早分叉
                joinedCount >= minCount, Math.max(0, minCount - joinedCount),
                nz(g.getEndAt()), members(g.getGroupNo()),
                joined, owner, g.getStatus(), neighbor,
                g.getActivityNo(),
                groupRulePort.activityName(g.getActivityNo()).orElse(null),
                me == null ? null : myOrderNo(g.getGroupNo(), me));
    }

    /** 我在这个团里的那一单。没参团为 null */
    private String myOrderNo(String groupNo, String userNo) {
        MktGroupMember mb = scoped(() -> memberMapper.selectOne(Wrappers.<MktGroupMember>lambdaQuery()
                .eq(MktGroupMember::getGroupNo, groupNo)
                .eq(MktGroupMember::getUserNo, userNo)
                .last("limit 1")));
        return mb == null ? null : mb.getSubOrderNo();
    }

    /** 「阳光里小区 3 幢 101」→「阳光里小区 3 幢（成团后显示门牌）」 */
    private static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        int cut = Math.max(address.lastIndexOf('幢'), address.lastIndexOf('栋'));
        return cut < 0 ? address : address.substring(0, cut + 1) + "（成团后显示门牌）";
    }

    /** 参团的邻居（展示用）。人数不多，一次查完即可 —— 团本来就是几十人的量级。 */
    private List<GroupVOs.MemberVO> members(String groupNo) {
        return scoped(() -> memberMapper.selectList(Wrappers.<MktGroupMember>lambdaQuery()
                        .eq(MktGroupMember::getGroupNo, groupNo)
                        .orderByAsc(MktGroupMember::getId))).stream()
                .map(mb -> new GroupVOs.MemberVO(avatarOf(mb.getUserNo()),
                        mb.getNickname() == null ? nicknameOf(mb.getUserNo()) : mb.getNickname()))
                .toList();
    }

    private String avatarOf(String userNo) {
        return userPort.find(userNo).map(UserQueryPort.UserBrief::avatar).orElse("");
    }

    private String nicknameOf(String userNo) {
        return userPort.find(userNo).map(UserQueryPort.UserBrief::nickname).orElse("邻居");
    }

    private RequestVO toRequestVO(MktRequest r, boolean interested) {
        List<QuoteVO> quotes = quotes(r.getRequestNo());
        QuoteVO chosen = r.getChosenQuoteNo() == null ? null : toChosenQuoteVO(r);
        var owner = userPort.find(r.getOwnerId());
        var pickup = r.getPickupNo() == null ? java.util.Optional.<PickupQueryPort.PickupBrief>empty()
                : pickupPort.find(r.getPickupNo());
        return new RequestVO(r.getRequestNo(), r.getTitle(), r.getDescription(),
                readList(r.getImages()), r.getOwnerId(),
                owner.map(UserQueryPort.UserBrief::nickname).orElse("邻居"),
                owner.map(UserQueryPort.UserBrief::avatar).orElse(""),
                r.getPickupNo(), pickup.map(PickupQueryPort.PickupBrief::name).orElse(""),
                nz(r.getExpectCount()), r.getBudgetMinor(),
                nz(r.getInterestCount()), interested,
                neighbours(r.getRequestNo()),
                r.getStatus(), quotes, chosen,
                r.getCreatedAt() == null ? 0L
                        : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                nz(r.getEndAt()),
                r.getGroupNo(), r.getLockedPrice());
    }

    /** +1 的邻居头像墙。**只取前 12 个**：这是展示，不是名单，全量对页面没有用处。 */
    private List<GroupVOs.NeighbourVO> neighbours(String requestNo) {
        return scoped(() -> interestMapper.selectList(Wrappers.<MktRequestInterest>lambdaQuery()
                        .eq(MktRequestInterest::getRequestNo, requestNo)
                        .orderByAsc(MktRequestInterest::getId).last("limit 12"))).stream()
                .map(i -> userPort.find(i.getUserNo())
                        .map(u -> new GroupVOs.NeighbourVO(u.avatar() == null ? "" : u.avatar(),
                                u.nickname()))
                        .orElseGet(() -> new GroupVOs.NeighbourVO("", "邻居")))
                .toList();
    }

    /**
     * 选定报价的展示：单价用 {@code request.lockedPrice}（**快照**），
     * 不是报价表里的当前价 —— 商家事后改价不该影响已锁的单。
     */
    private QuoteVO toChosenQuoteVO(MktRequest r) {
        MktQuote q = scoped(() -> quoteMapper.selectOne(Wrappers.<MktQuote>lambdaQuery()
                .eq(MktQuote::getQuoteNo, r.getChosenQuoteNo()).last("limit 1")));
        if (q == null) {
            return null;
        }
        QuoteVO vo = toQuoteVO(q);
        // 价用锁定快照，其余照抄；locked=true —— 这一条正是「已锁价」的那条
        return new QuoteVO(vo.quoteNo(), vo.requestNo(), vo.merchant(),
                nz(r.getLockedPrice()), vo.minCount(), vo.desc(),
                vo.validUntil(), vo.createdAt(), true, vo.revisions(), true, vo.status());
    }

    // ---------------------------------------------------------------- 平台侧（P-8.2）

    @Override
    public List<GroupVOs.RequestVO> opsDemands(String status) {
        /*
         * 不带 userNo 条件 —— 这是平台视角，运营要看到所有邻居的需求单。
         * requestList() 按当前登录者过滤，两者的区别就在这一行。
         * scoped() = executeWithoutScope，需求单无商家数据域，跑起来一样。
         */
        String userNo = null; // 平台侧不带「我参没参团」的 interested 标记
        return scoped(() -> requestMapper.selectList(Wrappers.<MktRequest>lambdaQuery()
                        .eq(status != null && !status.isBlank(), MktRequest::getStatus, status)
                        .orderByDesc(MktRequest::getId)))
                .stream().map(r -> toRequestVO(r, false)).toList();
    }

    @Override
    public List<OpsGroupVOs.OpsQuoteVO> opsQuotes(String status) {
        // 不用 scoped()：运营端的全量报价队列，理由同 opsGroups
        var rows = quoteMapper.selectList(Wrappers.<MktQuote>lambdaQuery()
                .eq(status != null && !status.isBlank(), MktQuote::getStatus, status)
                .orderByDesc(MktQuote::getId));
        /*
         * 需求标题一次批量取，不在循环里逐条查 —— 报价列表按需求聚集，
         * 逐条查会对同一个 request_no 查很多遍。
         */
        var titles = requestTitles(rows.stream().map(MktQuote::getRequestNo).toList());
        return rows.stream().map(q -> toOpsQuoteVO(q, titles)).toList();
    }

    /** 需求单号 → 标题。取不到的不放进 map，让调用方落到空串 */
    private java.util.Map<String, String> requestTitles(java.util.List<String> requestNos) {
        var ids = requestNos.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return scoped(() -> requestMapper.selectList(Wrappers.<MktRequest>lambdaQuery()
                        .in(MktRequest::getRequestNo, ids))).stream()
                .filter(r -> r.getTitle() != null)
                .collect(java.util.stream.Collectors.toMap(
                        MktRequest::getRequestNo, MktRequest::getTitle, (a, b) -> a));
    }

    private OpsGroupVOs.OpsQuoteVO toOpsQuoteVO(MktQuote q, java.util.Map<String, String> titles) {
        return new OpsGroupVOs.OpsQuoteVO(
                q.getQuoteNo(), q.getRequestNo(),
                // 取不到给空串而不是编一个 —— 运营在这一列上认单
                titles.getOrDefault(q.getRequestNo(), ""),
                q.getEntityNo(), merchantNameOf(q.getEntityNo()),
                nz(q.getUnitPriceMinor()), nz(q.getMinQty()), nz(q.getValidUntil()),
                nz(q.getRevisionCount()),
                // 由状态推出，不让端上再判一次 —— 两处各判一次迟早分岔
                MktQuote.BREACH.equals(q.getStatus()),
                q.getStatus() == null ? MktQuote.ACTIVE : q.getStatus(),
                millis(q.getCreatedAt()));
    }

    /**
     * <b>团购价必须低于原价</b>，否则「团购」是假的：C 端会看到
     * 「团购价 ¥15.00 / 原价 ¥9.90」这种自己打自己脸的价签，
     * 而凑齐人数的买家实际上多付了钱。
     *
     * <p>此前两条开团路径都只校验 {@code > 0}。ops-web 的类型注释上明明写着这条约束
     * （「必须低于原价，否则『团购』是假的」）—— <b>又一处「注释承诺了一个不存在的校验」</b>。
     * 它是在补运营端 VO 时被一条手工造的数据撞出来的：`groupPrice 1500 / originPrice 990`
     * 一路存进库、发到接口、渲染上页面，没有任何一层拦。
     *
     * <p>相等也拒：一个不省钱的团没有存在的理由，而它会占掉一个开团位。
     */
    private static void requireCheaperThanOrigin(
            ai.neargo.shop.spi.product.GoodsQueryPort.SkuSnapshot snap) {
        if (snap.groupPriceMinor() >= snap.price()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
    }

    private static long millis(java.time.LocalDateTime t) {
        return t == null ? 0L
                : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    @Transactional
    public QuoteVO opsRevisePrice(String quoteNo, long unitPriceMinor, String reason, String operatorNo) {
        if (unitPriceMinor <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktQuote q = requireQuote(quoteNo);
        if (MktQuote.BREACH.equals(q.getStatus())) {
            // 已判毁约的报价不再改价：改了也没人会按它成交，只会让价格历史更难读
            throw BizException.of(ErrorCode.CONFLICT);
        }
        // 走与商家改价同一条留痕路径 —— 公示给用户的是同一份价格历史，
        // 平台改的那一笔不该长得不一样
        return doRevise(q, unitPriceMinor,
                new QuoteCommand(unitPriceMinor, nz(q.getMinQty()), q.getNote(), 0));
    }

    @Override
    @Transactional
    public QuoteVO markBreach(String quoteNo, String detail, String operatorNo) {
        if (detail == null || detail.isBlank()) {
            // 没有事实的处置在申诉时站不住 —— 与 mch_violation.detail 的必填是同一条规矩
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktQuote q = requireQuote(quoteNo);
        if (MktQuote.BREACH.equals(q.getStatus())) {
            return toQuoteVO(q);   // 幂等：重复判定不叠加违规记录
        }
        q.setStatus(MktQuote.BREACH);
        scoped(() -> quoteMapper.updateById(q));
        /*
         * 经 Port 写商家违规，而不是直接碰 mch_violation：营销域依赖商家域会被 ArchUnit 拦下，
         * 而且违规的分级规则、对信用分的影响将来只该在商家域里改。
         * type=BREACH 是唯一计入 breach_count 的类型，那个数字公示在报价卡上（ADR-003）。
         */
        governPort.record(q.getEntityNo(), "BREACH", "WARN", detail, operatorNo);
        return toQuoteVO(q);
    }

    @Override
    public List<OpsGroupVOs.OpsGroupVO> opsGroups(String status) {
        /*
         * **不用 scoped()**：这是运营端的全量拼团队列，这一页上有商家名。
         * `scoped()` 那句注释（「团购是公共内容，分享链接要能打开」）对 C 端成立，
         * 对运营治理页不成立 —— **同一个包装被两拨调用方共用，
         * 于是 C 端的理由把运营端也一起豁免了**。
         * C 端那几处照旧走 scoped()。
         */
        return groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(status != null && !status.isBlank(), MktGroupBuy::getStatus, status)
                .orderByDesc(MktGroupBuy::getId)).stream()
                .map(g -> new OpsGroupVOs.OpsGroupVO(
                        g.getGroupNo(), g.getEntityNo(), merchantNameOf(g.getEntityNo()),
                        g.getTitle(), nz(g.getOriginPriceMinor()), nz(g.getGroupPriceMinor()),
                        nz(g.getMinCount()),
                        // ★ 已参团**人数**。C 端 VO 的同名字段是 boolean「我参没参团」，
                        //   发过去 false 当人数用，页面上就是一个不报错的错数字
                        nz(g.getJoinedCount()),
                        g.getStatus(), nz(g.getEndAt()), millis(g.getCreatedAt())))
                .toList();
    }

    /*
     * **不加 @Transactional**：退款逐张各自一个事务（售后是代理），
     * 包在外层事务里的话，一张退失败会把整个事务标成只能回滚 —— 连「置 FAILED」一起没了。
     * 置 FAILED 本身是一条带条件的 UPDATE，不需要外层事务。
     */
    @Override
    public GroupBuyVO abortGroup(String groupNo, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            // 团没了总得给参团的人一个说法。空理由的中止在客服那里是解释不了的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MktGroupBuy g = scoped(() -> groupBuyMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (MktGroupBuy.FORMED.equals(g.getStatus())) {
            /*
             * 已成团的不许中止：那一刻起用户已经付了钱、商家已经在备货。
             * 要退只能走售后（有退款与责任判定），不能靠把状态改回去 ——
             * 改状态不会把钱退给任何人，只会让订单和团的状态对不上。
             */
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (MktGroupBuy.FAILED.equals(g.getStatus())) {
            return toGroupBuyVO(g, false);   // 幂等
        }
        /*
         * 中止 = 置 FAILED **并退款**（设计 D5 / X5）。此前只改状态：
         * 参团的人付的钱一分没退，而团已经从列表里消失了。
         */
        if (fail(groupNo, List.of(MktGroupBuy.PENDING, MktGroupBuy.OPEN))) {
            groupOrderPort.refundAll(groupNo, "平台中止拼团：" + reason);
        }
        return toGroupBuyVO(requireGroup(groupNo), false);
    }

    @Override
    @Transactional
    public GroupBuyVO auditGroup(String groupNo, boolean pass, String reason, String operatorNo) {
        MktGroupBuy g = requireGroup(groupNo);
        if (!MktGroupBuy.PENDING.equals(g.getStatus())) {
            /*
             * 只有待审的团能审。**不做成幂等返回** —— 两个运营同时点，
             * 后点的那个应当看到「已经审过了，刷新看看」，
             * 而不是以为自己的判断生效了（他可能点的是相反的那个键）。
             */
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (!pass) {
            if (reason == null || reason.isBlank()) {
                // 驳回理由商家会原样看到。空理由等于让他猜自己错在哪
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            g.setStatus(MktGroupBuy.FAILED);
            scoped(() -> groupBuyMapper.updateById(g));
            return toGroupBuyVO(g, false);
        }
        /*
         * 通过前的两条硬校验 —— **不能只做 UI 提示**：
         * 「1 个人的团」与「团购价不低于原价」放出去之后，
         * C 端页面上写着「拼团」，而它既拼不起来、也不便宜。
         */
        if (g.getMinCount() == null || g.getMinCount() < 2) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (nz(g.getGroupPriceMinor()) >= nz(g.getOriginPriceMinor())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        g.setStatus(MktGroupBuy.OPEN);
        scoped(() -> groupBuyMapper.updateById(g));
        return toGroupBuyVO(g, false);
    }

    /** 合法迁移表。**只列允许的**，其余一律拒 —— 白名单比黑名单少一类漏网。 */
    private static final java.util.Map<String, java.util.Set<String>> STATUS_MOVES = java.util.Map.of(
            MktGroupBuy.PENDING, java.util.Set.of(MktGroupBuy.OPEN, MktGroupBuy.FAILED),
            MktGroupBuy.OPEN, java.util.Set.of(MktGroupBuy.FORMED, MktGroupBuy.FAILED));

    /* 不加 @Transactional：改成 FAILED 时要退款，理由同 abortGroup */
    @Override
    public GroupBuyVO setGroupStatus(String groupNo, String status, String operatorNo) {
        MktGroupBuy g = requireGroup(groupNo);
        if (status == null || status.equals(g.getStatus())) {
            return toGroupBuyVO(g, false);   // 原地不动是幂等，不是错
        }
        /*
         * **已成团/已失败是终态**：改回去不会把钱退给任何人，也不会把货变回来，
         * 只会让订单与团的状态对不上 —— 与 abortGroup 拒绝改已成团是同一条理由。
         */
        if (!STATUS_MOVES.getOrDefault(g.getStatus(), java.util.Set.of()).contains(status)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (MktGroupBuy.FAILED.equals(status)) {
            // 改成失败与中止同一个后果：钱要退。只改状态会留下「团没了、钱还扣着」
            if (fail(groupNo, List.of(g.getStatus()))) {
                groupOrderPort.refundAll(groupNo, "平台关闭拼团");
            }
            return toGroupBuyVO(requireGroup(groupNo), false);
        }
        g.setStatus(status);
        scoped(() -> groupBuyMapper.updateById(g));
        return toGroupBuyVO(g, false);
    }

    private MktGroupBuy requireGroup(String groupNo) {
        MktGroupBuy g = scoped(() -> groupBuyMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private QuoteVO toQuoteVO(MktQuote q) {
        var m = merchantPort.find(q.getEntityNo());
        return new QuoteVO(q.getQuoteNo(), q.getRequestNo(),
                new GroupVOs.MerchantBriefVO(q.getEntityNo(),
                        m.map(MerchantQueryPort.MerchantBrief::merchantName).orElse(""),
                        m.map(MerchantQueryPort.MerchantBrief::logo).orElse(""),
                        m.map(MerchantQueryPort.MerchantBrief::rating).orElse(0d),
                        m.map(MerchantQueryPort.MerchantBrief::ratingCount).orElse(0),
                        m.map(MerchantQueryPort.MerchantBrief::verified).orElse(false),
                        // ★ 毁约次数直接公示在报价卡上（ADR-003）
                        m.map(MerchantQueryPort.MerchantBrief::breachCount).orElse(0),
                        m.map(MerchantQueryPort.MerchantBrief::selfOperated).orElse(false)),
                nz(q.getUnitPriceMinor()), nz(q.getMinQty()), q.getNote(),
                nz(q.getValidUntil()),
                q.getCreatedAt() == null ? 0L
                        : q.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                Boolean.TRUE.equals(q.getChosen()),
                revisionsOf(q.getQuoteNo()),
                // 被选定即锁价：此后价格不可变，下单一律用快照价（ADR-003 第一层）
                Boolean.TRUE.equals(q.getChosen()),
                q.getStatus() == null ? MktQuote.ACTIVE : q.getStatus());
    }

    /**
     * 这条报价的改价公示。**每项给的是改价前的单价** —— 页面拿最后一条与现价比，
     * 低于现价就说明涨过，于是卡上挂一条「原 ¥X」。
     *
     * <p>此前这里只下发一个 `revisionCount`，于是「谁涨过价」这条 ADR-003 的核心机制
     * 在页面上永远显示不出来：数字对得上，而公示是空的。
     */
    private List<GroupVOs.QuoteHistoryVO> revisionsOf(String quoteNo) {
        return scoped(() -> revisionMapper.selectList(Wrappers.<MktQuoteRevision>lambdaQuery()
                        .eq(MktQuoteRevision::getQuoteNo, quoteNo)
                        .orderByAsc(MktQuoteRevision::getId))).stream()
                .map(rev -> new GroupVOs.QuoteHistoryVO(nz(rev.getFromPriceMinor()), nz(rev.getAt())))
                .toList();
    }

    private String merchantNameOf(String merchantNo) {
        return merchantPort.find(merchantNo)
                .map(MerchantQueryPort.MerchantBrief::merchantName).orElse("");
    }

    private MktGroupBuy requireGroupBuy(String groupNo) {
        MktGroupBuy g = scoped(() -> groupBuyMapper.selectOne(Wrappers.<MktGroupBuy>lambdaQuery()
                .eq(MktGroupBuy::getGroupNo, groupNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private MktRequest requireRequest(String requestNo) {
        MktRequest r = scoped(() -> requestMapper.selectOne(Wrappers.<MktRequest>lambdaQuery()
                .eq(MktRequest::getRequestNo, requestNo).last("limit 1")));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    private MktQuote requireQuote(String quoteNo) {
        MktQuote q = scoped(() -> quoteMapper.selectOne(Wrappers.<MktQuote>lambdaQuery()
                .eq(MktQuote::getQuoteNo, quoteNo).last("limit 1")));
        if (q == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return q;
    }

    private MktGroupMember findMember(String groupNo, String userNo) {
        return scoped(() -> memberMapper.selectOne(Wrappers.<MktGroupMember>lambdaQuery()
                .eq(MktGroupMember::getGroupNo, groupNo)
                .eq(MktGroupMember::getUserNo, userNo).last("limit 1")));
    }

    private MktRequestInterest findInterest(String requestNo, String userNo) {
        return scoped(() -> interestMapper.selectOne(Wrappers.<MktRequestInterest>lambdaQuery()
                .eq(MktRequestInterest::getRequestNo, requestNo)
                .eq(MktRequestInterest::getUserNo, userNo).last("limit 1")));
    }

    /**
     * 团购与求团是**公共内容**（分享出去的链接要能打开），
     * 且商家侧读写的是别人发起的需求单 —— 两种情况都不该被会话维度过滤。
     * 权限由方法内的显式判定负责（如 {@link #choose} 校验 ownerId）。
     */
    private <T> T scoped(java.util.function.Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action::get);
    }

    private String writeJson(List<String> images) {
        try {
            return json.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    public int quotableCount(String merchantNo) {
        List<MktRequest> open = scoped(() -> requestMapper.selectList(
                Wrappers.<MktRequest>lambdaQuery()
                        .in(MktRequest::getStatus, MktRequest.COLLECTING, MktRequest.QUOTED)));
        if (open.isEmpty()) {
            return 0;
        }
        // 排掉自己已经报过的：不排的话商家每报一单，待办数字纹丝不动
        java.util.Set<String> quoted = scoped(() -> quoteMapper.selectList(
                        Wrappers.<MktQuote>lambdaQuery().eq(MktQuote::getEntityNo, merchantNo)))
                .stream().map(MktQuote::getRequestNo).collect(java.util.stream.Collectors.toSet());
        return (int) open.stream().filter(r -> !quoted.contains(r.getRequestNo())).count();
    }

    // ---------------------------------------------------------------- 商家侧（B-11.9）

    @Override
    public List<GroupBuyVO> merchantGroups(String merchantNo) {
        return scoped(() -> groupBuyMapper.selectList(Wrappers.<MktGroupBuy>lambdaQuery()
                        .eq(MktGroupBuy::getEntityNo, merchantNo)
                        .orderByDesc(MktGroupBuy::getId)))
                .stream().map(g -> toGroupBuyVO(g, false)).toList();
    }

    @Override
    @Transactional
    public GroupBuyVO createMerchantGroup(String merchantNo, String goodsNo, String activityNo, String pickupNo) {
        var snap = goodsPort.snapshotOfGoods(goodsNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        // 只能给自己的货开团 —— 不校验的话，任何商家都能拿别人的货开团并把单收进自己店
        if (!merchantNo.equals(snap.merchantNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!snap.onSale()) {
            // 没上架的货开了团，用户点进去是一个买不了的页面
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * ★ **团购规则来自活动，不再来自商品**（2026-09-18）。
         *
         * 此前读的是 `snap.groupPriceMinor()` / `groupMinCount()`，两列长在
         * `prd_goods` 上 —— 于是一件货一辈子只有一个团购价。挪进活动之后，
         * 同一件货才可能在不同时间参加不同的团。
         *
         * **不要 activityNo 参数**：一件货同时只能在一个团购活动里是服务端硬校验，
         * 从货就能唯一反查出活动。让调用方先挑活动是多一处可以挑错的地方 ——
         * 挑了 A 活动却开了 B 活动的货，两边都说得通，账上是一个错价。
         *
         * 开团这一步仍然**不能临时定价**：价由活动定，与从前由商品定同一条理由 ——
         * 否则同一件货会有两个价，而 C 端已经看到过另一个。
         */
        var rule = groupRulePort.activeRuleFor(merchantNo, snap.goodsNo())
                .orElseThrow(() -> BizException.of(ErrorCode.ORDER_STATE_ILLEGAL));
        if (activityNo != null && !activityNo.isBlank() && !activityNo.equals(rule.activityNo())) {
            /*
             * 页面上选的活动（s34 第一行）与这件货此刻真正所在的活动对不上：
             * 活动在选完之后被结束 / 换了货。按页面的开，价就是另一个活动的价。
             */
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (pickupNo != null && !pickupNo.isBlank() && pickupPort.find(pickupNo).isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (rule.groupPriceMinor() <= 0 || rule.groupPriceMinor() >= snap.price()) {
            // 成团价不低于原价 = 一个不省钱的团。建活动那一步没拦住就在这儿拦
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        MktGroupBuy g = new MktGroupBuy();
        g.setGroupNo(BizKey.next(BizKey.GROUP_BUY));
        /*
         * initiatorUserNo 留空 = **这是商家开的团**。
         * 填上店主的 userNo 会让它同时出现在店主的「我发起的团」里，
         * 而那一页的签收与核销是按个人发起人设计的（货送到他家），语义完全不同。
         */
        g.setInitiatorUserNo(null);
        g.setActivityNo(rule.activityNo());
        // 成团范围（s34 第三行）：一车送到一个点。不选 = 不限点，按各人下单配到的点
        g.setPickupNo(pickupNo == null || pickupNo.isBlank() ? null : pickupNo);
        g.setGoodsNo(snap.goodsNo());
        // 不钉规格：团按商品开，参团的人买哪个规格都按团价（与买家开团一致）
        g.setSkuNo(null);
        g.setEntityNo(merchantNo);
        g.setTitle(snap.title());
        g.setCover(snap.cover());
        g.setGroupPriceMinor(rule.groupPriceMinor());
        g.setOriginPriceMinor(snap.price());
        g.setMinCount(Math.max(2, rule.minCount()));
        g.setJoinedCount(0);
        /*
         * 与用户发起的团**走同一个开关** —— 两条建团路径少管一条，
         * 开关就等于没开：商家自己开的团恰恰是最需要审的那种
         * （把原价标高再打「团购价」）。
         */
        g.setStatus(switchPort.bool("group.audit", false) ? MktGroupBuy.PENDING : MktGroupBuy.OPEN);
        /*
         * 成团时限**取活动的配置**（TDD-营销-活动统一模型与集单 §1.3 ②）。
         * 此前写死 7 天：活动里配的时限不生效，而界面上显示的是活动里那个数。
         */
        g.setEndAt(System.currentTimeMillis() + Duration.ofHours(rule.groupHours()).toMillis());
        scoped(() -> groupBuyMapper.insert(g));
        return toGroupBuyVO(g, false);
    }

    @Override
    public GroupBuyVO merchantGroup(String merchantNo, String groupNo) {
        return toGroupBuyVO(requireMerchantGroup(merchantNo, groupNo), false);
    }

    /**
     * 商家散团（s10）：置 FAILED 并把参团的钱退回去。
     *
     * <p>只能散<b>还在拼</b>的团：已成团的那一刻起买家已经按团价付了款、在等货，
     * 散掉等于商家单方面毁约 —— 要退只能逐单走售后，与 {@link #abortGroup} 同一条理由。
     * 不加 @Transactional，理由同 abortGroup。
     */
    @Override
    public GroupBuyVO dissolve(String merchantNo, String groupNo, String reason) {
        MktGroupBuy g = requireMerchantGroup(merchantNo, groupNo);
        if (MktGroupBuy.FAILED.equals(g.getStatus())) {
            return toGroupBuyVO(g, false);   // 幂等：点了两次散团
        }
        if (!fail(groupNo, List.of(MktGroupBuy.PENDING, MktGroupBuy.OPEN))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String why = reason == null || reason.isBlank() ? "商家散团，自动退款" : "商家散团：" + reason;
        groupOrderPort.refundAll(groupNo, why);
        return toGroupBuyVO(requireGroup(groupNo), false);
    }

    /** 团必须是这家的。别家的团一律当不存在（404），不说「无权」—— 那会确认团号有效 */
    private MktGroupBuy requireMerchantGroup(String merchantNo, String groupNo) {
        MktGroupBuy g = requireGroup(groupNo);
        if (!merchantNo.equals(g.getEntityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }
}
