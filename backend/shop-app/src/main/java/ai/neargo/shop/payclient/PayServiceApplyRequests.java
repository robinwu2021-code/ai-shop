package ai.neargo.shop.payclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 组装「申请支付服务」请求（TDD-运营端申请支付服务 §1）。纯函数：输入主体、门店快照、运营填的资料，输出 near-pay 请求。
 *
 * <p>门店的 externalId / revision / profileHash 必须与门店同步推给 near-pay 的完全一致，否则 near-pay 关联不上门店投影 ——
 * 所以直接复用 {@link NearPayStoreProfile#request}，不另算一遍。
 */
public final class PayServiceApplyRequests {

    public static final String PERM = "merchant:merchant:pay-apply";

    /** 运营在界面上填的主体资料 */
    public record Form(@NotBlank @Size(max = 128) String legalName,
                       @NotBlank @Size(max = 64) String registrationNo,
                       @Size(max = 64) String representativeName, @Size(max = 32) String contactPhone,
                       @Size(max = 256) String registeredAddress,
                       @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestNo) {
        /** Historical pure builders remain source-compatible; actual submissions require an explicit stable key. */
        public Form(String legalName,String registrationNo,String representativeName,String contactPhone,String registeredAddress) {
            this(legalName,registrationNo,representativeName,contactPhone,registeredAddress,null);
        }
    }

    private PayServiceApplyRequests() {
    }

    /** 运营代为申请（运营端「申请支付服务」） */
    public static PaySourceLinkApi.Apply build(StoreSnapshotReader.Entity entity, List<StoreSnapshotReader.Snapshot> stores,
                                               Form form, String operatorNo, StoreSyncProperties props) {
        return build(entity, stores, form, "OPERATOR", operatorNo, props);
    }

    /**
     * @param realm   OPERATOR（运营代办）/ MERCHANT（商家在 App 自己提交）
     * @param subject 操作人：运营工号或商家账号号
     */
    public static PaySourceLinkApi.Apply build(StoreSnapshotReader.Entity entity, List<StoreSnapshotReader.Snapshot> stores,
                                               Form form, String realm, String operatorNo, StoreSyncProperties props) {
        if (entity == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (form == null || blank(form.legalName()) || blank(form.registrationNo()) || blank(operatorNo)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String legalName = form.legalName().trim();
        String registrationNo = form.registrationNo().trim();
        long revision = Math.max(1, entity.revision());
        List<PaySourceLinkApi.StoreInput> storeInputs = stores.stream().filter(s -> !s.deleted()).map(s -> {
            Map<String, Object> r = NearPayStoreProfile.request(s, props);
            return new PaySourceLinkApi.StoreInput((String) r.get("storeRef"), ((Number) r.get("revision")).longValue(),
                    (String) r.get("profileHash"), s.storeNo());
        }).toList();
        if (storeInputs.size() > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        PaySourceLinkApi.Profile profile = new PaySourceLinkApi.Profile("FIAT", legalName, registrationNo,
                trimOrNull(form.registeredAddress()), trimOrNull(form.representativeName()),
                trimOrNull(form.contactPhone()), null, List.of());
        String event = "ais-apply-" + entity.entityNo() + "-" + revision + "-" + digest(profile).substring(0, 8);
        return new PaySourceLinkApi.Apply(event, props.sourceScope(), entity.entityNo(), revision,
                blank(entity.name()) ? legalName : entity.name(), storeInputs,
                new PaySourceLinkApi.ActorRef(realm, operatorNo, "1", PERM), "FIAT", revision, profile);
    }

    private static String digest(PaySourceLinkApi.Profile p) {
        String canonical = String.join("\u0001", p.purpose(), n(p.legalName()), n(p.registrationNo()),
                n(p.registeredAddress()), n(p.representativeName()), n(p.contactPhone()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
