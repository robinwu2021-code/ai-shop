package ai.neargo.secret.api;

/**
 * 密钥 / 密文归属谁：命名空间 + 调用方模块的对象类型 + 对象 id（{@code pay_merchant.id}、{@code pay_app.id} …）。
 *
 * <p>{@code ownerType} 是字符串而不是枚举：值域属于调用方模块（PAY_MERCHANT / PARTNER_APP / ONBOARDING_MATERIAL …），
 * 密钥模块不认识它们的语义，只拿来当 {@code sec_key} 唯一键与密文附加认证数据的一部分。
 */
public record KeyOwner(SecretNamespace namespace, String ownerType, long ownerId) {

    public KeyOwner {
        if (namespace == null || ownerType == null || ownerType.isBlank()) {
            throw new IllegalArgumentException("namespace and ownerType are required");
        }
        if (ownerType.length() > 24) {
            throw new IllegalArgumentException("ownerType longer than sec_*.owner_type VARCHAR(24): " + ownerType);
        }
    }
}
