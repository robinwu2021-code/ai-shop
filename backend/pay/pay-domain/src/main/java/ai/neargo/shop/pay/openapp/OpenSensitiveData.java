package ai.neargo.shop.pay.openapp;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 开放 API 敏感字段落库（开发计划 M3.5；TDD-支付服务-开放API加解密与密钥分发 §2.7）：守卫解出的明文立即经
 * {@link SecretVault} 封存进 {@code sec_secret_blob}（附加认证数据绑定归属行 —— 挪到别的单解不开），
 * 业务表只存 blob id 与掩码 JSON；查询只回掩码（{@link #masked}）。
 *
 * <p>一组字段一个封存：付款人（手机 / 邮箱 / 姓名）挂支付单，线下退款收款账号（银行代码 / 账号 / 户名）挂退款单。
 * 只收清单里的字段名，别的忽略。同一张单已封存过（幂等重放）不再写。密钥模块不可用 → {@code PAY_KEY_SERVICE_UNAVAILABLE}：
 * 不存明文，也不悄悄丢掉。
 */
@Component
public class OpenSensitiveData {

    static final List<String> PAYER = List.of("phone", "email", "name");
    static final List<String> REFUND_ACCOUNT = List.of("bankCode", "accountNo", "accountName");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<SecretVault> vault;
    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;

    public OpenSensitiveData(ObjectProvider<SecretVault> vault, PayOrderMapper orders, PayRefundMapper refunds) {
        this.vault = vault;
        this.orders = orders;
        this.refunds = refunds;
    }

    public void sealPayer(PayOrder o, Map<String, String> payer) {
        Map<String, String> clean = pick(payer, PAYER);
        if (clean.isEmpty() || o.getPayerBlobId() != null) {
            return;
        }
        Sealed s = seal("PAY_ORDER_PAYER", o.getId(), clean);
        PayOrder patch = new PayOrder();
        patch.setId(o.getId());
        patch.setPayerBlobId(s.blobId());
        patch.setPayerMasked(s.masked());
        DataScopeContext.executeWithoutScope(() -> orders.updateById(patch));
        o.setPayerBlobId(s.blobId());
        o.setPayerMasked(s.masked());
    }

    public void sealRefundAccount(PayRefund r, Map<String, String> account) {
        Map<String, String> clean = pick(account, REFUND_ACCOUNT);
        if (clean.isEmpty() || r.getRefundAccountBlobId() != null) {
            return;
        }
        Sealed s = seal("PAY_REFUND_ACCOUNT", r.getId(), clean);
        PayRefund patch = new PayRefund();
        patch.setId(r.getId());
        patch.setRefundAccountBlobId(s.blobId());
        patch.setRefundAccountMasked(s.masked());
        DataScopeContext.executeWithoutScope(() -> refunds.updateById(patch));
        r.setRefundAccountBlobId(s.blobId());
        r.setRefundAccountMasked(s.masked());
    }

    /** 掩码 JSON → 字段表（查询回给接入方）；没有为空 */
    public static Map<String, String> masked(String maskedJson) {
        if (maskedJson == null || maskedJson.isBlank()) {
            return null;
        }
        return JSON.readValue(maskedJson, new TypeReference<LinkedHashMap<String, String>>() { });
    }

    private record Sealed(long blobId, String masked) {
    }

    private Sealed seal(String ownerType, Long ownerId, Map<String, String> clean) {
        SecretVault v = vault.getIfAvailable();
        if (v == null) {
            throw BizException.of(ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
        }
        Map<String, String> masked = new LinkedHashMap<>();
        clean.forEach((k, val) -> masked.put(k, SensitiveMasks.mask(k, val)));
        try {
            SecretRef ref = v.seal(new KeyOwner(SecretNamespace.PAY, ownerType, ownerId),
                    JSON.writeValueAsString(clean).getBytes(StandardCharsets.UTF_8));
            return new Sealed(ref.blobId(), JSON.writeValueAsString(masked));
        } catch (RuntimeException e) {
            // 环境没配、主密钥不可用……：不存明文，回「密钥服务不可用」
            throw BizException.of(ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE);
        }
    }

    private static Map<String, String> pick(Map<String, String> in, List<String> allowed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            for (String k : allowed) {
                String v = in.get(k);
                if (v != null && !v.isBlank()) {
                    out.put(k, v);
                }
            }
        }
        return out;
    }
}
