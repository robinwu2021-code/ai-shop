package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
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
    private final MchEntityCommunityMapper communityMapper;
    private final MchPaymentMapper paymentMapper;
    private final ViolationMapper violationMapper;
    private final MerchantApplyQueryPort applyPort;
    private final ObjectMapper json;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper;
    /** 通过审核时要把内容写回门面表 */
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeProfileMapper;

    public MerchantGovernServiceImpl(MchEntityMapper merchantMapper,
                                     MchEntityCommunityMapper communityMapper,
                                     MchPaymentMapper paymentMapper,
                                     ViolationMapper violationMapper,
                                     MerchantApplyQueryPort applyPort,
                                     ai.neargo.shop.merchant.mapper.MerchantMappers.StoreAuditMapper storeAuditMapper,
                                     ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeProfileMapper,
                                     ObjectMapper json) {
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
                null);
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

    private StoreAuditVO toAuditVO(ai.neargo.shop.merchant.entity.MchStoreAudit a) {
        return new StoreAuditVO(a.getAuditNo(), a.getEntityNo(), nameOf(a.getEntityNo()),
                a.getKind(), a.getContent(), a.getStatus(), readList(a.getHits()),
                a.getSubmittedAt() == null ? 0L : a.getSubmittedAt(), a.getReason());
    }
}
