package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade / fulfillment / settle → user：查商家的最小必要信息。
 *
 * <p>刻意只暴露<b>下单必需</b>的四个字段，而不是返回整个商家实体 ——
 * Port 一旦返回实体，模块边界就名存实亡：调用方会顺手用上不该用的字段，
 * 将来 user 域改一个列，三个模块跟着炸。
 */
public interface MerchantQueryPort {

    /**
     * @param merchantNo 商家业务键
     * @return 空表示商家不存在
     */
    Optional<MerchantBrief> find(String merchantNo);

    /**
     * 批量查 —— 列表页专用（收藏列表、自提点的归属商家、商品卡上的店铺信息）。
     *
     * <p>为什么单独开一个方法而不是让调用方循环 {@link #find(String)}：
     * 这三处都是**一屏一批**的场景，循环即 N+1。而调用方一旦发现单查慢，
     * 下一步就是绕过 Port 直接注入 Mapper 自己批量捞 —— 那正是这次要拆掉的东西。
     * 边界要好用，否则它只会被绕过。
     *
     * @param merchantNos 商家业务键；空集合返回空 Map
     * @return 按 merchantNo 索引；查不到的键**不出现在结果里**（不是 null 值）
     */
    java.util.Map<String, MerchantBrief> findAll(java.util.Collection<String> merchantNos);

    /**
     * 这家店的货<b>能出现在哪些社区</b>（ADR-009，已按 {@code service_scope} 展开）。
     *
     * <p>product 域上架商品时要按这个范围写社区池。放在 Port 上而不是让 product
     * 自己去读 {@code mch_entity_community}：三档范围的展开规则属于 user 域，
     * 两处各实现一遍的结果是「商家页能搜到这家店、商品页却搜不到它的货」。
     *
     * @return 空表示这家店对谁都不可见（scope=COMMUNITY 却一个社区都没配）
     */
    java.util.List<String> reachableCommunities(String merchantNo);

    /**
     * 该主体的<b>默认门店</b>。下单时用它填 {@code ord_sub_order.store_no}（M2 双写）。
     *
     * <p>为什么下单只认默认门店：多门店放开（M6）之前，一个主体恰好一家店，
     * 两者恒等。放开之后这里要换成「按履约方式与用户位置选店」——
     * 那是一次真正的业务决策，不该在双写这一步顺手做掉。
     *
     * @return 空表示该主体没有门店（不该发生，但下单不能因此失败 —— 订单照常创建，
     *         store_no 留空，履约侧按「空 → 默认门店」兜底）
     */
    java.util.Optional<String> defaultStoreNo(String merchantNo);

    /**
     * 门店的<b>门面文案</b>：公告、营业时间、地址 —— 店主自己维护的那三样。
     *
     * <p>只给这三个，<b>不是整个门店资料</b>：经营范围、配送半径、收款号那些是
     * B 端配置，C 端一个字节都不该看到。Port 返回整条记录的话，
     * 调用方迟早会顺手用上不该用的字段。
     *
     * <p>为什么必须有：门店主页此前把公告写死成空串、履约文案写死成一句
     * 「每晚 7 点前到货」—— 店主在 B 端认真填的公告与营业时间，
     * <b>C 端一个字都显示不出来</b>。而门店主页是这一版的主获客路径（ADR-004）。
     *
     * @return 空表示这个主体没有门店（不该发生，调用方按空文案渲染即可）
     */
    java.util.Optional<StoreFront> storeFront(String merchantNo);

    /**
     * 门面文案。字段与契约 `StoreFront` 一一对应。
     *
     * @param status 门店状态 ACTIVE / READONLY / SUSPENDED（V96）——
     *               门店主页要据此显示「已停业」，而不是让停用的店照常收单
     */
    record StoreFront(String announcement, String openHours, String address, String status) {
    }

    /**
     * 门店<b>启用的送货方式</b>集合（方案 v4：channel 挂门店）。
     *
     * <p>trade 下单闸与 product 上架校验都从这里取，两处不各查一遍表。
     *
     * @param storeNo 为空 = <b>主体级并集</b>（所有门店启用的路取并）。商品挂主体，
     *                上架校验用并集；下单校验用履约门店那一份
     * @return <b>空集合表示「未迁移到 channel 模型」</b>（该范围内一行都没有），
     *         调用方按旧口径放行 —— 与 {@code fulfillment_reach} 只读兼容期的约定。
     *         迁移后的门店至少有一行（写入口拦着「一路都不开」），空集不会歧义
     */
    java.util.Set<String> enabledFulfillments(String merchantNo, String storeNo);

    /**
     * 这家主体<b>配置过的</b>取货点：各门店在「社区自提点」里引用的点 ∪ 各门店自己的 STORE 点（P1）。
     *
     * <p>下单闸用它判「买家选的点这家店送不送」。<b>空集 = 没配过，兼容期不限</b>——
     * 与 {@link #enabledFulfillments} 同一约定；写入口已经拦着「自提开着却一个点都没有」。
     */
    java.util.Set<String> allowedPickupNos(String merchantNo);

    /**
     * 这笔钱该打给<b>哪个收款商户号</b>：门店配的号 ?? 主体的默认号。
     *
     * <p><b>只有这一处实现。</b> 两处各写一遍的后果是可预测的：一处按新规则、
     * 一处按老规则，症状是「钱打错账户」—— 而这类错误不会报错，
     * 只会在对账时被发现，还得人工追回。
     *
     * <p>放在 user 域是因为「门店 → 收款号」的归属关系属于商家资料，
     * settle 域不该知道 {@code mch_store} 与 {@code mch_payment_merchant} 长什么样。
     *
     * <p>结算模式不是一个开关，是这个方法的返回值决定的：
     * 两家店解析出同一个号 = 合并结算，解析出不同号 = 分开结算。
     *
     * @param merchantNo 主体业务键
     * @param storeNo    门店业务键；<b>为空按主体默认号解析</b>（存量子单没有门店）
     * @return 空表示这个主体一个可用收款号都没有 —— 进件还没走完，
     *         此时结算单照常生成（钱是欠着的，不是不存在），但不能发起打款
     */
    java.util.Optional<String> payMerchantNoOf(String merchantNo, String storeNo);

    /**
     * 这个主体名下的全部门店号（含停用的）。
     *
     * <p>两个用途，都在「主体级 → 门店级」这条转换路径上：
     * <ol>
     *   <li><b>判断要不要按门店做</b>：多门店时每一次上下架都要落成门店行，
     *       包括在默认门店做的那次。用「是不是默认店」代替这个判断会漏掉
     *       默认店自己那次操作，于是分店一写行，默认店就静悄悄变成未上架</li>
     *   <li><b>转换时把其他门店的现状固化下来</b>：只给被操作的店写行的话，
     *       其余门店因为「有行了但没有自己的行」而一起变成未上架 ——
     *       商家做的只是「A 店今天不卖」，B 店的货却跟着没了。<b>实测撞到过</b></li>
     * </ol>
     *
     * <p>含停用门店是刻意的：停用的店重新启用时，它的上架设置该还在。
     */
    /**
     * 门店的<b>经营模式</b>：{@code SELF_OPERATED} / {@code THIRD_PARTY}。
     *
     * <p>settle 域生成结算单时要把它<b>快照</b>进单据——它决定这张单走哪条状态机
     * （自营：对账→确认→付款；第三方：待分账→可分账→已分账）。
     *
     * <p>放 Port 而不是让 settle 直接读 {@code mch_store}：那是商家域的表，
     * 直连会被 ArchUnit 拦下，而且模式的默认值规则（门店空则回落主体、
     * 主体空则回落平台默认）属于商家域，两处各写一遍迟早分岔。
     *
     * @param storeNo 空 = 用主体的默认门店
     * @return 解析不出时返回 {@code SELF_OPERATED} —— <b>保守回落</b>：
     *         自营的单会要求收进项票，误判为自营只是多要一张票；
     *         误判为第三方则会去下发分账，而对方根本没有二级商户号
     */
    String businessModeOf(String merchantNo, String storeNo);

    /**
     * 该主体的<b>资金路径</b>：{@link #FUNDS_AGGREGATED} / {@link #FUNDS_DIRECT}。
     *
     * <p><b>与 {@link #businessModeOf} 是两件事</b>：这个说钱先进谁的账户，
     * 那个说谁是销售主体。结算侧「要不要补差」判的是这一列 ——
     * 钱在商家账户才需要补进去。
     *
     * <p>查不到返回 {@link #FUNDS_AGGREGATED}：这是今天唯一在跑的路径，
     * 而误判成 DIRECT 会让系统去执行一次<b>本不存在的补差</b>（重复付款）。
     */
    String fundsModeOf(String merchantNo);

    /** 归集：用户付给平台户，平台是销售主体（代销）。**这条路径没有补差动作** */
    String FUNDS_AGGREGATED = "AGGREGATED";
    /** 直连：用户付给商家二级户，平台分账。**只有这条路径需要补差** */
    String FUNDS_DIRECT = "DIRECT";

    /** 自营：平台是销售主体。取值域与 Port 同处——调用方不必依赖商家域就能判断。 */
    String MODE_SELF_OPERATED = "SELF_OPERATED";
    /** 第三方：商家是销售主体，平台收佣金。 */
    String MODE_THIRD_PARTY = "THIRD_PARTY";

    java.util.List<String> storeNos(String merchantNo);

    /**
     * 门店 → 它属于哪个主体。批量，列表页专用。
     *
     * <p>自提点归属改到门店（V16）之后，community 域拿着 {@code store_no} 却要显示
     * 「这个自提点是哪家店承接的」—— 商家名与 logo 仍在主体上。
     *
     * @param storeNos 门店业务键；空集合返回空 Map
     * @return 按 storeNo 索引；查不到的键<b>不出现在结果里</b>（不是 null 值）
     */
    java.util.Map<String, String> entityOfStores(java.util.Collection<String> storeNos);

    /**
     * @param merchantNo   商家业务键
     * @param merchantName 展示名（下单快照用，商家改名不影响历史订单）
     * @param canSell      是否可上架售卖（审核通过且未封禁）
     * @param canReceive   是否可收款（分账接收方已报备，ADR-002）
     */
    /**
     * @param ratingCount 计入评分的评价条数。**没有它就分不清「0 分」和「还没人评过」**——
     *                    对买家这是相反的信号：0 分是被打出来的，没人评过只是新开的
     */
    record MerchantBrief(String merchantNo, String merchantName, boolean canSell, boolean canReceive,
                         String logo, double rating, int ratingCount,
                         boolean verified, int breachCount) {
    }

    /**
     * 这家店能不能用积分 —— <b>四级串联</b>：全局 → 社区 → 主体非小微 → 本店开关。
     *
     * <p>放在 Port 上而不是让 settle 域自己读四张表：判断顺序本身是有语义的，
     * 主体这一级必须排在商家开关<b>之前</b> —— 小微是「不可开」不是「关着」，
     * 提示语要说「升级为个体工商户后可开启」。两处各实现一遍，
     * 迟早有一处把顺序写反，而那时小微商家会看到「本店未开启积分」，
     * 以为自己打开就行。
     *
     * @return 不可用的原因（<b>直接展示给用户</b>）；可用时返回 {@code null}
     */
    String pointsDenyReason(String merchantNo);

    /** 平台按行业强制开启积分，商家不可自行关闭。 */
    boolean isPointsForced(String merchantNo);

    /**
     * 这家店<b>获批经营哪些类目</b>（{@code mch_entity.category_codes}）。
     *
     * <p>product 域上架商品时，拿它与 {@code prd_category.required_code} 比对。
     * 走 Port 而不是让 product 直接读 {@code mch_entity}：那是跨业务域的直连，
     * ArchUnit 第 1 条就会拦下来 —— 而规则拦的正是这种「为了一个字段捅穿一层边界」。
     *
     * <p><b>空集合表示没有任何特许类目</b>，只能上架无门槛的类目 —— 不是「不限制」。
     * 反过来默认放开的话，卖烧烤的第二天就能上架生鲜，而平台从没校验过。
     */
    java.util.Set<String> authorizedCategoryCodes(String merchantNo);

    /**
     * 该商家是否存在**已过期**的资质。
     *
     * <p>上架时当场拦一道。与定时扫描是两道防线，针对不同时机：
     * 定时任务覆盖「已经在架的」，这条覆盖「正要上架的」——
     * 任务有间隔，而上架随时发生。
     *
     * <p><b>没有资质记录时返回 false</b>（不拦）：存量商家都还没补录，
     * 一律拦会把他们全部挡死。补录是运营任务，不该由这条校验代劳。
     */
    boolean hasExpiredQualification(String merchantNo);

    /**
     * 这家商户的<b>所有人是哪个 C 端用户</b>。
     *
     * <p>准入矩阵的降级判定要用它：邻居自提点的 {@code owner_ref} 存的是用户号，
     * 要判「供货方就是自提点运营者」，只能在用户号这一层比 ——
     * 商户号与用户号不是一个命名空间，比不了。
     */
    java.util.Optional<String> ownerUserNoOf(String merchantNo);

    /**
     * 这家店的<b>收款能力</b>：支持哪些支付方式、能不能开票、额度还剩多少。
     *
     * <p>三件事合在一个查询里，因为它们来自同一行（{@code mch_payment_merchant}），
     * 而调用方（结算页、下单）每次也是三件一起要。分成三个方法只会让同一行被查三遍。
     *
     * <p>解析不到收款记录时返回一个<b>全放行</b>的对象而不是空：
     * 进件还没走完的商家不该因此不能下单 —— 钱是欠着的，不是不能成交。
     */
    PayCapability payCapabilityOf(String merchantNo, String storeNo);

    /**
     * @param payMethods   该商家可用的支付方式（JSAPI/H5/APP/NATIVE）。
     *                     <b>小微通常没有 H5 与 APP</b>，混合购物车里有一件小微的货，
     *                     整单就只能走交集里剩下的那几种
     * @param invoiceCapable 能否开票。小微免登记、无票，<b>这件事必须在付款前告诉用户</b>——
     *                     买完才发现开不了票，平台补救不了
     * @param quotaLimitMinor 收款额度上限；<b>0 = 未设置</b>（不是"额度为零"）
     */
    record PayCapability(java.util.Set<String> payMethods, boolean invoiceCapable,
                         long quotaLimitMinor, long quotaUsedMinor) {

        /** 额度已用尽。未设置额度（0）时恒为 false —— 没核对过的阈值不能拿来拦单。 */
        public boolean quotaExhausted() {
            return quotaLimitMinor > 0 && quotaUsedMinor >= quotaLimitMinor;
        }

        /** 再来 {@code amountMinor} 会不会超。 */
        public boolean wouldExceed(long amountMinor) {
            return quotaLimitMinor > 0 && quotaUsedMinor + amountMinor > quotaLimitMinor;
        }
    }

    /**
     * 某行业下有多少商家。
     *
     * <p>运营改行业准入前要知道影响面 —— 把一个有 300 家店的行业停掉，
     * 和停一个空行业，是两件事。
     */
    long countByIndustry(String industry);

    /**
     * 某个经营范围档下有多少商家（ADR-009 三档）。
     *
     * <p>与 {@link #countByIndustry} 同一个用途：运营在后台关掉某一档之前要知道影响面。
     * 不带计数的开关是**盲操作** —— 关掉 CITY 和关掉一个没人用的档，看起来完全一样。
     */
    long countByServiceScope(String serviceScope);

    /**
     * 有多少商家持有某个类目授权码。
     *
     * <p>停用一个码不会撤销存量商家的授权（与行业同一口径），但运营要知道
     * 这一停会让多少家店从此上不了新品。
     */
    long countByAuthCode(String code);
}
