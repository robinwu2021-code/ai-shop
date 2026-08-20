package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntityPlan;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** {@link MerchantPlanService} 实现。 */
@Service
public class MerchantPlanServiceImpl implements MerchantPlanService {

    /**
     * 兜底额度：**只在订阅行还不存在时用**（V150 的回填漏了某个主体、或新主体尚未建行）。
     *
     * <p>刻意保留这个配置而不是删掉：一次性删掉会让「表还没回填的主体」建不了店，
     * 而那是一次静默的营业中断。
     */
    @Value("${shop.store.max-per-entity:1}")
    private int fallbackStoreQuota;

    private final EntityPlanMapper planMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper defMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper accountMapper;

    public MerchantPlanServiceImpl(EntityPlanMapper planMapper,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper defMapper,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper entityMapper,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper,
                                   ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper accountMapper) {
        this.planMapper = planMapper;
        this.defMapper = defMapper;
        this.entityMapper = entityMapper;
        this.storeMapper = storeMapper;
        this.accountMapper = accountMapper;
    }

    @Override
    public PlanVO current(String merchantNo) {
        MchEntityPlan row = find(merchantNo);
        return row == null ? fallback(merchantNo) : toVO(row);
    }

    @Override
    public void requireStoreQuota(String merchantNo, java.util.function.IntSupplier countActive) {
        // ★ 先锁、再数。顺序反过来的话，两个事务各自拿着加锁前的旧计数通过（实测过）
        PlanVO plan = lockAndRead(merchantNo);
        int currentActive = countActive.getAsInt();
        if (currentActive >= plan.storeQuota()) {
            /*
             * 不是 BAD_REQUEST：那句「请求参数有误」会让店主回去反复改门店名，
             * 而无论怎么改都一样被拒 —— 他要做的是升套餐，两件事毫无关系。
             *
             * 三个参数是「现在几家、上限几家、怎么解决」——
             * 只说「额度不足」，商家的下一步是打客服电话。
             */
            throw BizException.of(ErrorCode.STORE_QUOTA_EXCEEDED,
                    currentActive, plan.storeQuota(), plan.planCode());
        }
    }

    @Override
    public void requireStaffQuota(String merchantNo, java.util.function.IntSupplier countActive) {
        PlanVO plan = lockAndRead(merchantNo);
        int currentActive = countActive.getAsInt();
        if (currentActive >= plan.staffQuota()) {
            throw BizException.of(ErrorCode.STAFF_QUOTA_EXCEEDED,
                    currentActive, plan.staffQuota(), plan.planCode());
        }
    }

    @Override
    public boolean canCrossStoreStats(String merchantNo) {
        return current(merchantNo).crossStoreStats();
    }

    /**
     * 读订阅并**锁住那一行**（{@code SELECT ... FOR UPDATE}）——
     * 额度校验与随后的插入必须在同一个事务里串行化，见接口上的说明。
     *
     * <p>订阅行不存在时**不加锁**（没有行可锁），走兜底额度。
     * 那种情况下并发建店的窗口仍在，但兜底额度是 1，
     * 而「同一主体同时建两家店」在真实使用里不存在 —— 真要收紧，
     * 应该是把回填补齐让每个主体都有行，而不是在这里加一把锁不住的锁。
     */
    private PlanVO lockAndRead(String merchantNo) {
        MchEntityPlan row = DataScopeContext.executeWithoutScope(() ->
                planMapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                        .eq(MchEntityPlan::getEntityNo, merchantNo)
                        .last("limit 1 for update")));
        return row == null ? fallback(merchantNo) : toVO(row);
    }

    private MchEntityPlan find(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                planMapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                        .eq(MchEntityPlan::getEntityNo, merchantNo).last("limit 1")));
    }

    private PlanVO fallback(String merchantNo) {
        return new PlanVO(merchantNo, MchEntityPlan.FREE, fallbackStoreQuota, 0,
                false, MchEntityPlan.ACTIVE, null, MchEntityPlan.BY_SELF_PAID, false,
                PlanVO.FROM_CONFIG);
    }

    private PlanVO toVO(MchEntityPlan r) {
        /*
         * 覆盖值优先，否则快照。**覆盖是单独一列**，所以「这个数是档位给的还是单独谈的」
         * 一眼可辨 —— 下次调档位定义时才知道该不该动它。
         */
        boolean overridden = r.getStoreQuotaOverride() != null || r.getStaffQuotaOverride() != null;
        int store = r.getStoreQuotaOverride() != null ? r.getStoreQuotaOverride() : nz(r.getStoreQuota());
        int staff = r.getStaffQuotaOverride() != null ? r.getStaffQuotaOverride() : nz(r.getStaffQuota());
        return new PlanVO(r.getEntityNo(), r.getPlanCode(), store, staff,
                Boolean.TRUE.equals(r.getCrossStoreStats()), r.getStatus(), r.getExpireAt(),
                r.getGrantedBy(), Boolean.TRUE.equals(r.getTrialUsed()),
                overridden ? PlanVO.FROM_OVERRIDE : PlanVO.FROM_PLAN);
    }


    // ---------------------------------------------------------------- 运营端（P-11.2）

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** 宽限期：到期后能力全保留 7 天。扣款失败九成与经营无关，当天断掉是把平台的问题转嫁给商家 */
    private static final int GRACE_DAYS = 7;

    @Override
    public ai.neargo.shop.common.PageData<PlanRowVO> search(String filter, String keyword,
                                                            long page, long size) {
        long now = System.currentTimeMillis();
        var w = Wrappers.<MchEntityPlan>lambdaQuery();
        if ("GRACE".equals(filter)) {
            w.eq(MchEntityPlan::getStatus, MchEntityPlan.GRACE);
        } else if ("DOWNGRADED".equals(filter)) {
            w.isNotNull(MchEntityPlan::getDowngradedAt);
        } else if ("EXPIRING_7D".equals(filter)) {
            // 「快到期」只对还在生效的有意义 —— 已经进 GRACE 的归上一个筛选
            w.eq(MchEntityPlan::getStatus, MchEntityPlan.ACTIVE)
                    .isNotNull(MchEntityPlan::getExpireAt)
                    .le(MchEntityPlan::getExpireAt, now + GRACE_DAYS * DAY_MS)
                    .ge(MchEntityPlan::getExpireAt, now);
        }
        w.orderByAsc(MchEntityPlan::getExpireAt).orderByDesc(MchEntityPlan::getId);

        List<MchEntityPlan> rows = DataScopeContext.executeWithoutScope(() -> planMapper.selectList(w));
        List<PlanRowVO> all = rows.stream().map(this::toRow)
                // 关键字筛在内存里做：主体名在另一张表，而订阅总数是几百这个量级
                .filter(r -> keyword == null || keyword.isBlank()
                        || (r.merchantName() != null && r.merchantName().contains(keyword))
                        || r.merchantNo().contains(keyword))
                .toList();
        return ai.neargo.shop.common.PageData.ofAll(all, page, size);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public PlanRowVO grant(String merchantNo, String planCode, Integer months, String reason,
                           String operatorNo) {
        if (reason == null || reason.isBlank()) {
            // 没有理由的授予在复盘时说不清 —— 它决定商家能开几家店
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        MchEntityPlan row = require(merchantNo);
        var def = findDef(planCode);
        if (def == null || !Boolean.TRUE.equals(def.getEnabled())) {
            // 停售的档位不能新授（已订阅的照常用到到期，那是 enabled 的语义）
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        long now = System.currentTimeMillis();
        boolean sameCode = planCode.equals(row.getPlanCode());
        boolean extending = months != null && months > 0;

        /*
         * 快照刷新规则（TDD §4.7.1）—— 四种迁移只有两种行为，判据是「有没有发生新的购买」：
         *
         *   换档 或 续费  → 重读档位定义写新快照。那是一次新的成交，按当下的定义走
         *   只补缴不延长  → **不动快照**。他买的是当初那个额度，
         *                   中途运营下调档位定义不该殃及他（老用户保护，§2.2 同一条理由）
         */
        if (!sameCode || extending) {
            row.setPlanCode(planCode);
            row.setStoreQuota(def.getStoreQuota());
            row.setStaffQuota(def.getStaffQuota());
            row.setCrossStoreStats(Boolean.TRUE.equals(def.getCrossStoreStats()));
        }
        if (extending) {
            /*
             * 顺延起点：还在生效期内就从原到期日接着算，否则从现在算。
             * 从「现在」一律重算会**吞掉他已付未用的那几天** —— 提前续费反而亏，
             * 而那正是我们希望他做的事。
             */
            long base = row.getExpireAt() != null && row.getExpireAt() > now ? row.getExpireAt() : now;
            row.setExpireAt(base + months * 30L * DAY_MS);
            row.setStartAt(row.getStartAt() == null ? now : row.getStartAt());
        }
        row.setStatus(MchEntityPlan.ACTIVE);
        row.setGrantedBy(MchEntityPlan.BY_PLATFORM);

        // 恢复被降级压下的门店与子账号；商家自己停的不动（plan_suspended=0）
        restoreSuspended(merchantNo);
        row.setDowngradedAt(null);

        MchEntityPlan toSave = row;
        DataScopeContext.executeWithoutScope(() -> planMapper.updateById(toSave));
        return toRow(row);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public PlanRowVO overrideQuota(String merchantNo, Integer storeQuota, Integer staffQuota,
                                   String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        if ((storeQuota != null && storeQuota < 0) || (staffQuota != null && staffQuota < 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchEntityPlan row = require(merchantNo);
        // null = 清除覆盖、回到档位快照。**不写进 storeQuota** —— 见接口注释
        row.setStoreQuotaOverride(storeQuota);
        row.setStaffQuotaOverride(staffQuota);
        DataScopeContext.executeWithoutScope(() -> planMapper.updateById(row));
        return toRow(row);
    }

    @Override
    public List<PlanDefVO> defs() {
        List<ai.neargo.shop.merchant.entity.SysMerchantPlanDef> rows =
                DataScopeContext.executeWithoutScope(() -> defMapper.selectList(
                        Wrappers.<ai.neargo.shop.merchant.entity.SysMerchantPlanDef>lambdaQuery()
                                .orderByAsc(ai.neargo.shop.merchant.entity.SysMerchantPlanDef::getSort)));
        /*
         * 带上「有几家在用」：改档位定义的人必须看得到这个数 ——
         * 它是「只影响新订阅」那句话的具体量（已订阅的 N 家不受影响）。
         * 不给这个数，运营改的时候只能凭感觉判断影响面。
         */
        java.util.Map<String, Long> subs = DataScopeContext.executeWithoutScope(() ->
                        planMapper.selectList(Wrappers.<MchEntityPlan>lambdaQuery()))
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        MchEntityPlan::getPlanCode, java.util.stream.Collectors.counting()));
        return rows.stream().map(d -> new PlanDefVO(d.getPlanCode(), d.getName(),
                nz(d.getStoreQuota()), nz(d.getStaffQuota()),
                Boolean.TRUE.equals(d.getCrossStoreStats()), nz(d.getTrialDays()),
                Boolean.TRUE.equals(d.getEnabled()),
                subs.getOrDefault(d.getPlanCode(), 0L).intValue())).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public PlanDefVO saveDef(String planCode, int storeQuota, int staffQuota,
                             boolean crossStoreStats, int trialDays, boolean enabled,
                             String operatorNo) {
        if (storeQuota < 0 || staffQuota < 0 || trialDays < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var def = findDef(planCode);
        if (def == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **只改定义，一个字都不动已订阅的人**（TDD §2.2）。
         * 追溯到存量的后果是：运营把 PRO 从 3 店调成 2 店，
         * 所有现存 PRO 商家的第 3 家店当场变成只读 —— 而他们什么都没做。
         */
        def.setStoreQuota(storeQuota);
        def.setStaffQuota(staffQuota);
        def.setCrossStoreStats(crossStoreStats);
        def.setTrialDays(trialDays);
        def.setEnabled(enabled);
        var toSave = def;
        DataScopeContext.executeWithoutScope(() -> defMapper.updateById(toSave));
        return defs().stream().filter(d -> d.planCode().equals(planCode)).findFirst().orElseThrow();
    }

    @Override
    public List<UpgradeSignalVO> upgradeSignals() {
        /*
         * ★ **接数据域**（批③ 时由 G1 抓出来的一处越权）：这里此前是
         * executeWithoutScope，于是配了商家域的 BD 会在这一页上看到**不归他管的商家名**
         * —— 而这一页的返回体里恰好带着主体名，那就是一次实打实的信息外泄。
         *
         * 代价要说清楚：分组是在**他看得见的主体**上做的。一个人名下有 5 个主体、
         * 而 BD 只负责其中 1 个时，这条信号在他那里根本不出现（size() > 1 不成立）。
         * 这是对的 —— 他也只能去谈他负责的那一家；要看全量得由不设域的人来看。
         */
        List<ai.neargo.shop.merchant.entity.MchEntity> all =
                entityMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery());
        /*
         * 「同一个联系人开了两个以上主体」= 他其实需要多门店，只是绕开了（开两个主体）。
         * 需求 §2.1：那条路能给他「能开店」，给不了「能一起管、能比」——
         * 所以这批人是最该被销售找到的。
         */
        return all.stream()
                .filter(m -> m.getOwnerUserNo() != null && !m.getOwnerUserNo().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        ai.neargo.shop.merchant.entity.MchEntity::getOwnerUserNo))
                .values().stream()
                .filter(g -> g.size() > 1)
                .map(g -> new UpgradeSignalVO(g.get(0).getOwnerUserNo(),
                        g.stream().map(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo).toList(),
                        g.stream().map(ai.neargo.shop.merchant.entity.MchEntity::getName).toList(),
                        g.size()))
                .sorted(java.util.Comparator.comparingInt(UpgradeSignalVO::entityCount).reversed())
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public SweepResult sweepExpiry(long now) {
        int toGrace = 0;
        int toExpired = 0;
        int suspended = 0;

        // ① ACTIVE 且已过期 → GRACE。**能力全保留**，只换状态
        for (MchEntityPlan p : DataScopeContext.executeWithoutScope(() ->
                planMapper.selectList(Wrappers.<MchEntityPlan>lambdaQuery()
                        .eq(MchEntityPlan::getStatus, MchEntityPlan.ACTIVE)
                        .isNotNull(MchEntityPlan::getExpireAt)
                        .lt(MchEntityPlan::getExpireAt, now)))) {
            p.setStatus(MchEntityPlan.GRACE);
            DataScopeContext.executeWithoutScope(() -> planMapper.updateById(p));
            toGrace++;
        }

        // ② GRACE 且宽限期也过了 → EXPIRED + 执行降级
        for (MchEntityPlan p : DataScopeContext.executeWithoutScope(() ->
                planMapper.selectList(Wrappers.<MchEntityPlan>lambdaQuery()
                        .eq(MchEntityPlan::getStatus, MchEntityPlan.GRACE)
                        .isNotNull(MchEntityPlan::getExpireAt)
                        .lt(MchEntityPlan::getExpireAt, now - GRACE_DAYS * DAY_MS)))) {
            // downgraded_at 非空 = 已经压过，重跑不再压（幂等）
            if (p.getDowngradedAt() == null) {
                suspended += downgrade(p.getEntityNo());
                p.setDowngradedAt(now);
            }
            p.setStatus(MchEntityPlan.EXPIRED);
            var def = findDef(MchEntityPlan.FREE);
            p.setPlanCode(MchEntityPlan.FREE);
            p.setStoreQuota(def == null ? 1 : nz(def.getStoreQuota()));
            p.setStaffQuota(def == null ? 0 : nz(def.getStaffQuota()));
            p.setCrossStoreStats(false);
            DataScopeContext.executeWithoutScope(() -> planMapper.updateById(p));
            toExpired++;
        }
        return new SweepResult(toGrace, toExpired, suspended);
    }

    /**
     * 降级：把超出免费额度的门店压成只读，并标记是**平台压的**。
     *
     * <p><b>保留集 = 默认店 + 建店最早的若干家</b>，补满免费额度。规则写死 ——
     * 不让商家选（他在欠费当天未必看得到通知），也不让系统猜。
     * 默认店必须在保留集里：它是「找不到具体门店时去哪」的答案。
     *
     * <p><b>只改「能不能接新单」，不动任何已有订单</b> —— 未完成的单照常核销。
     * 欠费当天还有几十个待取货订单，把它们一起冻住，受损的是买家和取货点，
     * 而他们与这笔欠费无关。
     *
     * @return 被压成只读的门店数
     */
    private int downgrade(String merchantNo) {
        var def = findDef(MchEntityPlan.FREE);
        int freeQuota = def == null ? 1 : Math.max(nz(def.getStoreQuota()), 1);

        List<ai.neargo.shop.merchant.entity.MchStore> stores = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getStatus,
                                ai.neargo.shop.merchant.entity.MchStore.ACTIVE)
                        .orderByAsc(ai.neargo.shop.merchant.entity.MchStore::getId)));

        // 默认店排到最前，其余按建店时间（id 递增）——保留集就是这个序列的前 freeQuota 个
        List<ai.neargo.shop.merchant.entity.MchStore> ordered = stores.stream()
                .sorted(java.util.Comparator.comparing(
                        (ai.neargo.shop.merchant.entity.MchStore x) ->
                                Boolean.TRUE.equals(x.getIsDefault()) ? 0 : 1)
                        .thenComparing(ai.neargo.shop.merchant.entity.MchStore::getId))
                .toList();

        int n = 0;
        for (int i = freeQuota; i < ordered.size(); i++) {
            var st = ordered.get(i);
            st.setStatus(ai.neargo.shop.merchant.entity.MchStore.READONLY);
            // ★ 标记「这是平台压的」—— 补缴恢复时只回这一批，商家自己停的不动
            st.setPlanSuspended(true);
            DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(st));
            n++;
        }

        // 子账号全部停用；主账号（老板）照常登录 —— 关掉他自己等于关店
        for (var a : DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getStatus,
                                ai.neargo.shop.merchant.entity.MchAccount.ACTIVE)))) {
            if (Boolean.TRUE.equals(a.getIsOwner())) {
                continue;
            }
            a.setStatus("DISABLED");
            DataScopeContext.executeWithoutScope(() -> accountMapper.updateById(a));
        }
        return n;
    }

    /** 恢复：**只回 {@code plan_suspended=1} 的门店**，商家自己停用的不动。 */
    private void restoreSuspended(String merchantNo) {
        for (var st : DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getPlanSuspended, true)))) {
            st.setStatus(ai.neargo.shop.merchant.entity.MchStore.ACTIVE);
            st.setPlanSuspended(false);
            DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(st));
        }
    }

    private MchEntityPlan require(String merchantNo) {
        MchEntityPlan row = find(merchantNo);
        if (row == null) {
            row = materialize(merchantNo);
        }
        return row;
    }

    /**
     * 订阅行**按需物化**。
     *
     * <p>发现的经过：`mch_entity_plan` 线上一行都没有 —— 而 `planMapper.insert`
     * 在整个代码库里出现**零次**，也就是说这张表从来没有任何代码往里写过。
     * 于是每个主体都走 {@link #fallback}：`/biz/plan` 显示「孵化版 · 没用过试用」，
     * 看起来试用是可点的；而 {@link #trialTargetOf} 第一行 `row == null` 直接返回 null
     * （按钮灰掉），{@link #startTrial} 的 `require()` 则抛 NOT_FOUND。
     *
     * <p>后果是**自助开通试用对系统里的每一个商家都是死的**，而它存在的理由
     * 恰恰是 startTrial 注释里写的「他正要建第二家店的那一刻」—— 那一刻按钮点不动。
     * 这件事没有任何症状：额度闸正常工作，页面也正常显示，只是升不上去。
     *
     * <p>{@link #lockAndRead} 的注释里已经点过名：「真要收紧，应该是把回填补齐
     * 让每个主体都有行」。这就是那个回填 —— 做成**按需**而不是一次性刷库：
     * 刷库要挑时机、要考虑新入驻的主体，而按需物化对新老一视同仁。
     *
     * <p>物化出来的是**兜底那一档原样**（FREE + 定义表里的额度），所以它不改变
     * 任何主体当前的能力，只是把「隐含的默认」变成「一行真实的记录」。
     */
    private MchEntityPlan materialize(String merchantNo) {
        var def = findDef(MchEntityPlan.FREE);
        var row = new MchEntityPlan();
        row.setEntityNo(merchantNo);
        row.setPlanCode(MchEntityPlan.FREE);
        row.setStoreQuota(def == null ? fallbackStoreQuota : nz(def.getStoreQuota()));
        row.setStaffQuota(def == null ? 0 : nz(def.getStaffQuota()));
        row.setCrossStoreStats(def != null && Boolean.TRUE.equals(def.getCrossStoreStats()));
        row.setStatus(MchEntityPlan.ACTIVE);
        row.setTrialUsed(false);
        row.setGrantedBy(MchEntityPlan.BY_SELF_PAID);
        MchEntityPlan toSave = row;
        DataScopeContext.executeWithoutScope(() -> planMapper.insert(toSave));
        return toSave;
    }

    private ai.neargo.shop.merchant.entity.SysMerchantPlanDef findDef(String planCode) {
        return DataScopeContext.executeWithoutScope(() -> defMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.SysMerchantPlanDef>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.SysMerchantPlanDef::getPlanCode, planCode)
                        .last("limit 1")));
    }

    // ---------------------------------------------------------------- 商家端

    @Override
    public MinePlanVO mine(String merchantNo) {
        /*
         * 用 require 而不是 find：**读这一页正是最自然的物化时机**。
         * 用 find 的话，页面上显示「没用过试用」而按钮永远是灰的 ——
         * 两个字段来自同一次调用却互相矛盾（见 materialize 的说明）。
         */
        MchEntityPlan row = require(merchantNo);
        PlanVO v = toVO(row);
        var def = findDef(v.planCode());

        /*
         * 被降级压下的门店名单。**只取 plan_suspended=1** ——
         * 商家自己停用的店混进来的话，套餐页会告诉他「补缴就能恢复」，
         * 而那家店与欠费毫无关系，补完他会发现它还是关着。
         */
        List<String> suspended = DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                                .eq(ai.neargo.shop.merchant.entity.MchStore::getPlanSuspended, true)))
                .stream().map(ai.neargo.shop.merchant.entity.MchStore::getName).toList();

        var tierDefs = enabledDefs();
        var trialTier = trialTargetOf(v, row);
        return new MinePlanVO(v.planCode(), def == null ? v.planCode() : def.getName(),
                v.status(), row == null ? null : row.getStartAt(), v.expireAt(),
                v.storeQuota(), countActiveStores(merchantNo),
                v.staffQuota(), countActiveStaff(merchantNo),
                v.crossStoreStats(), v.trialUsed(),
                trialTier == null ? null : trialTier.getPlanCode(),
                trialTier == null ? null : nz(trialTier.getTrialDays()),
                suspended,
                tierDefs.stream()
                        .map(d -> new TierVO(d.getPlanCode(), d.getName(), nz(d.getStoreQuota()),
                                nz(d.getStaffQuota()), Boolean.TRUE.equals(d.getCrossStoreStats()),
                                nz(d.getTrialDays()), d.getPlanCode().equals(v.planCode())))
                        .toList());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public MinePlanVO startTrial(String merchantNo) {
        MchEntityPlan row = require(merchantNo);
        PlanVO v = toVO(row);
        var target = trialTargetOf(v, row);
        if (target == null) {
            /*
             * 三种情况合成一个拒绝：已用过试用 / 已经是付费档 / 没有配置可试用的档位。
             * 分成三个错误码没有意义 —— 商家侧的下一步在三种情况下是同一个（联系平台），
             * 而**能不能点**这件事界面上已经用 trialTier 表达了：
             * 走到这里的只有并发点两次和绕过界面的请求。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        long now = System.currentTimeMillis();
        row.setPlanCode(target.getPlanCode());
        row.setStoreQuota(nz(target.getStoreQuota()));
        row.setStaffQuota(nz(target.getStaffQuota()));
        row.setCrossStoreStats(Boolean.TRUE.equals(target.getCrossStoreStats()));
        row.setStatus(MchEntityPlan.ACTIVE);
        row.setStartAt(now);
        row.setExpireAt(now + nz(target.getTrialDays()) * DAY_MS);
        // ★ 一主体一次，永不回退 —— 允许重开等于把付费档变成免费档
        row.setTrialUsed(true);
        row.setGrantedBy(MchEntityPlan.BY_PLATFORM);
        row.setDowngradedAt(null);
        // 试用也是一次生效的订阅：上一轮降级压下的店该回来
        restoreSuspended(merchantNo);
        MchEntityPlan toSave = row;
        DataScopeContext.executeWithoutScope(() -> planMapper.updateById(toSave));
        return mine(merchantNo);
    }

    /**
     * 试用的目标档位：**可试用且在售的档位里 sort 最小的那个**。
     *
     * <p>不写死 PRO：哪天运营在 FREE 与 PRO 之间插一档，写死会让试用跳过它、
     * 直接送出更贵的能力 —— 而那种错误在代码里看不出来，只会体现在成本上。
     *
     * @return null = 现在不能试用（已用过 / 已经是付费档 / 没有可试用的档位）
     */
    private ai.neargo.shop.merchant.entity.SysMerchantPlanDef trialTargetOf(PlanVO v, MchEntityPlan row) {
        if (row == null || Boolean.TRUE.equals(row.getTrialUsed())) {
            return null;
        }
        // 已经是付费档的人不需要试用（含宽限期与已过期 —— 那两种要的是续费，不是试用）
        if (!MchEntityPlan.FREE.equals(v.planCode())) {
            return null;
        }
        return enabledDefs().stream()
                .filter(d -> nz(d.getTrialDays()) > 0)
                .findFirst()
                .orElse(null);
    }

    /** 在售档位，按 sort 升序。三处（档位对比 / 试用目标 / 授予下拉）共用同一个序。 */
    private List<ai.neargo.shop.merchant.entity.SysMerchantPlanDef> enabledDefs() {
        return DataScopeContext.executeWithoutScope(() ->
                defMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.SysMerchantPlanDef>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.SysMerchantPlanDef::getEnabled, true)
                        .orderByAsc(ai.neargo.shop.merchant.entity.SysMerchantPlanDef::getSort)));
    }

    private int countActiveStores(String merchantNo) {
        return (int) DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectCount(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getStatus,
                                ai.neargo.shop.merchant.entity.MchStore.ACTIVE))).longValue();
    }

    private int countActiveStaff(String merchantNo) {
        return (int) DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectCount(Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getIsOwner, false)
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getStatus,
                                ai.neargo.shop.merchant.entity.MchAccount.ACTIVE))).longValue();
    }

    private PlanRowVO toRow(MchEntityPlan r) {
        PlanVO v = toVO(r);
        // 与商家侧 mine() 共用同一个计数口径 —— 两处各写一份的表现是
        // 「运营看板说 2/3，商家自己的套餐页说 3/3」，而没人说得清哪个对
        int storeUsed = countActiveStores(r.getEntityNo());
        int staffUsed = countActiveStaff(r.getEntityNo());
        var m = DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, r.getEntityNo())
                        .last("limit 1")));
        return new PlanRowVO(r.getEntityNo(), m == null ? null : m.getName(), r.getPlanCode(),
                v.storeQuota(), v.staffQuota(), storeUsed, staffUsed,
                v.crossStoreStats(), r.getStatus(), r.getStartAt(), r.getExpireAt(),
                r.getGrantedBy(), Boolean.TRUE.equals(r.getTrialUsed()), r.getDowngradedAt(),
                v.source());
    }


    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
