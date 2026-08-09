package ai.neargo.shop.user.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.StoreVO;
import ai.neargo.shop.user.mapper.UserMappers.MchPaymentMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchStoreRoleMapper;
import ai.neargo.shop.user.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.user.merchant.entity.MchStore;
import ai.neargo.shop.user.merchant.entity.MchStoreRole;
import ai.neargo.shop.user.service.StoreAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 门店管理。写侧此前完全空白 —— 表、实体、门店号生成规则都齐了，
 * 但除了「激活时建一家默认店」没有任何代码能再建第二家。
 */
@Service
public class StoreAdminServiceImpl implements StoreAdminService {

    private final MchStoreMapper storeMapper;
    private final MchStoreRoleMapper roleMapper;
    private final MchPaymentMapper paymentMapper;

    /**
     * 每个主体的门店上限。
     *
     * <p>默认 1 —— **与单店时代行为完全一致**，放开多门店是一次显式的配置/订阅变更，
     * 而不是升个版就悄悄变了。M4 Plan 落地后这个值由订阅档位给
     * （FREE 1 / PRO 3 / CHAIN 10），届时这里改成读 plan。
     */
    private final int maxPerEntity;

    public StoreAdminServiceImpl(MchStoreMapper storeMapper, MchStoreRoleMapper roleMapper,
                                 MchPaymentMapper paymentMapper,
                                 @Value("${shop.store.max-per-entity:1}") int maxPerEntity) {
        this.storeMapper = storeMapper;
        this.roleMapper = roleMapper;
        this.paymentMapper = paymentMapper;
        this.maxPerEntity = maxPerEntity;
    }

    @Override
    public List<StoreVO> list(String merchantNo) {
        List<MchStore> stores = stores(merchantNo);
        Set<String> payReady = activePayMerchantNos(merchantNo);
        Map<String, Long> staffCount = DataScopeContext.executeWithoutScope(() ->
                        roleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                                .in(MchStoreRole::getStoreNo,
                                        stores.isEmpty() ? List.of("") : stores.stream().map(MchStore::getStoreNo).toList())))
                .stream().collect(Collectors.groupingBy(MchStoreRole::getStoreNo, Collectors.counting()));
        return stores.stream().map(s -> toVO(s, payReady, staffCount)).toList();
    }

    @Override
    @Transactional
    public StoreVO create(String merchantNo, String name, String address) {
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<MchStore> existing = stores(merchantNo);
        /*
         * 超额直接拒。**不要"建了再说、超了不给用"** ——
         * 那样商家会看到一家建出来却打不开的店，比拒绝更难解释。
         */
        if (existing.size() >= maxPerEntity) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchStore s = new MchStore();
        s.setEntityNo(merchantNo);
        s.setStoreNo(BizKey.next(BizKey.STORE));
        s.setName(name);
        s.setAddress(address);
        // 第一家店自动成为默认店；之后新建的都不是 —— 默认标的转移是显式动作
        s.setIsDefault(existing.isEmpty());
        s.setStatus(MchStore.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(s));
        return toVO(s, activePayMerchantNos(merchantNo), Map.of());
    }

    @Override
    @Transactional
    public StoreVO rename(String merchantNo, String storeNo, String name, String address) {
        MchStore s = require(merchantNo, storeNo);
        if (name != null && !name.isBlank()) {
            s.setName(name);
        }
        if (address != null) {
            s.setAddress(address);
        }
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(s));
        return toVO(s, activePayMerchantNos(merchantNo), staffCountOf(storeNo));
    }

    @Override
    @Transactional
    public StoreVO setStatus(String merchantNo, String storeNo, boolean active) {
        MchStore s = require(merchantNo, storeNo);
        /*
         * 默认店不能停用：主体必须恰好有一家默认店，停掉之后
         * 「这个主体的店在哪」没有答案 —— 下单兜底、门店码、分享都依赖它。
         */
        if (!active && Boolean.TRUE.equals(s.getIsDefault())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        s.setStatus(active ? MchStore.ACTIVE : MchStore.READONLY);
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(s));
        return toVO(s, activePayMerchantNos(merchantNo), staffCountOf(storeNo));
    }

    @Override
    @Transactional
    public StoreVO setDefault(String merchantNo, String storeNo) {
        MchStore target = require(merchantNo, storeNo);
        // 停用的店不能当默认店：默认店是「找不到具体门店时去哪」的答案，
        // 而这个答案不能是一家已经关门的店
        if (!MchStore.ACTIVE.equals(target.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        DataScopeContext.executeWithoutScope(() -> {
            for (MchStore s : stores(merchantNo)) {
                boolean want = s.getStoreNo().equals(storeNo);
                if (Boolean.TRUE.equals(s.getIsDefault()) != want) {
                    s.setIsDefault(want);
                    storeMapper.updateById(s);
                }
            }
            return null;
        });
        target.setIsDefault(true);
        return toVO(target, activePayMerchantNos(merchantNo), staffCountOf(storeNo));
    }

    @Override
    @Transactional
    public StoreVO setPayment(String merchantNo, String storeNo, String payMerchantNo) {
        MchStore s = require(merchantNo, storeNo);

        if (payMerchantNo != null && !payMerchantNo.isBlank()) {
            /*
             * 只在**本主体已 ACTIVE 的收款号**里挑。
             *   别的主体的号 → 钱会打到别人的执照名下
             *   还没开好的号 → 换过去之后下一单就收不了款
             * 两种都不是「配置错了」能形容的后果，所以拦在这里而不是提示一下。
             */
            if (!activePayMerchantNos(merchantNo).contains(payMerchantNo)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            s.setPayMerchantNo(payMerchantNo);
        } else {
            // 传空是合法操作：回到「用主体的默认收款号」，不是清空错误
            s.setPayMerchantNo(null);
        }
        // 留痕：它改变的是钱的去向，出问题时要能回答「什么时候换的」
        s.setPaymentChangedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(s));
        return toVO(s, activePayMerchantNos(merchantNo), staffCountOf(storeNo));
    }

    // ------------------------------------------------------------------ 内部

    private List<MchStore> stores(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .orderByDesc(MchStore::getIsDefault)
                        .orderByAsc(MchStore::getId)));
    }

    /** 越权保护：门店号对不上主体一律 404，**不要 403** —— 403 等于确认这个号存在。 */
    private MchStore require(String merchantNo, String storeNo) {
        return stores(merchantNo).stream()
                .filter(s -> s.getStoreNo().equals(storeNo))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private Set<String> activePayMerchantNos(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        paymentMapper.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                                .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                                .eq(MchPaymentMerchant::getApplyStatus, MchPaymentMerchant.ACTIVE)))
                .stream()
                .map(MchPaymentMerchant::getPayMerchantNo)
                .filter(x -> x != null && !x.isBlank())
                .collect(Collectors.toSet());
    }

    private Map<String, Long> staffCountOf(String storeNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        roleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                                .eq(MchStoreRole::getStoreNo, storeNo)))
                .stream().collect(Collectors.groupingBy(MchStoreRole::getStoreNo, Collectors.counting()));
    }

    private StoreVO toVO(MchStore s, Set<String> activePay, Map<String, Long> staffCount) {
        /*
         * payReady 的口径：
         *   挂了收款号 → 那个号得是 ACTIVE
         *   没挂       → 主体有任意一个 ACTIVE 的号就行（用默认那个）
         * 端上照这个布尔显示，别自己去比 —— 比错的表现是"显示能收钱但收不了"。
         */
        boolean payReady = s.getPayMerchantNo() == null || s.getPayMerchantNo().isBlank()
                ? !activePay.isEmpty()
                : activePay.contains(s.getPayMerchantNo());
        return new StoreVO(s.getStoreNo(), s.getName(), s.getAddress(),
                Boolean.TRUE.equals(s.getIsDefault()), s.getStatus(),
                s.getPayMerchantNo(), payReady,
                staffCount.getOrDefault(s.getStoreNo(), 0L).intValue());
    }
}
