package ai.neargo.shop.pay.checkout.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.checkout.service.QrCodeService;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayQrCodeMapper;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link QrCodeService} 的实现（TDD-支付服务-I5接入层 S8） */
@Service
public class QrCodeServiceImpl implements QrCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> KINDS = Set.of("STORE", "TABLE");

    private final PayQrCodeMapper codes;
    private final CallerPolicy policy;
    private final PayMerchantBindingService stores;
    private final long maxSingle;
    private final long maxDaily;

    public QrCodeServiceImpl(PayQrCodeMapper codes, CallerPolicy policy, PayMerchantBindingService stores,
                             @Value("${shop.pay.qr.max-single-minor:500000}") long maxSingle,
                             @Value("${shop.pay.qr.max-daily-minor:5000000}") long maxDaily) {
        this.codes = codes;
        this.policy = policy;
        this.stores = stores;
        this.maxSingle = maxSingle;
        this.maxDaily = maxDaily;
    }

    @Override
    public List<PayQrCode> ofStore(PayCaller caller, Long storeId) {
        policy.check(caller, PayAction.QUERY, storeId);
        return DataScopeContext.executeWithoutScope(() -> codes.selectList(Wrappers.<PayQrCode>lambdaQuery()
                .eq(PayQrCode::getStoreId, storeId).orderByDesc(PayQrCode::getId).last("LIMIT 200")));
    }

    @Override
    public PayQrCode create(PayCaller caller, Long storeId, CreateQrCmd cmd) {
        policy.check(caller, PayAction.CREATE_ONLINE, storeId);
        String kind = cmd.kind() == null ? "STORE" : cmd.kind();
        if (!KINDS.contains(kind) || ("TABLE".equals(kind) && (cmd.tableRef() == null || cmd.tableRef().isBlank()))
                || cmd.singleLimitMinor() <= 0 || cmd.dailyLimitMinor() < cmd.singleLimitMinor()
                || cmd.singleLimitMinor() > maxSingle || cmd.dailyLimitMinor() > maxDaily
                || (cmd.tableRef() != null && cmd.tableRef().length() > 64)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String market = stores.storeById(storeId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)).market();
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        PayQrCode c = new PayQrCode();
        c.setQrCode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        c.setStoreId(storeId);
        c.setKind(kind);
        c.setTableRef("TABLE".equals(kind) ? cmd.tableRef().trim() : null);
        c.setCurrency(MerchantDefaults.currencyOf(market));
        c.setSingleLimitMinor(cmd.singleLimitMinor());
        c.setDailyLimitMinor(cmd.dailyLimitMinor());
        c.setStatus("ACTIVE");
        c.setCreatedByType(caller.type().name());
        DataScopeContext.executeWithoutScope(() -> codes.insert(c));
        return c;
    }

    @Override
    public void disable(PayCaller caller, String qrCode) {
        PayQrCode c = qrCode == null ? null : DataScopeContext.executeWithoutScope(() -> codes.selectOne(
                Wrappers.<PayQrCode>lambdaQuery().eq(PayQrCode::getQrCode, qrCode)));
        if (c == null || (!caller.covers(c.getStoreId()) && caller.type() != CallerType.OPS)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        PayQrCode patch = new PayQrCode();
        patch.setId(c.getId());
        patch.setStatus("DISABLED");
        DataScopeContext.executeWithoutScope(() -> codes.updateById(patch));
    }
}
