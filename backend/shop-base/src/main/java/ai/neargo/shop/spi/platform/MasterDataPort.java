package ai.neargo.shop.spi.platform;

/**
 * 任意域 → platform：读主数据（主体类型 / 行业 / 渠道的规则）。
 *
 * <p>只暴露<b>判断</b>，不暴露表：调用方要的从来不是「这一行长什么样」，
 * 而是「这个主体要不要执照」「这个旧取值对应哪个权威码」。
 * 返回整行的话，各域会顺手用上不该用的列，将来 platform 改一列就要改三个模块。
 *
 * <p><b>为什么这件事必须集中</b>：「PERSONAL 是不是就是小微」这个判断
 * 此前在代码里出现过<b>三次</b>，各写各的 —— 建商家一次、建分账主体一次、
 * 入驻校验一次。判错一次的后果不是显示错误，是商家进件被通道拒。
 */
public interface MasterDataPort {

    /**
     * 把任意主体取值翻译成权威码（通道口径 MICRO / INDIVIDUAL / ENTERPRISE）。
     *
     * <p>存量数据里是 PERSONAL / INDIVIDUAL_BIZ / COMPANY，两套并存期间
     * 一切读写都要先过这里。
     *
     * @return 传入的已是权威码时原样返回；认不出来返回 null
     */
    String canonicalSubject(String anySubject);

    /** 该主体（权威码）是否受行业白名单限制。仅小微为 true。 */
    boolean industryGated(String subjectType);

    /** 该主体（权威码）的结算账户形态：PERSONAL_BANK_CARD / MERCHANT_ID。查不到返回 null。 */
    String settleAccountType(String subjectType);

    /**
     * 该主体（权威码）是否<b>需要营业执照</b>（{@code sys_legal_form.need_license}）。
     *
     * <p><b>查不到一律返回 true</b>（当作需要执照）。取向与 {@code canSell} 一致：
     * 新增一个档位而忘了配这一列时，「查不到 = 不需要执照」会静默放出一个
     * 本该被管控的主体，而症状是税务或合规问题 —— 那类问题不报错，
     * 且要到季度结账才看得出来。
     *
     * <p>用它而不是在代码里写 {@code "MICRO".equals(x)}：哪一档免执照是
     * <b>注册表说了算</b>，而主体档位的取值正在改造中。写死取值的地方，
     * 改造那天会静默失配 —— 不报错，只是判断结果全变。
     */
    boolean needLicense(String subjectType);

    /**
     * 该通道能否**补差**（{@code sys_pay_channel.supports_subsidy}）。
     *
     * <p>积分抵扣要求平台在分账前把差额补进二级商户账户，否则商家收到的钱
     * 与订单金额对不上。<b>只在直连路径上有意义</b> —— 归集路径不发起补差。
     *
     * <p><b>查不到返回 false</b>：这个字段建出来就是为了拦截，
     * 而「查不到 = 支持」会让不具备补差能力的通道静默开出积分抵扣，
     * 症状是商家账上少一笔钱，且没有任何一处报错。
     */
    boolean supportsSubsidy(String payChannel);

    /**
     * 支付通道的展示名（{@code sys_pay_channel.name}）。
     *
     * <p>放在这里而不是让端上写死一份：通道改名（"微信支付" → "微信收付通"）时
     * 三端各改一次必然漏一处，而漏掉的那处会长期显示一个不存在的名字。
     *
     * @return 查不到时返回通道码本身，<b>不返回 null</b> —— 页面上宁可显示 WECHAT，
     *         也不要显示一个空白的支付方式
     */
    String channelName(String payChannel);

    /**
     * 校验经营范围：先值域（ADR-009 三档），后启用白名单（{@code sys_setting}）。
     *
     * <p>放在这里而不是让各域自己判：写经营范围的入口有两个（商家改门店、运营审入驻），
     * 各写一份的话，一期收敛时必然只改到其中一个 —— 而漏掉的那个正是商家自己能走的路径。
     *
     * @param scope 为空表示不改这个字段，直接放行
     * @throws ai.neargo.shop.common.BizException 值域非法或这一期未开放
     */
    void assertServiceScopeAllowed(String scope);

    /**
     * 区划码 → 「浙江省 / 杭州市 / 西湖区」这样的整条路径名（ADR-013）。
     *
     * <p>给整条而不只给末级：光一个「西湖区」，全国有好几个同名的 ——
     * 商家在自己的覆盖清单里看到两条都叫「西湖区」，分不出删哪条。
     *
     * @return 查不到时返回码本身，不返回空 —— 空白的覆盖项会被当成坏数据删掉
     */
    String regionPathName(String regionCode);

    /**
     * 区划码 → <b>末级名</b>（「330106」→「西湖区」）。批量查，一次一条会打出 N 次查询。
     *
     * <p>与 {@link #regionPathName} 并存而不是复用：那个给的是整条路径
     * （「浙江省 / 杭州市 / 西湖区」），适合在覆盖清单里消歧；
     * 而选区域的界面要把市与区**分两级排版**，需要的是拆开的名字。
     *
     * @return 查不到的码<b>不出现在结果里</b>（而不是回落成码本身）——
     *         调用方据此判断「这个码是脏数据」，回落会让脏数据看起来像正常区划
     */
    java.util.Map<String, String> regionNames(java.util.Collection<String> regionCodes);

    /**
     * 区划中心点（gcj02，E6，V192 起由高德批量补录）。
     *
     * <p>裁决提报时用它兜底：商家没带定位的提报，只要关联了官方村码，就能从这里取到坐标 ——
     * 否则建出来的聚落坐标为空，而 {@code withinRadius} 对空坐标恒 false，买家永远搜不到它，
     * 且这件事没有任何报错，运营与商家都看不出来。
     *
     * @return 没补录到坐标的区划返回空
     */
    java.util.Optional<RegionCoords> regionCoords(String regionCode);

    record RegionCoords(int latE6, int lngE6) {
    }

    /**
     * 这个码是不是**官方名录里的村**（第五级、source=OFFICIAL），是的话给出它所属街道码。
     *
     * <p>用途：官方村的提报免运营裁决直接开通 —— 数据源是统计局名录、`origin_code` 天然唯一、
     * 后端已有一村一聚落的查重，运营审它基本是走过场，而那道等待要按天算，
     * 期间商家的货对这个村一个人也看不见。
     *
     * <p><b>只认官方那批</b>：商家自己补录的村（source=MERCHANT）仍要审 ——
     * 名字是他自己起的，免审等于谁都能凭空造聚落。
     */
    java.util.Optional<String> officialVillageStreet(String regionCode);
}
