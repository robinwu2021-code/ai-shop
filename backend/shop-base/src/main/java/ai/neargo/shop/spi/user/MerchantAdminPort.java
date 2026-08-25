package ai.neargo.shop.spi.user;

import java.util.List;

/**
 * platform → user：创建/激活商家主体。
 *
 * <p>两条路进来：
 * <ul>
 *   <li>{@link #activate} —— 交执照、平台审过之后激活成 {@code ACTIVE}，能被买家看到、能收款</li>
 *   <li>{@link #quickStart} —— 无证照先开店，落成 {@code PENDING_LICENSE} 占位主体，
 *       补齐证照前对买家不可见</li>
 * </ul>
 *
 * <p><b>驳回的申请不该在库里留下「僵尸商家」</b>：那些记录会出现在商家列表、报表、
 * 分账接收方清单里，谁也说不清它算不算数。所以走审核那条路的，必须审过才建。
 */
public interface MerchantAdminPort {

    /**
     * 激活商家。<b>建商家 + 配可达范围 + 建分账主体，三件事在一个事务里</b>。
     *
     * <p>为什么不拆成三次调用：拆开就意味着可以只做前一件。
     * 而只建商家不配范围的结果是 —— 商家审核通过、登录 B 端、上架商品，
     * 然后<b>一个订单都不来</b>，他和运营都查不出原因（ADR-009：
     * {@code service_scope} 默认 COMMUNITY，而一个社区都没覆盖 = 对谁都不可见）。
     * 这个故障没有任何报错，只有「生意没来」。
     *
     * @param cmd 激活参数
     * @return merchantNo
     */
    String activate(ActivateCommand cmd);

    /**
     * 无证照快速开店：<b>不经审核</b>，当场建出一个「待补证照」的主体与它的默认门店。
     *
     * <p>与 {@link #activate} 是同一件事的两条路：那条要先交执照、等平台审；
     * 这条让老板先把店开起来（录商品、配范围、加员工），
     * <b>补齐证照之前买家看不到、也下不了单</b>（闸门在
     * {@code MerchantQueryPort.reachableCommunities}：非 ACTIVE 主体一律返回空）。
     *
     * <p>为什么要建一个占位主体而不是让门店没有主体：{@code mch_store.entity_no} 是
     * NOT NULL，且整个 B 端权限模型都挂在 {@code BizContext.merchantNo} 上 ——
     * 真让它可空的话，{@code merchantNo} 为 null，所有 {@code /biz/**} 直接 403。
     *
     * <p><b>一个账号最多一个待补证照的占位主体</b>：已经有就原样返回它，不建第二个。
     * 这既是防连点，也避免账号里堆出一串永远补不齐的空壳。
     * 想在这个占位主体下再开一家店，走正常的建店接口即可（那时已经有 BizContext）。
     *
     * @return 新建（或既有）的占位主体 + 它的默认门店号，见 {@link QuickStartResult}
     */
    QuickStartResult quickStart(QuickStartCommand cmd);

    /**
     * 这个账号名下「待补证照」的占位主体（{@code PENDING_LICENSE}）。
     *
     * <p>入驻申请提交时用它认领：有占位主体就把申请单的 {@code entity_no} 预填成它，
     * 于是审核通过时 {@link #activate} 走「已存在，就地升级」那一支 ——
     * <b>他先开的那家店、录的那些商品原样留着</b>，只是从此对买家可见。
     * 不认领的话会另建一个主体，那家店和它的货就永远留在看不见的旧主体下。
     *
     * @return 没有占位主体时 empty（正常入驻的人就是这种情况）
     */
    java.util.Optional<String> pendingLicenseEntityOf(String userNo);

    /**
     * @param ownerUserNo 发起人（当前登录账号）
     * @param storeName   老板填的店名。<b>同时用作主体名</b> —— 补证照时再被执照上的正式名称覆盖
     * @param address     门店地址，可空（之后在店铺资料里补）
     */
    record QuickStartCommand(String ownerUserNo, String storeName, String address) {
    }

    /**
     * <b>门店号也要返回</b>，不只是主体号。
     *
     * <p>因为「他刚开的是哪家店」这件事，调用方没有别的途径知道：这个账号名下
     * 可能已经有另一张执照（多证照），那时按默认主体解析出来的是<b>旧的那家</b>——
     * 端上会拿到一个「建成功了」的响应，里面却是上一家店的资料。
     *
     * <p>门店号同时是端上进这家新店要用的 {@code X-Store-No}：身份解析按门店反查主体
     * （见 {@code BizIdentityResolver}），没有它就进不去刚建好的店。
     */
    record QuickStartResult(String entityNo, String storeNo) {
    }

    /**
     * 授予经营类目编码。**与审核通过同一个事务**（商品域-优化总方案 批 B）。
     *
     * <p>拆成两步的后果不是「少一步」，是多出一个状态：<b>通过了，但一个码都没授</b>。
     * 商家收到通过通知、进去建品、点上架被拒，看到的是「你还没有资质授权」——
     * 去哪申请没人告诉他。这与「有门槛没有发证机关」是同一个形状。
     *
     * <p>码为空是合法的：只卖无门槛类目的商家本来就不需要任何码。
     * 认不出的码<b>直接抛</b> —— 写进去一个不存在的码等于一个永不命中的授权，静默失效。
     */
    void grantCategoryCodes(String entityNo, java.util.List<String> codes);

    /**
     * @param serviceScope   COMMUNITY / CITY / PLATFORM（ADR-009）
     * @param communityNos   scope=COMMUNITY 时<b>必须非空</b>，否则该商家对谁都不可见
     * @param settleAccountType 分账账户类型（ADR-002）。为空表示申请时没填，
     *                          通过后由商家在 B 端补 —— 但分账主体记录要先建出来占位，
     *                          否则第一笔订单来了才发现没有收款方
     */
    /**
     * @param industry    行业（{@code sys_industry.industry}）。
     *                    <b>决定这家店能不能以小微主体进件</b>（微信小微白名单按行业给），
     *                    也是 {@code points_forced} 默认值的来源。
     *                    此前申请单上存了行业却传不过来 —— 商家主体的行业永远是空的，
     *                    于是进件时才发现选错了主体
     * @param description 店铺简介。同样此前只存在申请单上：<b>C 端门店页读的是
     *                    {@code mch_entity.description}</b>，商家认真写的简介
     *                    通过审核后就消失了，而这不会报错，只是门店页少一段字
     */
    /**
     * @param activatedEntityNo 这份申请<b>之前是否已经激活过</b>：非空表示激活过，
     *                          本次是重复点击「通过」，按幂等重放到这个主体上；
     *                          为空表示首次激活，一律新建主体。
     *
     *                          <p><b>幂等判据必须是申请单，不能是人</b>。曾经按
     *                          {@code owner_user_no} 判重：老板申请<b>第二张执照</b>、
     *                          审核通过时被当成重复点击，系统去改<b>第一个主体</b> ——
     *                          名称/行业/法律形态被覆盖，两家店变一家，
     *                          <b>全程没有任何报错</b>，商家只看到「审核通过了」。
     */
    record ActivateCommand(String ownerUserNo, String name, String subject,
                           String serviceScope, List<String> communityNos,
                           String settleAccountType, String industry, String description,
                           String activatedEntityNo) {
    }

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b>：不动已发出的分，也不退已扣的服务费 ——
     * 否则关一次开关就是一次资金事故。
     */
    /**
     * 把入驻申请里的结构化资质**转存**成主体档案上的资质记录。
     *
     * <p><b>这是一条断了的链</b>：商家在入驻时传的执照，此前只存进
     * {@code mch_entity_apply.qualifications}，审核通过时没有任何一处把它转过来。
     * 而上架的两个闸门（资质过期、类目授权）读的是 {@code mch_qualification} ——
     * 那张表实测 <b>0 行</b>，于是两个闸门都写好了、都从不触发。
     *
     * <p><b>幂等</b>：按 {@code (entityNo, qualType, qualNumber)} 去重。
     * 审核接口会被重复点击，而资质重复写入会让「这家店有几张执照」变成一个假数字。
     *
     * @param items 结构化资质；空或 null 时什么也不做（免执照档位本来就没有）
     * @return 本次实际新增的条数 —— 调用方用它写审计日志，
     *         「转存了 0 条」与「没调用」在排查时是两件事
     */
    int saveQualifications(String merchantNo, java.util.List<QualificationItem> items);

    /**
     * @param expireAt 有效期截止（毫秒）。<b>null = 长期有效</b> ——
     *                 过期扫描按 null 跳过，不要用 0 或极大值冒充
     */
    record QualificationItem(String type, String code, String imageUrl,
                             Long expireAt, String issuer) {
    }

    void setPointsEnabled(String merchantNo, boolean enabled);

    /**
     * 支付成功后累加该店的<b>收款额度用量</b>。
     *
     * <p>微信对小微商户的收款有累计额度，超了之后收款直接失败。
     * 不累加的话系统永远不知道用掉了多少 —— 它只会在某个买家付款的那一刻
     * 表现为「支付失败」，而那时候平台既解释不清也补救不了。
     *
     * <p><b>周期翻篇时清零重算</b>：周期标识由实现按当前时间算，
     * 调用方不传 —— 传进来的话，补发的历史回调会把用量记进当前周期。
     *
     * <p>没有收款记录时静默跳过：进件还没走完的商家没有额度可记。
     */
    void accruePayQuota(String merchantNo, String storeNo, long amountMinor);
}
