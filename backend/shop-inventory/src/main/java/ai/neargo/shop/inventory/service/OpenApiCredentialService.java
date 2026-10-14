package ai.neargo.shop.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Open API 凭证签发与校验。
 *
 * <p><b>不复用 B 端 JWT</b>：那是给人用的 —— 有效期短、绑设备、要短信验证码。
 * 服务端对接拿不到，也不该拿到。
 */
public interface OpenApiCredentialService {

    /**
     * 校验一对 key/secret，返回它能看的业主。
     *
     * @throws ai.neargo.shop.common.BizException {@code UNAUTHORIZED} ——
     *         key 不存在、secret 不对、已吊销、已过期，<b>四种都返回同一个错</b>：
     *         分开报等于告诉对方「这个 key 是存在的，只是密码错了」
     */
    String ownerOf(String appKey, String appSecret, String requiredScope);

    /**
     * 签发一对 key/secret。
     *
     * <p><b>此前没有这个口</b> —— 于是开放接口虽然写完了，却<b>没有任何办法发出一把钥匙</b>：
     * 唯一的路是直接往 {@code inv_open_credential} 里插，而那正是
     * {@code inventory-write-ownership} 守卫拦的事（域外写 inv_* 表）。
     * 一个谁也用不了的开放接口不叫做完了。
     *
     * <p><b>secret 只在这一刻明文出现一次</b>，库里存的是哈希。丢了只能重发 ——
     * 能找回的密钥等于没有密钥。
     *
     * @param scopes 逗号分隔，如 {@code "read,stock:sync"}
     * @param expiresAt null = 不过期。**长期对接也建议给个期限**：
     *                  一把永不过期的钥匙，泄露了就没有自然终点
     */
    Issued issue(String ownerId, String name, String scopes, LocalDateTime expiresAt);

    /**
     * 吊销。**发得出、收不回的钥匙是半截功能** —— 对方换了对接商、密钥泄露了，
     * 唯一的处置就是这一下。
     *
     * <p>吊销不删行：删了之后「这把钥匙什么时候被谁停的」就没人答得上来。
     * 状态置 {@code REVOKED}，而校验那边四种失败一个错码，对方看到的与
     * 「key 不存在」一模一样 —— 不告诉他「这把钥匙曾经是真的」。
     */
    void revoke(String credentialId);

    /**
     * 某个业主发过哪些钥匙。**发得出、看不见等于发不出** ——
     * 运营被问「他们那把还能用吗」时，没有这一条就只能进库里查。
     *
     * <p>吊销过的也返回：这一列要能回答「什么时候停的」，
     * 而那正是「吊销不删行」的理由。
     */
    List<Listed> list(String ownerId);

    /** @param appSecret <b>明文，仅此一次</b>。库里只有哈希，之后任何地方都拿不回来 */
    record Issued(String credentialId, String appKey, String appSecret) {
    }

    /**
     * 列表里的一行。**没有 secret，一个字段都不给** ——
     * 库里只有哈希，而一个「看起来能看到密钥」的列表会让人以为丢了还能找回来。
     */
    record Listed(String credentialId, String appKey, String name, String scopes,
                  String status, LocalDateTime expiresAt, LocalDateTime lastUsedAt,
                  LocalDateTime createdAt) {
    }
}
