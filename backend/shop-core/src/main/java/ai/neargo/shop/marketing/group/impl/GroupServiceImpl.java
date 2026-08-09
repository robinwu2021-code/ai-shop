package ai.neargo.shop.marketing.group.impl;

import ai.neargo.shop.marketing.group.GroupService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.fulfillment.GroupPickupPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
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
    private final GroupMemberMapper memberMapper;
    private final RequestMapper requestMapper;
    private final RequestInterestMapper interestMapper;
    private final QuoteMapper quoteMapper;
    private final QuoteRevisionMapper revisionMapper;
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final ObjectMapper json;

    private final GroupPickupPort groupPickupPort;
    private final FulfillmentQueryPort fulfillmentPort;
    private final GoodsQueryPort goodsPort;
    public GroupServiceImpl(GroupBuyMapper groupBuyMapper, GroupMemberMapper memberMapper,
                            RequestMapper requestMapper, RequestInterestMapper interestMapper,
                            QuoteMapper quoteMapper, QuoteRevisionMapper revisionMapper,
                            MerchantQueryPort merchantPort, UserQueryPort userPort,
                            ObjectMapper json,
                            GroupPickupPort groupPickupPort, FulfillmentQueryPort fulfillmentPort, GoodsQueryPort goodsPort) {
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
                        .orderByDesc(MktGroupBuy::getId))).stream()
                .map(g -> toGroupBuyVO(g, false)).toList();
    }

    @Override
    public GroupBuyVO groupBuyDetail(String groupNo) {
        MktGroupBuy g = requireGroupBuy(groupNo);
        String userNo = SecurityUtils.currentUserNoOrNull();
        return toGroupBuyVO(g, userNo != null && findMember(groupNo, userNo) != null);
    }

    @Override
    @Transactional
    public GroupBuyVO join(String groupNo) {
        String userNo = SecurityUtils.currentUserNo();
        MktGroupBuy g = requireGroupBuy(groupNo);
        if (!MktGroupBuy.OPEN.equals(g.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (findMember(groupNo, userNo) != null) {
            // 一人一团只能参一次 —— 否则「还差 N 人」会被同一个人刷满
            throw BizException.of(ErrorCode.CONFLICT);
        }

        MktGroupMember member = new MktGroupMember();
        member.setGroupNo(groupNo);
        member.setUserNo(userNo);
        member.setNickname(userPort.find(userNo).map(UserQueryPort.UserBrief::nickname).orElse("邻居"));
        member.setJoinedAt(System.currentTimeMillis());
        scoped(() -> memberMapper.insert(member));

        g.setJoinedCount(nz(g.getJoinedCount()) + 1);
        if (g.getJoinedCount() >= nz(g.getMinCount())) {
            g.setStatus(MktGroupBuy.FORMED);
        }
        scoped(() -> groupBuyMapper.updateById(g));
        return toGroupBuyVO(g, true);
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
            // 已锁价的单不再接受报价：接受了也没意义，只会让商家以为还有机会
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

        var snap = goodsPort.snapshot(List.of(cmd.goodsNo())).values().stream().findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!snap.onSale()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 验收清单：「该商品未开放拼团」。
         * 团购价由**商家在商品上配**，开团人只是把它开出来 —— 让开团人自己填价，
         * 等于任何人都能以任意价格卖别人的货。
         */
        if (snap.groupPriceMinor() == null || snap.groupPriceMinor() <= 0) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        MktGroupBuy g = new MktGroupBuy();
        g.setGroupNo(BizKey.next(BizKey.GROUP_BUY));
        g.setInitiatorUserNo(userNo);
        g.setGoodsNo(snap.goodsNo());
        g.setSkuNo(snap.skuNo());
        g.setEntityNo(snap.merchantNo());
        g.setTitle(snap.title());
        g.setCover(snap.cover());
        g.setGroupPriceMinor(snap.groupPriceMinor());
        g.setOriginPriceMinor(snap.price());
        // 一个人不叫团：商家没配就按 2 人起
        g.setMinCount(snap.groupMinCount() == null || snap.groupMinCount() < 2 ? 2 : snap.groupMinCount());
        g.setJoinedCount(0);
        g.setPickupNo(cmd.pickupNo());
        g.setStatus(MktGroupBuy.OPEN);
        // 团的有效期。7 天是发起人能等、商家能备货的折中；到期未成团自动失败
        g.setEndAt(System.currentTimeMillis() + Duration.ofDays(7).toMillis());
        scoped(() -> groupBuyMapper.insert(g));

        /*
         * 勾了「送到我家」→ 建团粒度临时自提点（ADR-005）。
         * **承接人恒为发起人本人**，Port 的签名里没有「指定他人」这个可能 ——
         * 一旦能指定别人，就是团长招募换了个名字。
         */
        if (cmd.neighborAddress() != null && !cmd.neighborAddress().isBlank()) {
            groupPickupPort.createForGroup(g.getGroupNo(), userNo,
                    nicknameOf(userNo) + "家", cmd.neighborAddress(), cmd.neighborTimeSlot());
        }

        // 发起人自动算参团第一人 —— 开了团自己不买，「还差 N 人」就永远差一个
        return join(g.getGroupNo());
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
        return new GroupBuyVO(g.getGroupNo(), g.getGoodsNo(), g.getTitle(), g.getCover(),
                g.getEntityNo(), merchantNameOf(g.getEntityNo()),
                nz(g.getGroupPriceMinor()), nz(g.getOriginPriceMinor()),
                nz(g.getMinCount()), nz(g.getJoinedCount()), g.getStatus(),
                nz(g.getEndAt()), joined,
                initiator == null ? null : nicknameOf(initiator), owner,
                g.getPickupNo(), neighbor);
    }

    /** 「阳光里小区 3 幢 101」→「阳光里小区 3 幢（成团后显示门牌）」 */
    private static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        int cut = Math.max(address.lastIndexOf('幢'), address.lastIndexOf('栋'));
        return cut < 0 ? address : address.substring(0, cut + 1) + "（成团后显示门牌）";
    }

    private String nicknameOf(String userNo) {
        return userPort.find(userNo).map(UserQueryPort.UserBrief::nickname).orElse("邻居");
    }

    private RequestVO toRequestVO(MktRequest r, boolean interested) {
        int quoteCount = Math.toIntExact(scoped(() -> quoteMapper.selectCount(
                Wrappers.<MktQuote>lambdaQuery().eq(MktQuote::getRequestNo, r.getRequestNo()))));
        QuoteVO chosen = r.getChosenQuoteNo() == null ? null
                : toChosenQuoteVO(r);
        return new RequestVO(r.getRequestNo(), r.getTitle(), r.getDescription(),
                readList(r.getImages()), r.getOwnerId(),
                userPort.find(r.getOwnerId()).map(UserQueryPort.UserBrief::nickname).orElse("邻居"),
                nz(r.getExpectCount()), nz(r.getInterestCount()), interested,
                r.getStatus(), quoteCount, chosen,
                r.getCreatedAt() == null ? 0L
                        : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                nz(r.getEndAt()));
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
        return new QuoteVO(vo.quoteNo(), vo.requestNo(), vo.merchantNo(), vo.merchantName(),
                vo.merchantRating(), vo.breachCount(),
                nz(r.getLockedPrice()),
                vo.minQty(), vo.note(), vo.validUntil(), vo.revisionCount(), true, vo.createdAt());
    }

    private QuoteVO toQuoteVO(MktQuote q) {
        var m = merchantPort.find(q.getEntityNo());
        return new QuoteVO(q.getQuoteNo(), q.getRequestNo(), q.getEntityNo(),
                m.map(MerchantQueryPort.MerchantBrief::merchantName).orElse(""),
                m.map(MerchantQueryPort.MerchantBrief::rating).orElse(0d),
                // ★ 毁约次数直接公示在报价卡上（ADR-003）
                m.map(MerchantQueryPort.MerchantBrief::breachCount).orElse(0),
                nz(q.getUnitPriceMinor()), nz(q.getMinQty()), q.getNote(),
                nz(q.getValidUntil()), nz(q.getRevisionCount()),
                Boolean.TRUE.equals(q.getChosen()),
                q.getCreatedAt() == null ? 0L
                        : q.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
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
}
