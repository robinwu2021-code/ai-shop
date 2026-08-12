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
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper;
    /** 通过审核时要把内容写回门面表 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeProfileMapper;
    /** 覆盖项审核：裁决要落到这张表上（ADR-013 阶段三） */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;
    /** 待审覆盖项的展示名 —— 让运营对着「DISTRICT:330106」裁决等于让他去别处查一次 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityNamePort;
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;

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
            ai.neargo.shop.spi.platform.MasterDataPort masterDataPort) {
        this.serviceAreaMapper = serviceAreaMapper;
        this.communityNamePort = communityNamePort;
        this.masterDataPort = masterDataPort;
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

        List<MchEntity> rows = DataScopeContext.executeWithoutScope(() -> merchantMapper.selectList(w));
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
        return toVO(require(merchantNo));
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
                nameOf(v.getEntityNo()), v.getType(), v.getAction(), v.getDetail(),
                v.getOperatorNo(), v.getAt() == null ? 0L : v.getAt())).toList();
    }

    @Override
    @Transactional
    public ViolationVO recordViolation(String merchantNo, String type, String action, String detail,
                                       String operatorNo) {
        if (detail == null || detail.isBlank()) {
            // 没有事实的处置在申诉时站不住 —— 商家问「凭什么」，运营答不上来
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchEntity m = require(merchantNo);

        MchViolation v = new MchViolation();
        v.setViolationNo(BizKey.next(BizKey.VIOLATION));
        v.setEntityNo(merchantNo);
        v.setType(type);
        v.setAction(action);
        v.setDetail(detail.trim());
        v.setOperatorNo(operatorNo);
        v.setAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> violationMapper.insert(v));

        /*
         * 两个副作用是**处置的一部分**，不是可选项：
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
        return new ViolationVO(v.getViolationNo(), merchantNo, m.getName(), type, action,
                v.getDetail(), operatorNo, v.getAt());
    }

    // ───────────────────────────────────────────────────────────────────

    private MchEntity require(String merchantNo) {
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
                m.getLegalForm());
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
