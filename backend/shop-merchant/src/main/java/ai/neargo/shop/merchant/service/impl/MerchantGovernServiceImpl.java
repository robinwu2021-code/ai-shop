package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchViolation;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ViolationMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.platform.MerchantApplyQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MerchantGovernServiceImpl implements MerchantGovernService {

    private static final String ACTIVE = "ACTIVE";
    private static final String SUSPENDED = "SUSPENDED";
    private static final String FROZEN = "FROZEN";

    /**
     * 毁约次数上限：达到就不能再拿认证标。
     * 与 ops-web 的 {@code MAX_MERCHANT_BREACH} 同值 —— 两处都判是有意的：
     * 端上判是为了把按钮灰掉，后端判是防线。
     */
    private static final int MAX_BREACH = 3;

    /** 经营状态的合法迁移。封禁是处罚、冻结是风控，两者都只能先回到 ACTIVE。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            ACTIVE, Set.of(SUSPENDED, FROZEN),
            SUSPENDED, Set.of(ACTIVE),
            FROZEN, Set.of(ACTIVE));

    private final MchEntityMapper merchantMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper qualificationMapper;
    private final MchEntityCommunityMapper communityMapper;
    private final MchPaymentMapper paymentMapper;
    private final ViolationMapper violationMapper;
    private final MerchantApplyQueryPort applyPort;
    private final ObjectMapper json;
    /** 门槛码字典：商家侧「我传这张证能换来什么」靠它 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper authCodeMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper;
    /** 通过审核时要把内容写回门面表 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeProfileMapper;
    /** 覆盖项审核：裁决要落到这张表上（ADR-013 阶段三） */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;
    /** 待审覆盖项的展示名 —— 让运营对着「DISTRICT:330106」裁决等于让他去别处查一次 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityNamePort;
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;
    /** 自营结算敞口 —— 金额口径归结算域，商家域只负责「谁是无照的」 */
    private final ai.neargo.shop.spi.settle.SelfOperatedExposurePort exposurePort;
    /**
     * 门店强制下线要真的撤下货架（product 域的事，走 Port）—— 只改 status 是「处置完了还在卖」。
     *
     * <p><b>用 ObjectProvider 延迟解析而不是直接注入</b>：直接注入会形成构造期的环
     * （{@code MerchantPortImpl → 本类 → StoreShelfPort → MerchantGoodsService
     * → GoodsService → MerchantPortImpl}），整个上下文起不来。
     *
     * <p>不把撤货架挪到 Controller 去编排来绕开这个环：那样它就从
     * <b>处置的一部分</b>变成了「调用方记得调就调」的可选步骤，
     * 而漏掉一次的症状是「处置完了还在卖」—— 与压根没做一模一样，且没有任何报错。
     */
    private final org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.product.StoreShelfPort> shelfPort;

    public MerchantGovernServiceImpl(MchEntityMapper merchantMapper,
                                     MchEntityCommunityMapper communityMapper,
                                     MchPaymentMapper paymentMapper,
                                     ViolationMapper violationMapper,
                                     MerchantApplyQueryPort applyPort,
                                     ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper,
                                     ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeProfileMapper,
                                     ObjectMapper json,
            ai.neargo.shop.merchant.mapper.MerchantMappers.QualificationMapper qualificationMapper,
            ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper,
            ai.neargo.shop.spi.user.CommunityQueryPort communityNamePort,
            ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
            ai.neargo.shop.spi.settle.SelfOperatedExposurePort exposurePort,
            org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.product.StoreShelfPort> shelfPort,
            ai.neargo.shop.merchant.mapper.MerchantMappers.SysAuthCodeMapper authCodeMapper) {
        this.authCodeMapper = authCodeMapper;
        this.serviceAreaMapper = serviceAreaMapper;
        this.communityNamePort = communityNamePort;
        this.masterDataPort = masterDataPort;
        this.exposurePort = exposurePort;
        this.shelfPort = shelfPort;
        this.qualificationMapper = qualificationMapper;
        this.merchantMapper = merchantMapper;
        this.communityMapper = communityMapper;
        this.paymentMapper = paymentMapper;
        this.violationMapper = violationMapper;
        this.applyPort = applyPort;
        this.json = json;
        this.storeAuditMapper = storeAuditMapper;
        this.storeProfileMapper = storeProfileMapper;
    }

    @Override
    public ai.neargo.shop.common.PageData<MerchantProfileVO> list(String status, String communityNo,
                                                                  String keyword, long page, long size) {
        var w = Wrappers.<MchEntity>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(MchEntity::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(MchEntity::getName, keyword).or().like(MchEntity::getEntityNo, keyword));
        }
        w.orderByDesc(MchEntity::getId);

        /*
         * ★ **接数据域**（批②）：配了 merchant 域的运营只看得到那一家。
         * 没配的会话是 ALL（空 = 不限定），超管恒 ALL —— 存量账号零变化。
         */
        List<MchEntity> rows = merchantMapper.selectList(w);
        List<MerchantProfileVO> all = rows.stream()
                .map(this::toVO)
                // 社区筛在内存里做：一家店服务多个社区，SQL 侧要 join 一张多对多表，
                // 而商家总数是几百这个量级 —— 为此写一条 join 不划算
                .filter(v -> communityNo == null || communityNo.isBlank()
                        || v.communityNos().contains(communityNo))
                .toList();
        // 社区筛在内存里做，分页也只能跟着在内存里切 —— 否则页码会跳
        long from = Math.max(0, (page - 1) * size);
        List<MerchantProfileVO> pageRows = from >= all.size() ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + size));
        return ai.neargo.shop.common.PageData.of(pageRows, all.size(), page, size);
    }

    @Override
    public MerchantProfileVO detail(String merchantNo) {
        return toVO(requireInScope(merchantNo));
    }

    /**
     * 读路径专用：**接数据域**（批②）。域外的商家号查不到 → NOT_FOUND，
     * <b>不是 403</b> —— 403 等于确认「这个商家确实存在」，那本身是一条信息，
     * 而商家号可枚举。
     *
     * <p>与 {@link #require} 分成两个方法而不是加一个 boolean 参数：
     * 参数化的话，下一个人在写路径上传错一次就把处置变成了静默失败。
     */
    private MchEntity requireInScope(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return m;
    }

    @Override
    @Transactional
    public MerchantProfileVO setStatus(String merchantNo, String status, String remark, String operatorNo) {
        MchEntity m = require(merchantNo);
        if (!TRANSITIONS.getOrDefault(m.getStatus(), Set.of()).contains(status)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // 封禁与冻结都会让商家立刻停业，没有说明的话他只看到「店没了」
        if (!ACTIVE.equals(status) && (remark == null || remark.isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        m.setStatus(status);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return toVO(m);
    }

    @Override
    @Transactional
    public MerchantProfileVO setVerified(String merchantNo, boolean verified, String operatorNo) {
        MchEntity m = require(merchantNo);
        if (verified) {
            // 认证标是平台的背书 —— 挂在停业或正在毁约的商家身上，赔的是平台的信用
            if (!ACTIVE.equals(m.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (nz(m.getBreachCount()) >= MAX_BREACH) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        m.setVerified(verified);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return toVO(m);
    }

    @Override
    public List<ViolationVO> violations(String merchantNo) {
        var w = Wrappers.<MchViolation>lambdaQuery();
        if (merchantNo != null && !merchantNo.isBlank()) {
            w.eq(MchViolation::getEntityNo, merchantNo);
        }
        w.orderByDesc(MchViolation::getId);
        List<MchViolation> rows = DataScopeContext.executeWithoutScope(() -> violationMapper.selectList(w));
        return rows.stream().map(v -> new ViolationVO(v.getViolationNo(), v.getEntityNo(),
                nameOf(v.getEntityNo()), v.getStoreNo(), v.getType(), v.getAction(), v.getDetail(),
                v.getOperatorNo(), v.getAt() == null ? 0L : v.getAt())).toList();
    }

    @Override
    @Transactional
    public ViolationVO recordViolation(String merchantNo, String storeNo, String type, String action,
                                       String detail, String operatorNo) {
        if (detail == null || detail.isBlank()) {
            // 没有事实的处置在申诉时站不住 —— 商家问「凭什么」，运营答不上来
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean storeLevel = MchViolation.STORE_OFFLINE.equals(action);
        // 门店号与动作必须成对：门店级处置没有门店号，或主体级处置带着门店号，
        // 申诉时都说不清处置对象到底是谁
        if (storeLevel == (storeNo == null || storeNo.isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchEntity m = require(merchantNo);
        MchStore store = storeLevel ? requireStoreOf(merchantNo, storeNo) : null;
        if (storeLevel && !MchStore.ACTIVE.equals(store.getStatus())) {
            /*
             * 只处置 ACTIVE 的店（TDD T4）：对 READONLY（商家自己已停）再压一层，
             * 解除时「恢复到什么状态」就没有答案了 —— 从源头消掉这个分支。
             */
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        MchViolation v = new MchViolation();
        v.setViolationNo(BizKey.next(BizKey.VIOLATION));
        v.setEntityNo(merchantNo);
        v.setStoreNo(storeLevel ? storeNo : null);
        v.setType(type);
        v.setAction(action);
        v.setDetail(detail.trim());
        v.setOperatorNo(operatorNo);
        v.setAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> violationMapper.insert(v));

        /*
         * 副作用是**处置的一部分**，不是可选项：
         * 只记录不执行的处置等于没处置，而商家那边什么都不会发生。
         */
        boolean changed = false;
        if (MchViolation.BREACH.equals(type)) {
            // 毁约次数在报价卡上公示（ADR-003）—— 它是给买家看的信用信号
            m.setBreachCount(nz(m.getBreachCount()) + 1);
            changed = true;
        }
        if (MchViolation.SUSPEND.equals(action) && ACTIVE.equals(m.getStatus())) {
            m.setStatus(SUSPENDED);
            changed = true;
        }
        if (changed) {
            DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        }
        if (storeLevel) {
            store.setStatus(MchStore.SUSPENDED);
            DataScopeContext.executeWithoutScope(() -> storeProfileMapper.updateById(store));
            /*
             * 撤货架必须跟上（TDD D3）：门店 status 在 C 端可见性链路上没有读者，
             * 只改状态 = 「处置完了还在卖」。真闸门是店级在售 × 主体总闸 × 社区池。
             */
            shelfPort.getObject().platformOffline(merchantNo, storeNo);
        }
        return new ViolationVO(v.getViolationNo(), merchantNo, m.getName(), v.getStoreNo(),
                type, action, v.getDetail(), operatorNo, v.getAt());
    }

    // ---------------------------------------------------------------- 门店档案（P-11.2.1）

    @Override
    public ai.neargo.shop.common.PageData<StoreGovernVO> searchStores(String merchantNo, String status,
                                                                      String businessMode, String keyword,
                                                                      long page, long size) {
        var w = Wrappers.<MchStore>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), MchStore::getEntityNo, merchantNo)
                .eq(status != null && !status.isBlank(), MchStore::getStatus, status)
                .eq(businessMode != null && !businessMode.isBlank(), MchStore::getBusinessMode, businessMode);
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(MchStore::getName, keyword).or().eq(MchStore::getStoreNo, keyword));
        }
        w.orderByDesc(MchStore::getId);
        // ★ 接数据域（批②）：商家域运营只看得到那一家的门店
        var p = storeProfileMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, size), w);

        // 商家名批量拼 —— 逐行 nameOf 是 N+1
        Set<String> entityNos = p.getRecords().stream().map(MchStore::getEntityNo)
                .collect(java.util.stream.Collectors.toSet());
        // 装饰性取名：entityNos 来自上面**已接数据域**的门店查询，再裁一次只会让名字变空
        Map<String, String> names = entityNos.isEmpty() ? Map.of()
                : merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                                .in(MchEntity::getEntityNo, entityNos))
                        .stream().collect(java.util.stream.Collectors.toMap(
                                MchEntity::getEntityNo, MchEntity::getName, (a, b) -> a));

        List<StoreGovernVO> rows = p.getRecords().stream()
                .map(s -> toStoreGovernVO(s, names.getOrDefault(s.getEntityNo(), s.getEntityNo())))
                .toList();
        return ai.neargo.shop.common.PageData.of(rows, p.getTotal(), page, size);
    }

    @Override
    public StoreGovernVO storeDetail(String storeNo) {
        MchStore s = requireStoreInScope(storeNo);
        return toStoreGovernVO(s, nameOf(s.getEntityNo()));
    }

    /**
     * 读路径专用：**接数据域**（批②）。域外的门店号查不到 → NOT_FOUND，不是 403 ——
     * 门店号可枚举，403 等于确认它存在。写路径见 {@link #requireStore}。
     */
    private MchStore requireStoreInScope(String storeNo) {
        MchStore s = storeProfileMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getStoreNo, storeNo).last("limit 1"));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    @Override
    @Transactional
    public StoreGovernVO restoreStore(String storeNo, String operatorNo) {
        MchStore s = requireStore(storeNo);
        if (!MchStore.SUSPENDED.equals(s.getStatus())) {
            // 只有平台压下去的才由平台解除；对 ACTIVE/READONLY 的店「解除」没有意义
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        s.setStatus(MchStore.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> storeProfileMapper.updateById(s));
        // 只回带 platform_suspended 标记的行 —— 商家在处置期间自己下架的不动
        shelfPort.getObject().platformRestore(s.getEntityNo(), storeNo);
        return toStoreGovernVO(s, nameOf(s.getEntityNo()));
    }

    private StoreGovernVO toStoreGovernVO(MchStore s, String merchantName) {
        return new StoreGovernVO(s.getStoreNo(), s.getName(), s.getAddress(),
                s.getEntityNo(), merchantName,
                Boolean.TRUE.equals(s.getIsDefault()), s.getStatus(), s.getBusinessMode(),
                s.getPayMerchantNo(), s.getAnnouncement(), s.getOpenHours(),
                s.getDeliveryRadiusM(), s.getDeliveryMinOrderMinor(),
                s.getDeliveryFeeMinor(), s.getDeliveryFreeThresholdMinor());
    }

    private MchStore requireStore(String storeNo) {
        /*
         * **不接数据域**（T2）：解除强制下线、门店级处置都走这里。
         * 接了之后「处置一家域外的店」会变成静默 NOT_FOUND —— 比明确拒绝更坏。
         * 读路径走 {@link #requireStoreInScope}。
         */
        MchStore s = DataScopeContext.executeWithoutScope(() ->
                storeProfileMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getStoreNo, storeNo).last("limit 1")));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    private MchStore requireStoreOf(String merchantNo, String storeNo) {
        MchStore s = requireStore(storeNo);
        if (!merchantNo.equals(s.getEntityNo())) {
            // 门店不归这个主体：按 404 处理，别的主体有没有这家店不该被探知
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    // ───────────────────────────────────────────────────────────────────

    private MchEntity require(String merchantNo) {
        /*
         * **不接数据域**（T2）：这个方法同时被封禁/授标/记违规等写路径调用，
         * 接了之后「处置一家不在自己域内的商家」会变成静默的 NOT_FOUND ——
         * 而静默失败比明确拒绝更坏。读路径走 {@link #requireInScope}。
         */
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return m;
    }

    private String nameOf(String entityNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("limit 1")));
        return m == null ? entityNo : m.getName();
    }

    private MerchantProfileVO toVO(MchEntity m) {
        List<String> communities = DataScopeContext.executeWithoutScope(() ->
                        communityMapper.selectList(Wrappers.<MchEntityCommunity>lambdaQuery()
                                .eq(MchEntityCommunity::getEntityNo, m.getEntityNo())))
                .stream().map(MchEntityCommunity::getCommunityNo).toList();

        // 「能不能收钱」看进件是否开通 —— 与 B 端工作台那张卡同一个判断
        boolean settleReady = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectCount(Wrappers.<ai.neargo.shop.merchant.entity.MchPaymentMerchant>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchPaymentMerchant::getEntityNo, m.getEntityNo())
                        .eq(ai.neargo.shop.merchant.entity.MchPaymentMerchant::getApplyStatus, ACTIVE))) > 0;

        // 只取仍然有效的：过期/吊销的资质拿来比对授权，等于让已经失效的证继续开门
        List<String> quals = DataScopeContext.executeWithoutScope(() ->
                        qualificationMapper.selectList(Wrappers.<MchQualification>lambdaQuery()
                                .eq(MchQualification::getEntityNo, m.getEntityNo())
                                .eq(MchQualification::getStatus, MchQualification.VALID)))
                .stream().map(MchQualification::getQualName).filter(Objects::nonNull).toList();

        var contact = applyPort.latestOf(m.getEntityNo());
        return new MerchantProfileVO(m.getEntityNo(), m.getName(), m.getTier(), m.getStatus(),
                communities,
                contact.map(MerchantApplyQueryPort.ApplyContact::contactName).orElse(null),
                contact.map(MerchantApplyQueryPort.ApplyContact::contactPhone).orElse(null),
                readList(m.getCategoryCodes()),
                Boolean.TRUE.equals(m.getVerified()), nz(m.getBreachCount()), settleReady,
                m.getJoinedAt() == null ? 0L : m.getJoinedAt(),
                contact.map(MerchantApplyQueryPort.ApplyContact::rejectReason).orElse(null),
                contact.map(MerchantApplyQueryPort.ApplyContact::asPickupPoint).orElse(false),
                // 归档用状态表达：FROZEN 即「不在营业中」，没有独立的归档位
                null,
                // 准入档位完全由它决定 —— 看得到结果看不到原因，只会引出一通电话
                m.getLegalForm(),
                quals,
                m.getFundsMode() == null || m.getFundsMode().isBlank()
                        ? ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_AGGREGATED
                        : m.getFundsMode(),
                Integer.valueOf(1).equals(m.getIsAgriProducer()));
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ---------------------------------------------------------------- 门店经营模式（F-6 后续）

    @Override
    @org.springframework.transaction.annotation.Transactional
    public StoreModeVO setBusinessMode(String storeNo, String mode, String operatorNo) {
        if (!MchStore.SELF_OPERATED.equals(mode) && !MchStore.THIRD_PARTY.equals(mode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchStore store = storeProfileMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getStoreNo, storeNo).last("LIMIT 1"));
        if (store == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        String payMerchantNo = activePayMerchantNo(store);
        /*
         * 第三方模式的硬前提：钱直接进商家账户，那个账户得存在。
         *
         * 不校验的后果不是报错，而是**静默欠款**：订单照常成交、账单照常生成，
         * 只是 payMerchantNo 为空，钱卡在平台侧下不去。等发现时已经积了一批单。
         * 自营不需要这一条 —— 自营的钱本来就先进平台。
         */
        if (MchStore.THIRD_PARTY.equals(mode) && payMerchantNo == null) {
            throw BizException.of(ErrorCode.PAY_MERCHANT_REQUIRED);
        }

        store.setBusinessMode(mode);
        store.setUpdatedBy(operatorNo);
        storeProfileMapper.updateById(store);

        // 不回改历史账单，也不需要：stl_bill.business_mode 在生成时已落快照。
        // 审计留痕在 controller 记（与本域其它运营动作一致）——「这家店什么时候
        // 从自营切成第三方的」是日后对账争议里第一个会被问到的问题。
        return new StoreModeVO(store.getStoreNo(), store.getName(), store.getEntityNo(),
                mode, payMerchantNo);
    }

    @Override
    public List<StoreModeVO> storeModes(String merchantNo) {
        return storeProfileMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo))
                .stream()
                .map(st -> new StoreModeVO(st.getStoreNo(), st.getName(), st.getEntityNo(),
                        st.getBusinessMode(), activePayMerchantNo(st)))
                .toList();
    }

    @Override
    @Transactional
    public MerchantProfileVO setFundsMode(String merchantNo, String mode, String operatorNo) {
        if (!ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_AGGREGATED.equals(mode)
                && !ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_DIRECT.equals(mode)) {
            // 枚举只有两个值，第三个只可能是笔误 —— 静默存下会让这家店走进一条不存在的路径
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchEntity m = require(merchantNo);

        /*
         * 无照主体不得走归集：平台是销售主体、按全额确认收入，
         * 而他开不出进项票 —— 那笔支出**不得在企业所得税前扣除**，走一单亏一单。
         *
         * 农业生产者例外：平台可自开农产品收购发票，成本有合法凭证。
         */
        boolean unlicensed = !masterDataPort.needLicense(m.getLegalForm());
        boolean agri = Integer.valueOf(1).equals(m.getIsAgriProducer());
        if (ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_AGGREGATED.equals(mode)
                && unlicensed && !agri) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        m.setFundsMode(mode);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.updateById(m));
        return toVO(m);
    }

    @Override
    public List<ModeRiskVO> modeRiskStores() {
        // 全量主体只有几百量级，一次捞出来在内存里筛 ——
        // 「哪一档免执照」由注册表决定，SQL 里写不出这个条件
        // ★ 接数据域（批②）：这是主查询 —— 商家域运营的风险清单只该有那一家
        List<MchEntity> all = merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery());
        Map<String, MchEntity> unlicensed = new java.util.HashMap<>();
        for (MchEntity m : all) {
            if (!masterDataPort.needLicense(m.getLegalForm())) {
                unlicensed.put(m.getEntityNo(), m);
            }
        }
        if (unlicensed.isEmpty()) {
            return List.of();
        }

        // 同上接数据域。两处都接才一致 —— 只接一处会得到「主体裁了、门店没裁」
        List<MchStore> selfOperated = storeProfileMapper.selectList(
                Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getBusinessMode, MchStore.SELF_OPERATED)
                        .in(MchStore::getEntityNo, unlicensed.keySet()));
        if (selfOperated.isEmpty()) {
            return List.of();
        }

        var exposure = exposurePort.selfOperatedExposure(
                selfOperated.stream().map(MchStore::getEntityNo).distinct().toList());

        return selfOperated.stream()
                .map(st -> {
                    MchEntity m = unlicensed.get(st.getEntityNo());
                    // 缺省成 0 而不是跳过：**「有这家店但还没成交」也是要显示的一行** ——
                    // 它是即将发生的敞口，正好是最该在成交前处理掉的那些
                    var e = exposure.getOrDefault(st.getEntityNo(),
                            new ai.neargo.shop.spi.settle.SelfOperatedExposurePort.Exposure(0, 0));
                    return new ModeRiskVO(m.getEntityNo(), m.getName(), m.getLegalForm(),
                            st.getStoreNo(), st.getName(), st.getBusinessMode(),
                            e.billCount(), e.amountMinor());
                })
                // 敞口大的排前面 —— 这份清单的用途就是决定先处理谁
                .sorted(java.util.Comparator.comparingLong(ModeRiskVO::settledMinor).reversed())
                .toList();
    }

    /**
     * 该店实际可用的收款号：优先本店专属号，回落到主体默认号。
     *
     * <p>回落这一步不能省 —— 不配店号就是「合并结算，走主体号」，
     * 那是正常形态而不是缺失（见 {@code MerchantPaymentService.openForStore}）。
     * 只查店号会把所有合并结算的门店误判成「没有收款账户」。
     */
    private String activePayMerchantNo(MchStore store) {
        var own = paymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getStoreNo, store.getStoreNo())
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)
                .last("LIMIT 1"));
        if (own != null) {
            return own.getPayMerchantNo();
        }
        var fallback = paymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, store.getEntityNo())
                .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)
                .last("LIMIT 1"));
        return fallback == null ? null : fallback.getPayMerchantNo();
    }

    // ---------------------------------------------------------------- 门面内容审核（P-10.1）

    @Override
    public List<StoreAuditVO> storeAudits(String status) {
        var w = Wrappers.<ai.neargo.shop.merchant.entity.MchStoreAudit>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(ai.neargo.shop.merchant.entity.MchStoreAudit::getStatus, status);
        }
        w.orderByDesc(ai.neargo.shop.merchant.entity.MchStoreAudit::getId);
        return DataScopeContext.executeWithoutScope(() -> storeAuditMapper.selectList(w))
                .stream().map(this::toAuditVO).toList();
    }

    @Override
    @Transactional
    public StoreAuditVO decideStoreAudit(String auditNo, boolean pass, String reason, String operatorNo) {
        if (!pass && (reason == null || reason.isBlank())) {
            // 驳回原因原样出现在商家 B 端 —— 不写的话商家不知道该改什么，只会原样再提一次
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var a = DataScopeContext.executeWithoutScope(() ->
                storeAuditMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStoreAudit>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStoreAudit::getAuditNo, auditNo)
                        .last("limit 1")));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 裁完就是终态：再裁一次意味着同一条公告有两个结论
        if (!ai.neargo.shop.merchant.entity.MchStoreAudit.PENDING.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        a.setStatus(pass ? ai.neargo.shop.merchant.entity.MchStoreAudit.PASSED
                : ai.neargo.shop.merchant.entity.MchStoreAudit.REJECTED);
        a.setReason(pass ? null : reason.trim());
        a.setDecidedAt(System.currentTimeMillis());
        a.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> storeAuditMapper.updateById(a));

        if (ai.neargo.shop.merchant.entity.MchStoreAudit.SERVICE_AREA.equals(a.getKind())) {
            decideServiceArea(a.getRefNo(), pass);
        }
        if (pass && ai.neargo.shop.merchant.entity.MchStoreAudit.NOTICE.equals(a.getKind())) {
            /*
             * 通过之后内容**这时才真正生效**。
             * 提交时不写门面表是有意的：命中期间店铺页保留旧公告 ——
             * 清空的话页面会突然变白，店主以为自己改坏了，只会反复再改一遍。
             */
            var store = storeProfileMapper.selectOne(
                    Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                            .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, a.getEntityNo())
                            .last("limit 1"));
            if (store != null) {
                store.setAnnouncement(a.getContent());
                var toSave = store;
                DataScopeContext.executeWithoutScope(() -> storeProfileMapper.updateById(toSave));
            }
        }
        return toAuditVO(a);
    }

    /**
     * 裁决落到覆盖项本身。
     *
     * <p><b>驳回是物理删除，不留 REJECTED 墓碑。</b> 这张表的唯一键在
     * (entity, level, ref) 上且**不含 deleted** —— 留墓碑的话，商家补齐材料
     * 重新勾同一个区就直接撞键，而他看到的是「系统开小差了」。
     * 本仓库已经为这个组合打过五个 revive 补丁，这里不打第六个。
     * 驳回理由留在审核单上，商家照样查得到。
     */
    private void decideServiceArea(String areaNo, boolean pass) {
        if (areaNo == null || areaNo.isBlank()) {
            return;
        }
        var row = DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getAreaNo, areaNo)
                        .last("limit 1")));
        if (row == null) {
            // 商家自己已经把这条删了 —— 单据照常裁完，没有可落的行
            return;
        }
        if (pass) {
            row.setStatus(ai.neargo.shop.merchant.entity.MchServiceArea.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.updateById(row));
        } else {
            DataScopeContext.executeWithoutScope(() -> serviceAreaMapper.hardDelete(
                    row.getEntityNo(), row.getLevel(), row.getRefCode()));
        }
    }

    private StoreAuditVO toAuditVO(ai.neargo.shop.merchant.entity.MchStoreAudit a) {
        return new StoreAuditVO(a.getAuditNo(), a.getEntityNo(), nameOf(a.getEntityNo()),
                a.getKind(), a.getContent(), a.getStatus(), readList(a.getHits()),
                a.getSubmittedAt() == null ? 0L : a.getSubmittedAt(), a.getReason(),
                displayOf(a));
    }

    /**
     * 覆盖项的 content 是「DISTRICT:330106」这样的机器串，运营看不出那是哪儿。
     *
     * <p>「一家菜摊能不能覆盖整个西湖区」这个判断，靠的就是这个名字 ——
     * 让运营对着一串数字裁决，等于让他去别处查一次再回来。
     */
    private String displayOf(ai.neargo.shop.merchant.entity.MchStoreAudit a) {
        if (!ai.neargo.shop.merchant.entity.MchStoreAudit.SERVICE_AREA.equals(a.getKind())
                || a.getContent() == null || !a.getContent().contains(":")) {
            return a.getContent();
        }
        String[] parts = a.getContent().split(":", 2);
        return "COMMUNITY".equals(parts[0])
                ? communityNamePort.communityName(parts[1])
                : masterDataPort.regionPathName(parts[1]);
    }
    // ---------------------------------------------------------------- 资质（P1-7）

    @Override
    public List<AuthCodeVO> authCodeCatalog() {
        return DataScopeContext.executeWithoutScope(() -> authCodeMapper.selectList(
                        Wrappers.<ai.neargo.shop.merchant.entity.SysAuthCode>lambdaQuery()
                                .eq(ai.neargo.shop.merchant.entity.SysAuthCode::getEnabled, true)
                                .orderByAsc(ai.neargo.shop.merchant.entity.SysAuthCode::getSort)))
                .stream()
                .map(c -> new AuthCodeVO(c.getCode(), c.getName(),
                        c.getRequiredQualification(), c.getQualType()))
                .toList();
    }

    @Override
    public List<QualificationVO> qualifications(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() -> qualificationMapper.selectList(
                        Wrappers.<MchQualification>lambdaQuery()
                                .eq(MchQualification::getEntityNo, merchantNo)
                                .orderByAsc(MchQualification::getExpireAt)))
                .stream().map(this::toQualVO).toList();
    }

    @Override
    @Transactional
    public QualificationVO saveQualification(String merchantNo, SaveQualificationCommand cmd,
                                             String operatorNo) {
        if (cmd.qualType() == null || cmd.qualType().isBlank()
                || cmd.qualName() == null || cmd.qualName().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchQualification q;
        if (cmd.qualNo() == null || cmd.qualNo().isBlank()) {
            q = new MchQualification();
            q.setQualNo(BizKey.next(BizKey.MERCHANT) + "Q");
            q.setEntityNo(merchantNo);
        } else {
            q = DataScopeContext.executeWithoutScope(() -> qualificationMapper.selectOne(Wrappers.<MchQualification>lambdaQuery()
                    .eq(MchQualification::getQualNo, cmd.qualNo()).last("limit 1")));
            if (q == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        q.setQualType(cmd.qualType());
        q.setQualName(cmd.qualName());
        q.setQualNumber(cmd.qualNumber());
        q.setImageUrl(cmd.imageUrl());
        q.setExpireAt(cmd.expireAt());
        /*
         * 重新登记（比如商家续了证传了新的）时状态回到 VALID —— 否则续证之后
         * 记录还挂着 EXPIRED，上架依然被拦，商家会以为「传了也没用」。
         */
        q.setStatus(cmd.expireAt() != null && cmd.expireAt() < System.currentTimeMillis()
                ? MchQualification.EXPIRED : MchQualification.VALID);
        if (q.getId() == null) {
            DataScopeContext.executeWithoutScope(() -> qualificationMapper.insert(q));
        } else {
            DataScopeContext.executeWithoutScope(() -> qualificationMapper.updateById(q));
        }
        return toQualVO(q);
    }

    @Override
    @Transactional
    public QualificationVO revokeQualification(String qualNo, String operatorNo) {
        MchQualification q = DataScopeContext.executeWithoutScope(() -> qualificationMapper.selectOne(
                Wrappers.<MchQualification>lambdaQuery()
                        .eq(MchQualification::getQualNo, qualNo).last("limit 1")));
        if (q == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 不物理删：撤销本身是要留痕的事实，删掉之后没人说得清「当初有没有这张证」
        q.setStatus(MchQualification.REVOKED);
        DataScopeContext.executeWithoutScope(() -> qualificationMapper.updateById(q));
        return toQualVO(q);
    }

    @Override
    @Transactional
    public java.util.Set<String> expireOverdueQualifications() {
        long now = System.currentTimeMillis();
        java.util.Set<String> affected = new java.util.LinkedHashSet<>();
        for (MchQualification q : DataScopeContext.executeWithoutScope(() -> qualificationMapper.selectList(
                Wrappers.<MchQualification>lambdaQuery()
                        .eq(MchQualification::getStatus, MchQualification.VALID)
                        // expire_at 为空 = 长期有效，**不能扫进来** ——
                        // 把「没填」当成「已过期」会把持长期执照的商家全部误伤
                        .isNotNull(MchQualification::getExpireAt)
                        .lt(MchQualification::getExpireAt, now)))) {
            q.setStatus(MchQualification.EXPIRED);
            DataScopeContext.executeWithoutScope(() -> qualificationMapper.updateById(q));
            affected.add(q.getEntityNo());
        }
        return affected;
    }

    @Override
    public boolean hasExpiredQualification(String merchantNo) {
        Long n = DataScopeContext.executeWithoutScope(() -> qualificationMapper.selectCount(
                Wrappers.<MchQualification>lambdaQuery()
                        .eq(MchQualification::getEntityNo, merchantNo)
                        .eq(MchQualification::getStatus, MchQualification.EXPIRED)));
        return n != null && n > 0;
    }

    private QualificationVO toQualVO(MchQualification q) {
        return new QualificationVO(q.getQualNo(), q.getEntityNo(), q.getQualType(),
                q.getQualName(), q.getQualNumber(), q.getImageUrl(), q.getExpireAt(), q.getStatus());
    }


}
