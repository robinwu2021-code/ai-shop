package ai.neargo.shop.spi.marketing;

import java.util.List;

/**
 * trade → marketing：下单时的**店铺活动**优惠计算。
 *
 * <p><b>为什么要有这个 Port，而不是复用 {@link CouponPort}</b>：券与活动是两件事。
 * 券是用户**主动选**的（要领、要挑、一单一张），活动是**自动生效**的
 * （满额就减，用户不做任何动作）。两者的可用性判定、出资方、叠加规则都不同，
 * 硬塞进一个接口会让「这张券为什么没减」和「这个满减为什么没生效」共用一条排查路径。
 *
 * <p><b>它补上的是一条断了的链路</b>：`mkt_campaign` 表此前**没有任何消费方** ——
 * 读它的只有它自己的 mapper/service/controller。商家在 B 端建了满减活动，
 * 后端存下来了，下单时一分钱不减，而商家侧界面显示活动「进行中」。
 * 四层测试全绿，因为测的是「活动能不能建」，没有一条测「建了之后金额有没有变」。
 */
public interface CampaignPort {

    /**
     * 按商家算自动优惠（当前只有满减 {@code FULL_CUT}）。
     *
     * <p><b>与券的先后顺序</b>：先算活动，再算券 —— 券作用在活动优惠**之后**的金额上。
     * 理由是用户视角：满减是他没得选的（满额就减），券是他挑的；
     * 「这张券帮我省了多少」应该是在已有优惠基础上的增量，否则同一张券在不同订单里
     * 显示的减免额会对不上他自己的心算。
     *
     * <p><b>门店级活动</b>（{@code mkt_campaign.store_no} 有值）只对
     * {@code MerchantAmount.storeNo} 那家店生效；全主体活动（store_no 为空）对谁都生效。
     * 两者同时命中时按「取最优」合并 —— 与同类活动之间同一口径。
     *
     * @param groups 按商家分组的商品额（拆单之后、任何优惠之前）
     * @return 每个商家的活动优惠额；没有生效活动时返回空分摊
     */
    Discount autoDiscount(List<MerchantAmount> groups);

    /**
     * 商品页上的活动标签（优惠券全链路梳理 批 3 · B6）：这家店此刻在跑、满足条件就自动减的活动。
     * 此前满减类活动<b>只在下单页出现</b> —— 顾客逛商品时不知道「满 50 减 8」，也就不会凑单。
     *
     * @param thresholdMinor 满多少元（分）才减；0 = 不按金额
     * @param thresholdQty   满几件才减；0 = 不按件数
     * @param newCustomerOnly 只给新客（受众是「非会员」）—— 老客看到「新客立减」会以为自己也有
     */
    record ActivityTag(String activityNo, String name, long amountMinor, long thresholdMinor,
                       int thresholdQty, boolean newCustomerOnly) {
    }

    /** 这家店此刻的活动标签。默认没有 */
    default List<ActivityTag> activityTags(String merchantNo) {
        return List.of();
    }

    /** 顾客选了「这家店不参加活动」 */
    String CHOICE_NONE = "NONE";

    /**
     * 每家店<b>命中的全部活动</b>（候选，优惠券全链路梳理 批 2）。每一条的金额 =
     * 这家店<b>只参加它</b>时减多少。下单页据此列出「满 50 减 8 / 新客立减 5 / 不参加」让顾客选。
     *
     * <p>默认实现只给最优的那一个 —— 没有候选概念的实现（老模型）照旧只有一个选项。
     */
    default List<AppliedActivity> candidates(List<MerchantAmount> groups) {
        return autoDiscount(groups).applied();
    }

    /**
     * 按顾客的选择算活动优惠。
     *
     * @param choices 商家号 → 活动号，或 {@link #CHOICE_NONE}。<b>没出现的店按最优</b>，
     *                所以空 Map 与 {@link #autoDiscount(List)} 完全一致
     * @throws ai.neargo.shop.common.BizException ACTIVITY_CHOICE_UNAVAILABLE —— 选的那个此刻不命中
     */
    default Discount autoDiscount(List<MerchantAmount> groups, java.util.Map<String, String> choices) {
        if (choices == null || choices.isEmpty()) {
            return autoDiscount(groups);
        }
        return pick(candidates(groups), choices);
    }

    /**
     * 从候选里挑：选了不参加 → 这家店没有活动；选了某个 → 就是它（不命中就拒）；
     * 没选 → 金额最大的那个（同额取先出现的 —— 与改造前「严格大于才替换」同一个结果）；
     * 全都不减钱时取第一个只送积分的组合（它不减钱，但付款时要按它发分、扣它的量）。
     */
    static Discount pick(List<AppliedActivity> candidates, java.util.Map<String, String> choices) {
        java.util.Map<String, List<AppliedActivity>> byMerchant = new java.util.LinkedHashMap<>();
        for (AppliedActivity a : candidates) {
            byMerchant.computeIfAbsent(a.merchantNo(), k -> new java.util.ArrayList<>()).add(a);
        }
        for (String m : choices.keySet()) {
            byMerchant.putIfAbsent(m, List.of());
        }
        List<MerchantDiscount> shares = new java.util.ArrayList<>();
        List<AppliedActivity> applied = new java.util.ArrayList<>();
        long total = 0L;
        for (var e : byMerchant.entrySet()) {
            String choice = choices == null ? null : choices.get(e.getKey());
            AppliedActivity chosen = null;
            if (CHOICE_NONE.equals(choice)) {
                continue;
            }
            if (choice != null) {
                chosen = e.getValue().stream().filter(a -> choice.equals(a.activityNo()))
                        .findFirst().orElseThrow(() -> ai.neargo.shop.common.BizException.of(
                                ai.neargo.shop.common.ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE));
            } else {
                for (AppliedActivity a : e.getValue()) {
                    if (chosen == null ? a.amountMinor() > 0 : a.amountMinor() > chosen.amountMinor()) {
                        chosen = a;
                    }
                }
                if (chosen == null) {
                    chosen = e.getValue().stream().findFirst().orElse(null);
                }
            }
            if (chosen == null) {
                continue;
            }
            if (chosen.amountMinor() > 0) {
                shares.add(new MerchantDiscount(e.getKey(), chosen.amountMinor()));
                total += chosen.amountMinor();
            }
            applied.add(chosen);
        }
        return new Discount(total, shares, applied);
    }

    /**
     * 限时特价：这些商品此刻的活动价。
     *
     * <p>返回的 Map 只含**命中活动**的商品，没命中的键不出现 —— 调用方按
     * 「有就覆盖、没有就用原价」处理，不必判断 0 与 null 的差别。
     *
     * <p><b>为什么是 goodsNo 而不是 skuNo</b>：`mkt_campaign` 的模型就是
     * 「这几个商品 + 一个活动价」（{@code goods_nos} + {@code flash_price_minor}），
     * 一个商品的所有 SKU 共用这个价。
     * ⚠️ 多规格商品（10 斤装 / 5 斤装）因此会被拉成同一个价 —— 这是**模型的限制**
     * 而不是实现取舍，要改得先给活动加 SKU 维度。已在方案里记为待确认。
     *
     * @param goodsNos 要查的商品；空集合直接返回空 Map，不打库
     */
    java.util.Map<String, Long> flashPrices(java.util.Collection<String> goodsNos);

    /**
     * 买赠规则：这些商品此刻的「买 N 送 M」。
     *
     * <p>只含命中活动的商品。同一商品命中多个时取**送得最多**的那个 ——
     * 与满减「取最优」、特价「取最低价」同一口径：都往对用户有利的一侧走，
     * 商家不会因为多建一个活动而少送。
     *
     * <p>⚠️ <b>赠品只能是同款</b>：{@code mkt_campaign} 里只有 buyN / giftM，
     * 没有「赠哪件」的字段。端上的 `Promotion` 类型有 giftGoodsNo（买米送油），
     * 后端表达不了 —— 又一处**同一件事两处建模且不一致**，已记入待确认。
     */
    java.util.Map<String, GiftRule> giftRules(java.util.Collection<String> goodsNos);

    /**
     * 买 N 送 M。
     *
     * <p>口径：**付 N 件的钱，收到 N+M 件**（买 2 送 1，买 4 件送 2 件）。
     * 另一种常见口径是「每 N+M 件里有 M 件免费」（买 3 付 2），买 4 件只送 1 件。
     * 取前者是因为它与商家口头说的「买二送一」一致，用户不会算错
     * （与端上 `shared/utils/promotion.ts` 同一口径，两边必须一致，
     * 否则页面显示送 2 件而实际发 1 件）。
     */
    record GiftRule(int buyN, int giftM) {

        public int giftQty(int bought) {
            if (buyN <= 0 || giftM <= 0 || bought < buyN) {
                return 0;
            }
            return bought / buyN * giftM;
        }
    }

    /**
     * @param goodsQty 这笔货的<b>下单件数</b>，供「满 N 件减 M」判门槛用。
     *                 <b>不含买赠送出的那些</b> —— 送的没收钱，拿它去凑满件数
     *                 等于让优惠自己喂自己（买 2 送 1 凑够「满 3 件减 5」）
     * @param storeNo 这笔货<b>从哪家门店出</b>（下单时按自提点解析出来的）。
     *                门店级活动只对它生效；为空表示还没有门店上下文，
     *                此时只有全主体活动生效 —— <b>不是「所有门店活动都生效」</b>，
     *                那会让一家店的开业满减减到别家店的单上
     */
    record MerchantAmount(String merchantNo, long goodsAmount, int goodsQty, String storeNo,
                          /*
                           * 这笔货按商品拆开的金额与件数。平台活动只对报名里的那几件货生效，
                           * 门槛要按那几件货的小计判 —— 只给总额的话，买一件报名的货加一堆别的货就凑够了满减。
                           * 老调用方不传（空列表）：平台活动在这一家上不生效，行为与加字段之前一致。
                           */
                          List<GoodsLine> lines) {

        public MerchantAmount(String merchantNo, long goodsAmount, int goodsQty, String storeNo) {
            this(merchantNo, goodsAmount, goodsQty, storeNo, List.of());
        }
    }

    /** 一件货在这一单里的小计（不含买赠送的那些） */
    record GoodsLine(String goodsNo, long amount, int qty) {
    }

    /**
     * 活动优惠的分摊结果。
     *
     * <p>没有 {@code byMerchant} 字段 —— 店铺活动的出资方**恒为商家**
     * （{@code mkt_campaign.entity_no NOT NULL}，活动是店铺级的，平台不出这个钱）。
     * 券那边需要这个字段是因为平台券与商家券并存。
     */
    /**
     * 下单成功后回执：<b>把这一单真的用掉的活动记下来</b>。
     *
     * <p>新模型的活动有限量（{@code quota}）—— 不在这一步扣，限量就永远不会推进，
     * 那个字段等于摆设。放在下单落库之后、与订单同事务：
     * 扣量失败要能连订单一起回滚，否则会出现「量扣了、单没成」。
     *
     * <p>老实现是空的：{@code mkt_campaign} 没有限量这个概念。
     *
     * @param userNo 下单人。<b>显式传，不从会话里掏</b> —— 掏的话，
     *               测试里与异步线程里都没有会话，写出去的是 null，
     *               而那一列非空，报的是「数据库约束」，看不出根因在这儿
     */
    void commit(String userNo, String orderNo, Discount discount);

    /**
     * 这一单在这家店用上的活动要额外送多少积分（自己组合的「送积分」，原型 s11）。
     * 付款成功时随常规积分一起发 —— 同一次发放、同一笔费用金，不另走一条发分路径。
     * 老模型没有这个概念，缺省 0。
     */
    default long bonusPoints(String orderNo, String merchantNo) {
        return 0L;
    }

    /**
     * 活动优惠的分摊结果。
     *
     * <p>没有 {@code byMerchant} 字段 —— 店铺活动的出资方**恒为商家**。
     *
     * @param applied 这一单命中了哪些活动。<b>算价时就要带出来</b> ——
     *                事后靠金额反推「是哪个活动减的」，在两个活动减一样多的时候无解，
     *                而限量要扣的正是那个具体的活动
     */
    record Discount(long total, List<MerchantDiscount> shares, List<AppliedActivity> applied) {

        public Discount(long total, List<MerchantDiscount> shares) {
            this(total, shares, List.of());
        }

        public static Discount none() {
            return new Discount(0L, List.of(), List.of());
        }

        public long of(String merchantNo) {
            return shares.stream().filter(s -> s.merchantNo().equals(merchantNo))
                    .mapToLong(MerchantDiscount::amount).sum();
        }

        /**
         * 这一家的活动优惠里<b>平台出的那部分</b>（平台活动按出资比例拆出来的）。
         * 落进子单 {@code discount_platform}，结算时算回给商家（{@code gross = 实付 + 平台补贴}）。
         * 只看 {@code applied}：那里只有真正用上的活动，老模型的活动恒为商家出资。
         */
        public long platformOf(String merchantNo) {
            return applied.stream().filter(a -> a.merchantNo().equals(merchantNo))
                    .mapToLong(AppliedActivity::platformMinor).sum();
        }
    }

    record MerchantDiscount(String merchantNo, long amount) {
    }

    /**
     * @param qty           这一单用掉几份限量。满减类是 1 单 1 份
     * @param platformMinor 优惠额里平台出资的部分；商家活动恒为 0
     * @param enrollmentNo  平台活动时是这家店的报名单号（限量扣在报名上，不扣在活动上）；商家活动为空
     */
    /**
     * 关单时把这一单占掉的**活动配额退回去**（执行计划 B6）。
     *
     * <p>此前关单释放了库存、券、积分、预约时段，**唯独没退配额** ——
     * 限量 100 份的活动会被没付款的单吃掉量，而运营看到的「已用 4 份」里
     * 有几份从来没成交。
     *
     * <p><b>幂等</b>：同一单退两次只退一次（判据在数据里 —— 那几行已经作废就不再退）。
     * 老模型（mkt_campaign）没有这张账，空实现。
     */
    default void release(String orderNo) {
    }

    /**
     * 这一单**已经用上的**那几条优惠，按单号回查（TDD-C端优惠依据）。
     *
     * <p>与 {@link #autoDiscount} 的区别是时态：那个是「下单时算一遍」，
     * 这个是「这单当时减了什么」—— 订单详情要的是后者，而活动规则可能早就改了，
     * 所以**不能拿现在的规则重算**，只能读当时落下的那几行。
     *
     * <p>默认空表：老模型（mkt_campaign）没有这张账，端上退回只显示合计。
     */
    default List<AppliedDiscount> appliedOf(String orderNo) {
        return List.of();
    }

    /**
     * 一条已经用上的优惠。
     *
     * @param kind ACTIVITY / COUPON
     * @param name 给买家看的名字；取不到就是空，调用方**不要编一个**
     */
    /**
     * @param merchantNo 这一笔记在哪家店上（一单多家店时，详情只列本子单那家的）
     * @param funder     谁出的钱：MERCHANT / PLATFORM（批 3 · B8，商家对账要知道是他让的还是平台补的）
     */
    record AppliedDiscount(String kind, String name, long amountMinor, String merchantNo, String funder) {
        public static final String ACTIVITY = "ACTIVITY";
        public static final String COUPON = "COUPON";

        /** 老签名：不带商家与出资方 */
        public AppliedDiscount(String kind, String name, long amountMinor) {
            this(kind, name, amountMinor, null, null);
        }
    }

    record AppliedActivity(String activityNo, String merchantNo, long amountMinor, int qty,
                           long platformMinor, String enrollmentNo,
                           /**
                            * 活动名，**给买家看的那个**（TDD-C端优惠依据）。
                            *
                            * <p>算价那一侧本来就读了活动行，顺手带出来 ——
                            * 否则确认页与订单详情要为了一个名字再查一次 promotion，
                            * 而 trade 与 promotion 之间只有这个 port。
                            *
                            * <p>取不到就是空：端上退回只显示金额，<b>不编名字</b>。
                            */
                           String name) {

        public AppliedActivity(String activityNo, String merchantNo, long amountMinor, int qty) {
            this(activityNo, merchantNo, amountMinor, qty, 0L, null, null);
        }

        public AppliedActivity(String activityNo, String merchantNo, long amountMinor, int qty,
                               long platformMinor, String enrollmentNo) {
            this(activityNo, merchantNo, amountMinor, qty, platformMinor, enrollmentNo, null);
        }
    }
}
