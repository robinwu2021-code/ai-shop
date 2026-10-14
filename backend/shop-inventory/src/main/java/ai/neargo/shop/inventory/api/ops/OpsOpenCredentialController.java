package ai.neargo.shop.inventory.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.OpenApiCredentialService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开放对接凭证的发放口（运营端）。
 *
 * <p><b>为什么这一屏此前不存在，而它不存在等于开放接口没做完。</b>
 * 三个 {@code /open/v1} 端点早就写完了，签发与吊销的服务层也在
 * （{@code OpenApiCredentialService}，它自己的注释就写着「此前没有这个口」）——
 * 缺的只是一个控制器与一屏界面。于是唯一发得出钥匙的办法是直接往
 * {@code inv_open_credential} 里插，而那正是 {@code inventory-write-ownership}
 * 守卫拦的事。**一个谁也拿不到钥匙的开放接口，不叫做完了。**
 *
 * <h2>权限分两档，不是一档</h2>
 * 看列表用 {@code product:sku:read}（与同一个 section 里另外三页一致），
 * 签发与吊销用 {@code merchant:mode:update}。
 * **发一把能读商家全部库存的钥匙，不该和「看一眼库存」同一个门槛** ——
 * 前者是把数据交出平台，后者只是在平台内部看一眼。
 *
 * <p>看列表那一档**特意跟着 section 走**：`ops-web/lib/nav.ts` 里
 * 进销存这个 section 的 {@code module} 是 `product`，而它是
 * <b>权限码前缀</b>（canModule 按前缀过滤整段）。列表若挂 `merchant:*`，
 * 结果是「能看见这个模块的人打不开这一页，能打开的人看不见这个模块」。
 *
 * <h2>没有「改」</h2>
 * 名字、范围、有效期都不能改：改了之后「这把钥匙当初是按什么发的」
 * 就没人答得上来。要换条件就吊销重发 —— 与单据「不可修改，只能作废重录」
 * 是同一条道理。
 */
@Profile("ops")
@ConditionalOnInventory
@RestController
public class OpsOpenCredentialController {

    private final OpenApiCredentialService credentials;
    private final InventoryAclService acl;

    public OpsOpenCredentialController(OpenApiCredentialService credentials,
                                       InventoryAclService acl) {
        this.credentials = credentials;
        this.acl = acl;
    }

    /** 某个商家发过哪些钥匙。**吊销过的也在列** —— 这一列要能回答「什么时候停的」 */
    @PreAuthorize("@perm.can('" + Perms.INVENTORY_CREDENTIAL_READ + "')")
    @GetMapping("/ops/inventory/credentials")
    public List<OpenApiCredentialService.Listed> list(@RequestParam String entityNo) {
        return credentials.list(acl.ownerIdOf(entityNo));
    }

    /**
     * 签发。**返回体里的 secret 是它这辈子唯一一次明文出现** ——
     * 库里存的是哈希，这个响应关掉就再也拿不回来，只能吊销重发。
     */
    @PreAuthorize("@perm.can('" + Perms.INVENTORY_CREDENTIAL_GRANT + "')")
    @PostMapping("/ops/inventory/credentials")
    public OpenApiCredentialService.Issued issue(@RequestBody IssueReq req) {
        return credentials.issue(acl.ownerIdOf(req.entityNo()), req.name(),
                req.scopes(), req.expiresAt());
    }

    /** 吊销。**发得出、收不回的钥匙是半截功能** */
    @PreAuthorize("@perm.can('" + Perms.INVENTORY_CREDENTIAL_GRANT + "')")
    @PostMapping("/ops/inventory/credentials/{credentialId}/revoke")
    public void revoke(@PathVariable String credentialId) {
        credentials.revoke(credentialId);
    }

    /**
     * @param scopes 逗号分隔，如 {@code "read,stock:sync"}
     * @param expiresAt null = 不过期。**长期对接也建议给个期限** ——
     *                  一把永不过期的钥匙，泄露了就没有自然终点
     */
    public record IssueReq(String entityNo, String name, String scopes,
                           LocalDateTime expiresAt) {
    }
}
