package ai.neargo.secret.api;

/**
 * 冻结的密钥版本：{@code (sec_key.id, sec_key_version.version_index)}。
 *
 * <p>业务单据冻结的是它（{@code pay_merchant.sec_key_id} / {@code sec_key_version}、{@code pay_order.sec_key_version}）：
 * 退款、查单、验旧回调<b>一律用冻结版本</b>，不取「当前密钥」（表结构说明 §3.4）。
 */
public record KeyVersionRef(long keyId, int versionIndex) {

    public KeyVersionRef {
        if (keyId <= 0 || versionIndex <= 0) {
            throw new IllegalArgumentException("keyId and versionIndex must be positive");
        }
    }
}
