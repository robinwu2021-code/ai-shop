package ai.neargo.shop.merchant.service;

/**
 * 增值包与额度（P-11.2，TDD-增值包与门店额度）。
 *
 * <p>它回答两个问题：**这个商家能开几家店 / 加几个人**，以及**他有没有跨店数据这个能力**。
 * 在此之前第一个问题的答案是一个全局配置 `shop.store.max-per-entity` ——
 * 对所有商家同一个数。
 */
public interface MerchantPlanService {

    /**
     * 当前订阅。**永远有值**：库里没有行时返回 FREE 的兜底视图
     * （额度取配置 `shop.store.max-per-entity`），因为额度校验每次建店都要读它，
     * 返回 null 会让「还没回填的主体」变成一次 NPE 而不是「按免费档处理」。
     */
    PlanVO current(String merchantNo);

    /**
     * 建店前的额度闸。**必须在事务内调用**：它会锁住订阅行。
     *
     * <p><b>为什么要锁</b>：「数一下现在几家 → 小于额度就建」在两个请求同时进来时
     * 会双双通过 —— FREE 额度 1 的商家能开出两家店，而两次请求各自看都是合法的。
     * 竞争只发生在同一主体内部，代价可以忽略。
     *
     * <p>超额抛 {@code STORE_QUOTA_EXCEEDED}，消息里带三个数：
     * 现在几家、上限几家、怎么解决 —— 只说「额度不足」，商家的下一步是打客服电话。
     *
     * <p><b>入参是「怎么数」而不是「数了多少」</b>，这一点是被实测逼出来的：
     * 传数值的版本里，调用方先数、后调本方法加锁 —— 两个事务各自拿着**加锁前的旧计数**
     * 通过，锁形同虚设（实测两个并发请求双双建店成功）。
     * 传 supplier 之后，「先锁、再数」的顺序由本方法保证，调用方写不错。
     *
     * @param countActive 在**锁生效之后**才被调用，用来数当前 ACTIVE 门店数
     */
    void requireStoreQuota(String merchantNo, java.util.function.IntSupplier countActive);

    /** 子账号额度闸。语义同 {@link #requireStoreQuota}。 */
    void requireStaffQuota(String merchantNo, java.util.function.IntSupplier countActive);

    // ---------------------------------------------------------------- 商家端（B-11.13）

    /**
     * 商家自己那一份（P4 步骤 4.0）。
     *
     * <p><b>用量必须由后端算</b>：端上自己数门店会与额度闸的口径分岔 ——
     * 闸门只数 {@code ACTIVE}，而端上手里的列表通常含停用的店。分岔的表现是
     * 「页面显示 3/3 已满，实际还能建一家」或者反过来，两种都会让商家不再相信这个数。
     *
     * <p>顺带把**三档对比**也放在同一个响应里，而不是让端上再调一次档位定义接口：
     * 那个接口挂的是运营的权限码，商家调不到；而为它开一条 B 端出口，
     * 等于把「有哪些档位」这件事拆成两次请求 —— 套餐页永远是一起显示的。
     */
    MinePlanVO mine(String merchantNo);

    /**
     * 自助开通试用（步骤 4.4）。
     *
     * <p><b>一主体一次，永不回退</b>：{@code trial_used} 置位后不再清除 ——
     * 允许重开等于把付费档变成免费档，而「反复试用」不需要任何技巧就能发现。
     *
     * <p>试用的目标档位不写死：取**可试用（{@code trial_days > 0}）且在售的档位里
     * sort 最小的那个** —— 也就是他要升的下一档。写死 PRO 的话，
     * 哪天运营在 FREE 与 PRO 之间插一档，试用就会跳过它直接送出更贵的能力。
     *
     * @return 开通后的视图，与 {@link #mine} 同形状 —— 端上拿到就能直接重渲染
     * @throws ai.neargo.shop.common.BizException 已用过试用 / 已经是付费档 / 没有可试用的档位
     */
    MinePlanVO startTrial(String merchantNo);

    // ---------------------------------------------------------------- 运营端（P-11.2）

    /**
     * 到期与降级看板（P-11.2.5）。
     *
     * @param filter {@code EXPIRING_7D} 七天内到期 / {@code GRACE} 宽限期中 /
     *               {@code DOWNGRADED} 已降级；为空 = 全部
     */
    ai.neargo.shop.common.PageData<PlanRowVO> search(String filter, String keyword,
                                                     long page, long size);

    /**
     * 授予 / 延长（P-11.2.2）。谈下来的连锁客户不走自助付费。
     *
     * <p><b>快照按迁移类型刷新</b>（TDD §4.7.1，四种各不相同）：
     * <ul>
     *   <li>换档（升档/降档到 PRO/CHAIN）与同档续费 → <b>重读档位定义写新快照</b>，
     *       那是一次新的购买行为，按当下的档位定义成交</li>
     *   <li>从 GRACE/EXPIRED 补缴回来（档位不变、不延长）→ <b>不动快照</b>，
     *       他买的是当初那个额度，中途运营下调档位定义不该殃及他</li>
     * </ul>
     *
     * @param months 延长月数；{@code null} 或 0 = 只补缴不延长（走上面第二条）
     * @param reason 必填 —— 它决定商家能开几家店，没有理由的授予在复盘时说不清
     */
    PlanRowVO grant(String merchantNo, String planCode, Integer months, String reason,
                    String operatorNo);

    /**
     * 单商家额度覆盖（P-11.2.4）。传 {@code null} 清除覆盖、回到档位快照。
     *
     * <p><b>写进单独的 override 列而不是改快照</b>：混在一起就分不清
     * 「这个数是档位给的还是单独谈的」，而下次调档位定义时没人知道该不该动它。
     */
    PlanRowVO overrideQuota(String merchantNo, Integer storeQuota, Integer staffQuota,
                            String reason, String operatorNo);

    /** 档位定义（P-11.2.3）。**改了只影响之后新订阅的人** —— 接口文案要把这句说出来。 */
    java.util.List<PlanDefVO> defs();

    PlanDefVO saveDef(String planCode, int storeQuota, int staffQuota, boolean crossStoreStats,
                      int trialDays, boolean enabled, String operatorNo);

    /**
     * 升档信号（P-11.2.6）：**同一手机号/联系人开了两个以上主体的商家**。
     *
     * <p>他其实需要多门店但绕开了 —— 这是最该被销售找到的一批。
     * 不做复杂画像，一条查询能出的信号先上。
     */
    java.util.List<UpgradeSignalVO> upgradeSignals();

    /**
     * 到期推进一轮（定时任务调）：{@code ACTIVE → GRACE → EXPIRED}，
     * EXPIRED 时执行降级。**靠 {@code downgraded_at} 幂等**，重跑不重复压。
     *
     * @return 本轮推进的主体数（进 GRACE / 进 EXPIRED 各计）
     */
    SweepResult sweepExpiry(long now);

    /**
     * @param storeUsed  已用门店数（**只数 ACTIVE**，与额度闸同一口径 ——
     *                   两处不一致的表现是「看板说 2/3，建店却被拒」）
     * @param quotaSource PLAN / OVERRIDE / CONFIG，运营要看得出这个额度是哪来的
     */
    record PlanRowVO(String merchantNo, String merchantName, String planCode,
                     int storeQuota, int staffQuota, int storeUsed, int staffUsed,
                     boolean crossStoreStats, String status, Long startAt, Long expireAt,
                     String grantedBy, boolean trialUsed, Long downgradedAt,
                     String quotaSource) {
    }

    record PlanDefVO(String planCode, String name, int storeQuota, int staffQuota,
                     boolean crossStoreStats, int trialDays, boolean enabled,
                     int subscriberCount) {
    }

    /**
     * 升档信号的一行。
     *
     * <p><b>按 {@code ownerUserNo} 分组而不是按手机号</b>：主体表上没有联系电话
     * （那在申请单上），而 owner 是真实的身份关联 —— 「同一个人开了两个主体」
     * 这件事本身就记在这一列上。
     *
     * @param entityNos 这个人名下的全部主体 —— 销售要一次看到他有几家
     */
    record UpgradeSignalVO(String ownerUserNo,
                           java.util.List<String> entityNos,
                           java.util.List<String> entityNames, int entityCount) {
    }

    record SweepResult(int toGrace, int toExpired, int storesSuspended) {
    }

    /** 有没有跨店总览与对比的能力位（B-11.12.5/6）。 */
    boolean canCrossStoreStats(String merchantNo);

    /**
     * @param storeQuota 生效额度 = 覆盖值优先，否则快照，否则配置兜底
     * @param source     这个额度是哪来的：PLAN（档位快照）/ OVERRIDE（单独谈的）/ CONFIG（还没回填，走兜底）
     */
    record PlanVO(String merchantNo, String planCode, int storeQuota, int staffQuota,
                  boolean crossStoreStats, String status, Long expireAt,
                  String grantedBy, boolean trialUsed, String source) {

        public static final String FROM_PLAN = "PLAN";
        public static final String FROM_OVERRIDE = "OVERRIDE";
        public static final String FROM_CONFIG = "CONFIG";
    }

    /**
     * 商家自己看的那一份。
     *
     * @param planName          档位显示名（「成长版」）。**端上不要按 code 自己映射文案** ——
     *                          运营改了档位名，端上那份映射表不会跟着变
     * @param storeUsed         已用门店数，**只数 ACTIVE**，与额度闸同一口径
     * @param suspendedStores   因降级被压成只读的门店名。步骤 4.3：套餐页要写明是**哪几家** ——
     *                          只说「部分门店已停用」，商家得自己一家家点开去找
     * @param trialTier         可试用的目标档位码；null = 现在不能试用
     *                          （已用过、已经是付费档、或没有配置可试用的档位）
     * @param tiers             三档对比。顺序按 {@code sort}，端上照原序渲染
     */
    record MinePlanVO(String planCode, String planName, String status, Long startAt, Long expireAt,
                      int storeQuota, int storeUsed, int staffQuota, int staffUsed,
                      boolean crossStoreStats, boolean trialUsed, String trialTier,
                      Integer trialDays, java.util.List<String> suspendedStores,
                      java.util.List<TierVO> tiers) {
    }

    /** @param current 这一档是不是他现在用的 —— 端上不必拿 planCode 再比一次 */
    record TierVO(String planCode, String name, int storeQuota, int staffQuota,
                  boolean crossStoreStats, int trialDays, boolean current) {
    }
}
