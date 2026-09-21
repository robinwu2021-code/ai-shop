package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 订单（对齐 c-app {@code Order} 的平铺模型 + Q6 双视角）。
 *
 * <p><b>同一个结构承担两种视角</b>：
 * <ul>
 *   <li><b>订单视角</b>（{@code orderNo} = 子单号）：单商家，有 {@code fulfillment} /
 *       {@code verifyCode} / {@code timeline}。订单列表、详情、售后、评价、核销都用它。</li>
 *   <li><b>支付视角</b>（{@code orderNo} = 主单号）：合计金额 + {@code subOrders}，
 *       **不给 {@code fulfillment}** —— 跨商家可能各不相同，给一个单值就是错的。只有收银台用。</li>
 * </ul>
 *
 * <p>为什么不拆成两个类：c-app 的 `Order` 类型只有一个，拆两个会逼前端在每个页面判断「这是哪种」。
 * 用可空字段表达差异，端上按字段有无渲染即可。
 */
public record OrderVO(String orderNo,
                      /** 支付单号（主单）。两种视角都带，收银台靠它跳转 */
                      String payOrderNo,
                      String status,
                      /** 支付视角为 null */
                      String fulfillment,
                      String merchantNo,
                      String merchantName,
                      List<ItemVO> items,
                      Amount amount,
                      /** 自提码/核销码/兑换码三态共用；支付成功后才有 */
                      String verifyCode,
                      String pickupNo,
                      String pickupName,
                      /** 支付截止时间（原 expireAt，随前端命名） */
                      Long payDeadlineAt,
                      long createdAt,
                      Long paidAt,
                      /**
                       * 快递单号（EXPRESS 履约）。
                       *
                       * <p><b>买家和商家都要看得到</b>：没有单号的「已发货」对买家没有任何用处 ——
                       * 他既查不到物流，也无法判断该不该继续等；商家这边则无法核对自己填了什么。
                       * 此前这一列在库里有、在 VO 里没有，于是发货这件事对两边都不可见。
                       */
                      String expressNo,
                      String trafficSource,
                      /**
                       * 预约开始时间（{@code APPOINTMENT} 履约）。其余履约为 null。
                       *
                       * <p><b>没有时间的「待服务」等于没说</b> —— 买家要知道的正是几点，
                       * 商家的待服务列表也按它排。端上 {@code orderView()} 见到它才会
                       * 用带时间的文案。
                       */
                      Long appointmentAt,
                      /**
                       * 收件人（V69 快照）。<b>自提单为 null</b>。
                       *
                       * <p>此前这一列在库里有 {@code address_id}、在 VO 里连字段都没有 ——
                       * 于是「商家自送」这条链路上，<b>所有角色（含店主）都不知道送到哪里</b>，
                       * 而页面上只有一个「已送达」按钮。
                       */
                      Receiver receiver,
                      List<TimelineNode> timeline,
                      /** **仅支付视角**：一次支付覆盖的各商家订单 */
                      List<OrderVO> subOrders,
                      /**
                       * 下单人昵称。**商家侧才有**（B12：认得出是谁，不给联系方式）。
                       *
                       * <p>端上的契约一直有这个字段，后端此前不下发 —— 于是
                       * B 端售后页每一行的买家都是「—」：店主要处理一张退货单，
                       * 却看不到是谁申请的，只能回订单列表里对号入座。
                       */
                      String buyerNickname,
                      /**
                       * 这一单评价过没有。<b>只有详情视角填</b>，列表恒为 false。
                       *
                       * <p>此前端上的契约声明了它、后端从来不发 —— 于是「去评价」的判据
                       * {@code status === COMPLETED && !reviewed} 后半截恒为真，
                       * <b>已经评过的订单照样显示那个按钮</b>。而 mock 会在评价成功后置真，
                       * 所以本机点一遍是对的，只有真机不对。
                       */
                      boolean reviewed,
                      /**
                       * 挂在这一单上的售后单。<b>只有详情视角填</b>，没有则为 null。
                       *
                       * <p>与 {@link #reviewed} 同一个来历：端上声明了、后端不发，于是订单详情页的
                       * 「售后进行中」整张卡（连同「填写退货单号」与「提出申诉」两个动作）
                       * <b>永远不显示</b>。
                       *
                       * <p><b>不在列表视角填</b>：列表一次几十条，逐条去查售后就是 N+1；
                       * 而列表页本来就单独取了一次售后清单。
                       */
                      AfterSaleVO afterSale,
                      /**
                       * 这次支付一共覆盖几笔子订单。<b>只有详情视角填</b>，缺省 1。
                       *
                       * <p>端上原本读的是 {@code payGroupNo} —— <b>那个字段库里、VO 里都不存在，
                       * 是 mock 里造出来的概念</b>，于是详情页那句「本次支付覆盖多笔订单」永远不出现。
                       * 真实模型里这件事由「主单下有几张子单」表达，所以这里发的是个数而不是编号：
                       * 端上要判的本来就是「是不是多于一笔」。
                       */
                      int payGroupSize,
                      // ↓ 社区集单（s37）。非集单单两者皆空
                      String arriveDate,
                      Long cancellableUntil,
                      /** 这一单参加的团（ord_sub_order.group_no）。非团单为空。
                          支付页付完团单落团页、订单详情画拼团进度卡都靠它（TDD-C端拼团买家流程）。
                          端上 Order 类型早就声明了它，后端此前从没下发过 */
                      String groupNo,
                      /**
                       * 配到的自提点离买家多远（米）。**只有确认页那一次预览填**，
                       * 历史订单为 null —— 那时买家在哪儿已经无从谈起。
                       *
                       * <p>{@code -1} = 这个点没标坐标（存量点是手填地址建的），
                       * <b>不是 0</b>：0 会被端上显示成「0 米」，那是一句假话。
                       */
                      Integer pickupDistanceM,
                      /**
                       * 快递公司（微信 {@code delivery_id}，如 SF / ZTO）。
                       *
                       * <p><b>加在最后而不是紧挨着 {@code expressNo}</b>：这个 record 有十处
                       * 位置参数构造，插在中间会让每一处都要改，而改错了编译器**未必**报错
                       * —— 相邻两个都是 String。加在末尾，只有真有值的那几处需要动。
                       *
                       * <p>V344 之前发的存量单为空：当时根本没收集过这一项。
                       */
                      String expressCompany,
                      /**
                       * 这笔优惠**是怎么来的**（TDD-C端优惠依据）。空 = 没有优惠。
                       *
                       * <p>合计仍在 {@code amount.discountMinor}，这里只是把它拆开：
                       * 买家看到的「优惠 −¥10」此前来历不明（活动？券？两者叠加？），
                       * 而后端一直知道 —— `pmt_apply` 每一笔都记着是哪个活动减的。
                       *
                       * <p>明细是**解释不是账**：与合计对不上时以合计为准。
                       * 老订单（走老模型 mkt_campaign 的那些）没有明细，为空。
                       */
                      List<DiscountLine> discountLines,
                      /**
                       * 已取消 / 已退款时券与积分的去向（待办设计 P3）。
                       * <b>只在详情视角、只在这两个状态填</b>，其余为 null；每一项从数据查，不从状态推。
                       */
                      Returned returned,
                      /**
                       * 自送超出配送范围的商家名（待办设计 P6）。<b>只有预览填</b>，送得到时为 null。
                       * 预览不拦、建单才拦 —— 与自提点同一口径：确认页当场给「换地址 / 换配送方式」，
                       * 而不是等他点了付款才说送不到。
                       */
                      List<String> outOfRange,
                      /**
                       * 下单页的优惠选项（优惠券全链路梳理 批 2）：每家店命中哪些活动、现在选的是哪个，
                       * 以及系统算好的<b>最省组合</b>（活动选择 × 券一起枚举）。<b>只有预览填</b>。
                       */
                      Offers offers) {

    /**
     * @param merchants          有活动可选的那几家店
     * @param suggestedChoices   最省组合里每家店参加哪个活动（或 NONE）
     * @param suggestedCouponNo  最省组合用哪张券（用户持有的那张的号）；null = 不用券更省
     * @param suggestedDiscountMinor 最省组合一共减多少（活动 + 券，不含积分）
     */
    public record Offers(List<MerchantOffers> merchants, List<Choice> suggestedChoices,
                         String suggestedCouponNo, long suggestedDiscountMinor) {
    }

    /** @param chosen 这一次预览实际用上的活动号；NONE = 顾客选了不参加；null = 这家店这次没有活动 */
    public record MerchantOffers(String merchantNo, String merchantName, List<Option> options, String chosen) {
    }

    /** @param amountMinor 这家店只参加它时减多少 */
    public record Option(String activityNo, String name, long amountMinor) {
    }

    public record Choice(String merchantNo, String activityNo) {
    }

    /** 订单关闭后券与积分去了哪。三项都可能为空 / 0 —— 有才说 */
    public record Returned(String couponTitle, long pointsReturned, long pointsClawedBack) {
        public boolean isEmpty() {
            return (couponTitle == null || couponTitle.isBlank())
                    && pointsReturned <= 0 && pointsClawedBack <= 0;
        }
    }

    /** 不带优惠选项的签名：存量构造处不必跟着改 */
    public OrderVO(String orderNo, String payOrderNo, String status, String fulfillment,
                   String merchantNo, String merchantName, List<ItemVO> items, Amount amount,
                   String verifyCode, String pickupNo, String pickupName, Long payDeadlineAt,
                   long createdAt, Long paidAt, String expressNo, String trafficSource,
                   Long appointmentAt, Receiver receiver, List<TimelineNode> timeline,
                   List<OrderVO> subOrders, String buyerNickname, boolean reviewed,
                   AfterSaleVO afterSale, int payGroupSize, String arriveDate,
                   Long cancellableUntil, String groupNo, Integer pickupDistanceM,
                   String expressCompany, List<DiscountLine> discountLines, Returned returned,
                   List<String> outOfRange) {
        this(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName, items, amount,
                verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt, paidAt, expressNo,
                trafficSource, appointmentAt, receiver, timeline, subOrders, buyerNickname,
                reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil, groupNo,
                pickupDistanceM, expressCompany, discountLines, returned, outOfRange, null);
    }

    /** 不带配送范围标记的签名：存量构造处不必跟着改 */
    public OrderVO(String orderNo, String payOrderNo, String status, String fulfillment,
                   String merchantNo, String merchantName, List<ItemVO> items, Amount amount,
                   String verifyCode, String pickupNo, String pickupName, Long payDeadlineAt,
                   long createdAt, Long paidAt, String expressNo, String trafficSource,
                   Long appointmentAt, Receiver receiver, List<TimelineNode> timeline,
                   List<OrderVO> subOrders, String buyerNickname, boolean reviewed,
                   AfterSaleVO afterSale, int payGroupSize, String arriveDate,
                   Long cancellableUntil, String groupNo, Integer pickupDistanceM,
                   String expressCompany, List<DiscountLine> discountLines, Returned returned) {
        this(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName, items, amount,
                verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt, paidAt, expressNo,
                trafficSource, appointmentAt, receiver, timeline, subOrders, buyerNickname,
                reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil, groupNo,
                pickupDistanceM, expressCompany, discountLines, returned, null, null);
    }

    /** 不带去向的签名：存量构造处不必跟着改 */
    public OrderVO(String orderNo, String payOrderNo, String status, String fulfillment,
                   String merchantNo, String merchantName, List<ItemVO> items, Amount amount,
                   String verifyCode, String pickupNo, String pickupName, Long payDeadlineAt,
                   long createdAt, Long paidAt, String expressNo, String trafficSource,
                   Long appointmentAt, Receiver receiver, List<TimelineNode> timeline,
                   List<OrderVO> subOrders, String buyerNickname, boolean reviewed,
                   AfterSaleVO afterSale, int payGroupSize, String arriveDate,
                   Long cancellableUntil, String groupNo, Integer pickupDistanceM,
                   String expressCompany, List<DiscountLine> discountLines) {
        this(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName, items, amount,
                verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt, paidAt, expressNo,
                trafficSource, appointmentAt, receiver, timeline, subOrders, buyerNickname,
                reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil, groupNo,
                pickupDistanceM, expressCompany, discountLines, null);
    }

    /**
     * 一条优惠的来历。
     *
     * @param kind ACTIVITY（商家 / 平台活动）或 COUPON（券）
     * @param name 给人看的名字：活动名、券名
     * @param amountMinor 这一条减了多少（正数）
     */
    public record DiscountLine(String kind, String name, long amountMinor) {
        public static final String ACTIVITY = "ACTIVITY";
        public static final String COUPON = "COUPON";
    }

    /**
     * 不带优惠明细的签名：**存量构造处不必跟着改**（同 expressCompany 那一条的理由）。
     * 明细只有预览与订单详情两处填，其余位置给空表 —— 空表的含义是「没有优惠」，
     * 与「没查」不必区分：没有优惠时合计本来就是 0。
     */
    public OrderVO(String orderNo, String payOrderNo, String status, String fulfillment,
                   String merchantNo, String merchantName, List<ItemVO> items, Amount amount,
                   String verifyCode, String pickupNo, String pickupName, Long payDeadlineAt,
                   long createdAt, Long paidAt, String expressNo, String trafficSource,
                   Long appointmentAt, Receiver receiver, List<TimelineNode> timeline,
                   List<OrderVO> subOrders, String buyerNickname, boolean reviewed,
                   AfterSaleVO afterSale, int payGroupSize, String arriveDate,
                   Long cancellableUntil, String groupNo, Integer pickupDistanceM,
                   String expressCompany) {
        this(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName, items, amount,
                verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt, paidAt, expressNo,
                trafficSource, appointmentAt, receiver, timeline, subOrders, buyerNickname,
                reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil, groupNo,
                pickupDistanceM, expressCompany, List.of());
    }

    /** 不带集单字段的旧签名：存量构造处不必跟着改 */
    public OrderVO(String orderNo, String payOrderNo, String status, String fulfillment,
                   String merchantNo, String merchantName, List<ItemVO> items, Amount amount,
                   String verifyCode, String pickupNo, String pickupName, Long payDeadlineAt,
                   long createdAt, Long paidAt, String expressNo, String trafficSource,
                   Long appointmentAt, Receiver receiver, List<TimelineNode> timeline,
                   List<OrderVO> subOrders, String buyerNickname, boolean reviewed,
                   AfterSaleVO afterSale, int payGroupSize) {
        this(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName, items, amount,
                verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt, paidAt, expressNo,
                trafficSource, appointmentAt, receiver, timeline, subOrders, buyerNickname,
                reviewed, afterSale, payGroupSize, null, null, null, null, null, List.of());
    }

    /**
     * 挂上集单信息。{@code cancellableUntil} 是截单时刻：此前可撤单（走退款），此后不能；
     * 已截单时给空 —— 端上只看「有没有」，不必自己再比一次时钟。
     */
    public OrderVO withBatch(String arriveDate, Long cancellableUntil) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned, outOfRange, offers);
    }

    /**
     * 补上**只有详情视角才查**的那三样。
     *
     * <p>做成 {@code with} 而不是让 {@code orderView} 多三个参数：
     * 那个方法被列表与详情共用，多出来的三个查询会让列表变成 N+1。
     */
    /** 挂上团号（只在 C 端订单视角上填） */
    public OrderVO withGroup(String groupNo) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned, outOfRange, offers);
    }

    /**
     * 挂上「这个自提点离你多远」。**只有确认页那一次预览用** ——
     * 距离是按买家此刻的坐标算的，存进订单没有意义，下次看又该变了。
     */
    public OrderVO withPickupDistance(Integer pickupDistanceM) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned, outOfRange, offers);
    }

    /** 挂上超出配送范围的商家（P6，只在预览）。空表给 null —— 端上看 null 就不提示 */
    public OrderVO withOutOfRange(List<String> merchants) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned,
                merchants == null || merchants.isEmpty() ? null : merchants, offers);
    }

    /** 挂上优惠选项（批 2，只在预览）。没有活动也没有券可选时给 null */
    public OrderVO withOffers(Offers o) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned, outOfRange, o);
    }

    /** 挂上去向（P3）。空的一律给 null —— 端上看 null 就整块不显示 */
    public OrderVO withReturned(Returned r) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines,
                r == null || r.isEmpty() ? null : r, outOfRange, offers);
    }

    /** 挂上优惠明细。预览与订单详情各自取各自的来源，见 TDD-C端优惠依据 */
    public OrderVO withDiscountLines(List<DiscountLine> lines) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, lines == null ? List.of() : lines, returned, outOfRange, offers);
    }

    public OrderVO withDetail(boolean reviewed, AfterSaleVO afterSale, int payGroupSize) {
        return new OrderVO(orderNo, payOrderNo, status, fulfillment, merchantNo, merchantName,
                items, amount, verifyCode, pickupNo, pickupName, payDeadlineAt, createdAt,
                paidAt, expressNo, trafficSource, appointmentAt, receiver, timeline, subOrders,
                buyerNickname, reviewed, afterSale, payGroupSize, arriveDate, cancellableUntil,
                groupNo, pickupDistanceM, expressCompany, discountLines, returned, outOfRange, offers);
    }

    /**
     * 金额值对象（字段名随 c-app）。把 8 个金额收在一起，
     * 比在订单上平铺 8 个 `xxxAmount` 更难写错 —— 传参时少一个就编译不过。
     */
    public record Amount(long goodsMinor,
                         long freightMinor,
                         long discountMinor,
                         long payableMinor,
                         long paidMinor,
                         long pointsDeductMinor,
                         int pointsUsed,
                         int pointsEarn,
                         String currency) {

        /**
         * 不带积分的老工厂。<b>它把积分三个字段写死成 0</b> ——
         * 于是「库里有 points_deduct_minor、VO 里有 pointsDeductMinor」的同时，
         * 端上拿到的永远是 0，而两侧代码单独看都对。
         *
         * <p>保留它是因为大量调用方确实没有积分（预览、子单视图），
         * 但**有积分的路径必须用下面那个** —— 少传一次就是一次「金额对不上」。
         */
        public static Amount of(long goods, long freight, long discount, long paid, String currency) {
            return of(goods, freight, discount, paid, 0L, 0, currency);
        }

        /**
         * @param pointsDeduct 积分抵扣金额（分）。**payable 要把它减掉** ——
         *                     不减的话结算页显示的应付比实际扣款高，用户会以为多扣了钱
         * @param pointsUsed   用掉的积分数
         */
        public static Amount of(long goods, long freight, long discount, long paid,
                                long pointsDeduct, int pointsUsed, String currency) {
            return new Amount(goods, freight, discount,
                    goods + freight - discount - pointsDeduct, paid,
                    pointsDeduct, pointsUsed, 0, currency);
        }
    }

    public record ItemVO(String goodsNo,
                         String merchantNo,
                         String skuNo,
                         String title,
                         String cover,
                         String spec,
                         long price,
                         int qty,
                         long amount,
                         String type,
                         /**
                          * 赠品行（买赠活动送的），价格为 0。
                          *
                          * <p>端上的 `OrderItem.isGift` 一直有这个字段，后端此前不下发 ——
                          * 于是买赠订单里会出现一条「¥0.00 ×4」的行，而 C 端认不出它是赠品，
                          * 既显示不了「赠」标，也无法与「商家把价格填成 0」区分开。
                          */
                         boolean isGift,
                         /**
                          * 这一行**最多还能买几件**（TDD/执行计划 B2）。
                          *
                          * <p>下单页要给步进器，而上限只有后端算得准：可售库存是按门店覆盖层算的，
                          * 端上手里那份是商品详情缓存下来的旧数。不给的话端上只能猜，
                          * 猜大了提交才报错、猜小了少卖。
                          *
                          * <p>**只有预览填**，历史订单为 null：那时候的库存与现在无关。
                          * 取「可售库存」与「每人限购还剩几件」的小值（待办设计 P1）。
                          */
                         Integer maxQty,
                         /**
                          * 是谁挡住了 {@code maxQty}：{@value #LIMIT_STOCK} 库存 / {@value #LIMIT_PER_USER} 每人限购。
                          * 端上到顶时的那句话要说对 ——「仅剩 3 件」与「每人限购 5 件，你已买 2 件」
                          * 是两件事，前者等补货能买，后者补货也没用。只有预览填。
                          */
                         String limitReason,
                         /** 每人限购（只在设了限购且开关开着时给） */
                         Integer limitPerUser,
                         /** 已买量（口径见 PurchaseLimitGuard）。与 limitPerUser 同时出现 */
                         Integer boughtQty) {

        public static final String LIMIT_STOCK = "STOCK";
        public static final String LIMIT_PER_USER = "PER_USER";

        /** 只带库存上限的签名（B2 时的形状） */
        public ItemVO(String goodsNo, String merchantNo, String skuNo, String title,
                      String cover, String spec, long price, int qty, long amount,
                      String type, boolean isGift, Integer maxQty) {
            this(goodsNo, merchantNo, skuNo, title, cover, spec, price, qty, amount, type,
                    isGift, maxQty, null, null, null);
        }

        /** 不带上限的旧签名：订单视角那几处构造不必跟着改 */
        public ItemVO(String goodsNo, String merchantNo, String skuNo, String title,
                      String cover, String spec, long price, int qty, long amount,
                      String type, boolean isGift) {
            this(goodsNo, merchantNo, skuNo, title, cover, spec, price, qty, amount, type,
                    isGift, null);
        }
    }

    /**
     * 收件人。
     *
     * @param phone <b>脱敏与否由调用方决定</b>（见 {@code MerchantOrderServiceImpl}）：
     *              商家自送给完整号 —— 送不到门口就得打电话，给后四位等于让人站在楼下干瞪眼；
     *              其余履约方式给后四位（B12：商家不需要能打给每一个买家）。
     *              <b>这条规则写在装配的地方，不写在这里</b> ——
     *              record 只是形状，谁能看到多少是那一处的判断
     */
    public record Receiver(String name, String phone, String address) {
    }

    public record TimelineNode(String status, String label, long at) {
    }
}
