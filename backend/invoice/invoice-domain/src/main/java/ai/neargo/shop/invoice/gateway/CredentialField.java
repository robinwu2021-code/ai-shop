package ai.neargo.shop.invoice.gateway;

import java.util.List;

/**
 * 一个凭据字段的声明（TDD-发票-供应商能力定义完善 D3，与 ADR-064 的 CredentialField 同名同义）：
 * 放在账号三元的哪一位、页面用哪个词条、是否只写不读、格式校验、能否由主体资料自动带出。
 *
 * @param labelKey   页面词条后缀：b-app 先找 {@code salesInvoice.credential.<供应商编码>.<labelKey>}，没有再用通用词条
 * @param secret     只写不读（进 secret 模块，页面只显示「已保存」）
 * @param pattern    格式校验（正则）；空 = 不校验
 */
public record CredentialField(Slot slot, String labelKey, boolean secret, String pattern, Derived derivedFrom) {

    /** 三元里的位置：{@link InvoiceGateway.ProviderAccount} 的 accountCode / keyId / secret。 */
    public enum Slot { ACCOUNT_CODE, KEY_ID, SECRET }

    /** 由主体资料自动带出，页面不必填：如汎宇的 SELLERID 就是卖方统编。 */
    public enum Derived { NONE, SELLER_TAX_ID }

    public CredentialField {
        if (slot == null || labelKey == null || labelKey.isBlank()) throw new IllegalArgumentException("slot and label required");
        derivedFrom = derivedFrom == null ? Derived.NONE : derivedFrom;
    }

    /** 没有专门声明的供应商沿用的三项：代号存业务表、另两项只写不读。 */
    public static final List<CredentialField> GENERIC = List.of(
            new CredentialField(Slot.ACCOUNT_CODE, "accountCode", false, null, Derived.NONE),
            new CredentialField(Slot.KEY_ID, "keyId", true, null, Derived.NONE),
            new CredentialField(Slot.SECRET, "secret", true, null, Derived.NONE));
}
