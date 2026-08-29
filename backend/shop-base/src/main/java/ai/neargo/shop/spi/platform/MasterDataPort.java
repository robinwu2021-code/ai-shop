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
     * 区划码 → 是不是村委会（{@code sys_region.rural}）。只对第五级有意义。
     *
     * <p>与 {@link #regionNames} 同一个用途分开成两个方法，而不是塞进一个 DTO——
     * 大多数调用点只要名字，不要这个判据；不拆开会让不需要它的调用方也背上一次
     * 额外的列读取与序列化。
     *
     * @return 查不到的码不出现在结果里，与 {@link #regionNames} 同一个约定
     */
    java.util.Map<String, Boolean> regionRural(java.util.Collection<String> regionCodes);

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

    /**
     * 按「区县码 + 街道名」定位街道（9 位码）。
     *
     * <p><b>为什么不能用高德的 towncode</b>：两套编码不同源 —— 实测福城街道的
     * towncode 是 440309006000，去掉后三位是 440309006，而那在统计局口径里是**观澜街道**。
     * 按码挂会把聚落挂到隔壁街道，且没有任何报错，等到商家发现「货对那边可见、这边不可见」
     * 已经过去很久。区县码两边一致，街道按名字找才对得上。
     *
     * @return 找不到返回空 —— 调用方据此拒绝，而不是挂到一个猜出来的街道上
     */
    java.util.Optional<String> streetByDistrictAndName(String adcode, String townshipName);

    /**
     * 从「地址文本 + 坐标」推断该挂哪个街道。运营裁决那一屏用它免去从 31 个省点起。
     *
     * <p><b>走 Port 而不是让 community 域直连 platform.RegionService</b> ——
     * 后者是跨域直连，ArchUnit 第 1 条会拦（我第一版就是这么写的，被拦了）。
     * 规则拦的正是这种「为了一个查询捅穿一层边界」：捅一次之后，
     * 下一个人会顺手用上 RegionService 的别的方法，两个域就再也拆不开了。
     */
    java.util.List<RegionSuggestion> resolveRegion(String address, Integer latE6, Integer lngE6);

    /**
     * @param source ADDRESS 地址文本推断 / COORDS 坐标最近邻
     * @param detail 给运营看的依据（匹配到的地址片段，或「茜坑社区 · 320 米」）
     */
    record RegionSuggestion(String regionCode, String level, String name, String path,
                            String source, String detail) {
    }
    /**
     * 该市场下启用中的支付通道码，按注册顺序。空列表是合法结果 ——
     * 调用方自己处理「一个都没有」，<b>不要在这里兜一个默认通道</b>。
     */
    java.util.List<String> enabledChannels(String market);

}
