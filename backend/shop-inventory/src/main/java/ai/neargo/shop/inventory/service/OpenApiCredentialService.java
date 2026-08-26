package ai.neargo.shop.inventory.service;

/**
 * Open API 凭证校验。
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
}
