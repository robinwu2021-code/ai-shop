package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.entity.InvOwner;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 独立服务的平台层（TDD §2.4，M2 T2.1）：开通业主、签发服务凭证、认凭证、翻转写入方。
 *
 * <p><b>业主取自凭证，不信请求体</b>：一把服务凭证绑定一个系统、一个业主；
 * 入口守卫用 {@link #authenticate} 认出这两样，之后所有读写都限定在这本账上。
 */
public interface ServicePlatformService {

    /**
     * 开通一个业主（一本库存账）。写入方默认是独立服务。
     *
     * @param externalType 宿主组织类型（STORE / CHAIN / ENTITY / PLATFORM …），服务不解释；空 = 按系统取存量缺省
     * @throws ai.neargo.inventory.api.InventoryException {@code BAD_REQUEST} 系统未登记或已停用；
     *         {@code CONFLICT} 同系统下这个「类型 + 外部引用」已开通
     */
    String openOwner(String systemCode, String externalType, String externalRef, String name, String operator);

    /**
     * 签发服务凭证。secret 只在返回值里出现这一次。
     *
     * @param capabilities 这把凭证能用的能力包；空 = 只按业主档案
     */
    Issued issueCredential(String ownerId, String name, Set<Capability> capabilities,
                           LocalDateTime expiresAt, String operator);

    /**
     * 签发系统凭证：不绑业主，调用时以请求头指定本系统的业主（只放行写入方为独立服务的业主）。
     * 给多商家的宿主用（商城），免得逐个商家签凭证、再在宿主里造一套密钥存取。
     */
    Issued issueSystemCredential(String systemCode, String name, Set<Capability> capabilities,
                                 LocalDateTime expiresAt, String operator);

    /**
     * @param externalType 空 = 只按编号找；同号有多本（门店与连锁撞号）时 {@code CONFLICT owner_type_required}
     * @return 本系统下这个外部引用对应的业主；没有为空
     */
    InvOwner ownerByRef(String systemCode, String externalType, String externalRef);

    /**
     * 认一把服务凭证。key 不存在、secret 不对、已吊销、已过期、不是服务凭证 ——
     * <b>五种都是同一个 {@code UNAUTHORIZED}</b>：分开报等于给对方一个枚举 key 的探测接口。
     */
    Authenticated authenticate(String appKey, String appSecret);

    /** 翻转写入方（{@code EMBEDDED} / {@code SVC}），商城切流按业主灰度用（AC-27）。 */
    void setWriteAuthority(String ownerId, String writeAuthority, String operator);

    /** @throws ai.neargo.inventory.api.InventoryException {@code NOT_FOUND} */
    InvOwner owner(String ownerId);

    record Issued(String credentialId, String appKey, String appSecret) {
    }

    /** @param ownerId 系统凭证为空 */
    record Authenticated(String systemCode, String ownerId, String credentialId, Set<Capability> capabilities) {
    }
}
