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
     * 按<b>门店</b>算可达（可见性按门店算 · 第 1 步）。
     *
     * <p>与主体口径的差别只有一处：履约能力与地理子集按这家店取，
     * 而不是取「任何一家门店送得到就算」的并集。多门店之后那个并集口径
     * 会让 A 店的货出现在只有 B 店服务的社区里。
     *
     * @param storeNo 为空时**退回主体并集**，与 {@link #reachableCommunities(String)} 等价 ——
     *                「这家商家覆盖哪儿」这类主体级问题继续用那一个，不该被这次改造波及
     * @return 空表示这家店对谁都不可见
     */
    java.util.List<String> reachableCommunities(String merchantNo, String storeNo);

    /**
     * <b>范围预览</b>：这一组范围行（还没保存）会覆盖到哪些聚落。
     *
     * <p>走的是与 {@link #reachableCommunities(String)} <b>同一段展开代码</b> ——
     * 预览另算一遍的话，两个数字都「算对了」，只是算的不是同一件事，
     * 而商家会照着预览做决定，保存之后才发现覆盖到的不是他看到的那些。
     *
     * <p>{@code EXCLUDE} 的减法用的也是传进来的这一份（不掺库里的旧行）：
     * 预览要回答的是「改成这样之后」，不是「改成这样再叠上旧的」。
     *
     * @param areas 端上正要保存的那一份，每条是 {@code [level, refCode, mode]}；
     *              {@code mode} 空按 INCLUDE
     */
    java.util.List<String> previewReachable(String merchantNo, java.util.List<String[]> areas);

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
     * 批量取门店名（获客看板按门店出行时用）。
     *
     * <p><b>批量而不是逐个</b>：看板一屏几十行，逐行查就是 N+1。
     *
     * @return 门店号 → 门店名；<b>查不到的门店不出现在结果里</b>，
     *     调用方自己决定显示门店号还是留空 —— 那两件事不一样
     */
    java.util.Map<String, String> storeNames(java.util.Collection<String> storeNos);

    /**
     * 批量取默认门店号。
     *
     * <p>与单个的 {@link #defaultStoreNo} 是<b>两件事</b>：那个为了兼容历史调用方
     * （含无数据域上下文的 C 端游客路径）解了数据域；这个<b>接域</b>，
     * 给运营端聚合用 —— 一屏几十家逐个查既是 N+1，又会把域外的默认店带出来。
     *
     * @return 主体号 → 默认门店号；<b>没有默认店的主体不出现</b>
     */
    java.util.Map<String, String> defaultStoreNos(java.util.Collection<String> merchantNos);

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
     * 自送的「圆心 + 半径」。<b>两者缺一这条规则就不成立</b> ——
     * 没有圆心的半径算不出任何东西，而那正是这个设置此前的状态。
     *
     * @return 门店不存在、或没标过点时返回空（调用方据此放行，不拿缺失数据拦单）
     */
    java.util.Optional<DeliveryOrigin> deliveryOrigin(String merchantNo);

    /**
     * 门店坐标健康度。**运营端唯一能看见「自送半径是不是哑的」的地方。**
     *
     * <p>没标点的门店，{@code requireWithinDeliveryRadius} 那条闸直接放行 ——
     * 商家以为自己限了三公里，实际多远的单都进来，等他要送货才发现送不到，
     * 那时钱已经收了。而这件事今天在任何界面上都看不见。
     *
     * <p>返回明细而不只是数字：只给「7 家没标点」，运营下一步无从做起。
     */
    StoreCoordHealth storeCoordHealth();

    /**
     * @param missing 没标点的那些。**带上 merchantNo** —— 运营要从这里跳到商家去催
     */
    record StoreCoordHealth(int total, int withCoords, java.util.List<MissingStore> missing) {

        /**
         * @param merchantNo 运营从这里跳到商家去催他标点。
         *                   <b>刻意不带商家名</b>：取名字要走 {@code findAll}，
         *                   而那个方法为 C 端刻意绕开了数据域 —— 从 ops 读路径调它，
         *                   配了域的运营就会看到别家的商家名。
         *                   名字由前端按已授权的 merchantNo 自己去取。
         */
        public record MissingStore(String storeNo, String storeName, String merchantNo,
                                   Integer deliveryRadiusM) {
        }
    }

    /** @param radiusM 0 或负数表示商家没限制距离 */
    record DeliveryOrigin(int latE6, int lngE6, int radiusM) {
    }

    /**
     * 门面文案。字段与契约 `StoreFront` 一一对应。
     *
     * @param status 门店状态 ACTIVE / READONLY / SUSPENDED（V96）——
     *               门店主页要据此显示「已停业」，而不是让停用的店照常收单
     */
    /** @param latE6 门店坐标（gcj02，E6，V191）。没标过点为 null —— 买家侧据此决定显不显示「导航」 */
    /**
     * @param announcementAt 公告最后一次发布的时刻（epoch 毫秒），没发过为 null。
     *                       <b>买家要靠它判断这句话新不新</b> —— 一行没有时间的
     *                       「今天到了新米」，既可能是今早写的也可能是上个月忘了撤的
     */
    record StoreFront(String announcement, Long announcementAt, String openHours, String address,
                      String status, Integer latE6, Integer lngE6) {
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
     * 按买家所在社区裁剪后的门店可用送货方式（P2 范围子集）：
     * enabled 且未被运营锁路，且（scope=ALL 或 社区 ∈ 子集展开）。
     * communityNo 为空时等同 {@link #enabledFulfillments}（子集无从判，按不限）。
     * 空集约定同 {@link #enabledFulfillments}：没配过 = 兼容期不限。
     */
    java.util.Set<String> enabledFulfillmentsFor(String merchantNo, String storeNo, String communityNo);

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

    /**
     * 该主体的<b>法律形态</b>：{@code MICRO} / {@code INDIVIDUAL} / {@code ENTERPRISE}。
     *
     * <p>结算侧要它只为一件事：<b>取通道费率</b> —— 费率是
     * 「通道 × 支付方式 × 主体形态」三维的，少这一维的话，
     * 运营配的「企业专属费率」永远取不到，而这种错不会报警，
     * 只会让某一类商家一直按通用费率结算（与
     * {@code PayChannelRateService.effective} 的匹配顺序注释同一个坑）。
     *
     * @return 查不到返回 {@code null} —— <b>不回落成某一档</b>。
     *         回落等于替这家商户认领了一个它可能没有的形态，
     *         而费率取错的表现是账目静默地差几分钱
     */
    String legalFormOf(String merchantNo);

    /**
     * 这家主体在哪个市场经营。
     *
     * <p><b>取可用支付渠道要用它</b>：渠道按市场打标签，
     * 传 null 的话一律按默认市场算 —— 台湾商家会看到只在大陆可用的渠道，
     * 点进去进件必然被拒，而拒的理由是英文码。
     *
     * @return 市场码；查不到返回 {@code null}，<b>不兜底成 CN</b> ——
     *         兜底会让「没配过」与「配成大陆」在调用方看来一样
     */
    String marketOf(String merchantNo);

    /**
     * 通道手续费<b>由谁承担</b>：{@code MERCHANT} / {@code PLATFORM}
     * （{@code mch_payment_merchant.fee_bearer}）。
     *
     * <p>要落进结算单：手续费金额说明「收了多少」，这一列说明「从谁身上收的」。
     * 只记金额的话，事后没人答得上「这笔是平台让的利还是商家自己出的」。
     *
     * @return 查不到返回 {@code null}；调用方决定用不用库里的默认档
     */
    String feeBearerOf(String merchantNo, String storeNo, String payChannel);

    /**
     * 这个主体在这个通道的<b>账期</b>（{@code mch_payment_merchant.settle_cycle}）。
     *
     * <p>账期是「主体 × 通道」二维的：一家同时开微信和支付宝，两边可以不同。
     *
     * @return 查不到返回 {@code null} —— 调用方与通道那一档取更短的，
     *         而 {@code SettleCycles} 把空当成最短的 T+1。<b>不在这里兜</b>：
     *         兜一个默认值会让「没配过」和「配成 T+1」在调用方看来一样
     */
    String settleCycleOf(String merchantNo, String storeNo, String payChannel);

    /**
     * 资金风控要的两个<b>主库</b>事实：保证金可用额与欠款余额。
     *
     * <p>一次取齐而不是两个方法：它们总是一起被问（判「这一批放出去安不安全」），
     * 分两次就是两次跨域调用 —— 而支付域独立成服务之后，那是两次跨进程往返。
     *
     * <p>查不到一律给 0，<b>不给 null</b>：这两个数进的是算式（集中度 = 批额 / 保证金），
     * null 会让调用方到处判空，而判漏一处就是一次空指针。
     */
    FundRiskFacts fundRiskFacts(String merchantNo);

    /**
     * @param depositAvailableMinor 保证金<b>可用</b>额（实缴 − 理赔占用）。
     *                              用可用而不是实缴：冻结中的那部分正被别的争议占着
     * @param debtBalanceMinor      当前欠款余额
     */
    record FundRiskFacts(long depositAvailableMinor, long debtBalanceMinor) {
    }

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
     * 这些门店的坐标（gcj02, E6）。与 {@link CommunityQueryPort#coordsOfCommunities} 配对，
     * 用来算「哪家店离这个社区最近」。
     *
     * @return 只含**标过点**的门店；没标点的不出现 —— 调用方据此走「算不出距离」那一支
     */
    java.util.Map<String, int[]> coordsOfStores(java.util.Collection<String> storeNos);

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
     * 这家（可指定门店）<b>进件已生效</b>的通道。
     *
     * <p>与 {@link PayCapability#payMethods()} 是<b>两件事</b>：那个是「支付方式」
     * （JSAPI / H5 / APP —— 用户点的那个按钮），这个是「通道」（钱从哪家机构走）。
     * <b>把两者当成一回事是这个域里最容易犯的错</b>：
     * 收银台拿通道码去比支付方式集合，结果永远比不中，
     * 而症状是「进过件的商家一种支付方式都没有」。
     *
     * @return 已生效（ACTIVE）的通道码；没进过件返回空集
     */
    java.util.Set<String> activeChannelsOf(String merchantNo, String storeNo);

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
