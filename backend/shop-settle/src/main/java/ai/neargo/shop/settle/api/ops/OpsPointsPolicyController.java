package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.settle.PointsService.ClientPointsPolicy;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 平台端 · 积分的<b>端策略</b>：哪个端不发放、哪个端不核销、线下付能不能抵扣。
 *
 * <p><b>单独一个 Controller，不并进 {@code OpsPointsController}</b>：
 * 那个类的注释写着「只读，不给写侧」，讲的是<b>积分池的钱不许用手改</b>。
 * 那句话很重要，不该因为这里多了一个策略开关而变得含糊 ——
 * 策略是策略，余额是余额。
 *
 * <p>⚠️ <b>这不是合规硬闸。</b> 端标识来自客户端请求头、天然可伪造，
 * 而且今天还不是每个端都在发。它能做到的是「让自报家门的那个端不发/不用积分」，
 * 做不到的是「保证某个端一定拿不到积分」。要后者得在端侧和签名上做文章。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPointsPolicyController {

    /** 与 {@code PointsServiceImpl} 用同一个键。改一处要改两处 —— 所以两边都写了这句。 */
    private static final String POLICY_KEY = "points.client.policy";
    private static final String POLICY_DEFAULT =
            "{\"earnDeny\":[],\"redeemDeny\":[],\"offlineRedeem\":true}";

    private final SettingPort settingPort;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public OpsPointsPolicyController(SettingPort settingPort, AuditLogPort auditLogPort,
                                     ObjectMapper json) {
        this.settingPort = settingPort;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @GetMapping("/ops/points/client-policy")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ClientPointsPolicy policy() {
        return json.readValue(settingPort.get(POLICY_KEY, POLICY_DEFAULT), ClientPointsPolicy.class);
    }

    /**
     * 保存端策略。
     *
     * <p><b>取值域当场校验</b>：写进去一个拼错的端名，它不会报错，
     * 只会安安静静地谁也拦不住 —— 而运营会以为已经关掉了。
     * 这类「设置成功但不生效」的故障，事后极难从现象追回到这一行。
     */
    @PostMapping("/ops/points/client-policy")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ClientPointsPolicy save(@RequestBody ClientPointsPolicy req) {
        List<String> earn = requireValidScenes(req.earnDeny());
        List<String> redeem = requireValidScenes(req.redeemDeny());
        ClientPointsPolicy saved = new ClientPointsPolicy(earn, redeem, req.offlineRedeem());
        settingPort.put(POLICY_KEY, json.writeValueAsString(saved), SecurityUtils.currentUserNo());
        /*
         * 留痕不是可选项：这个开关决定用户在某个端上能不能拿到/用掉积分，
         * 而积分是平台对用户的负债。「用户说昨天还能抵，今天不能了」这类工单，
         * 没有这条记录就只能靠猜。
         */
        auditLogPort.record("POINTS_CLIENT_POLICY", POLICY_KEY,
                "禁发放 %s｜禁核销 %s｜线下抵扣 %s".formatted(
                        earn.isEmpty() ? "无" : String.join(",", earn),
                        redeem.isEmpty() ? "无" : String.join(",", redeem),
                        saved.offlineRedeem() ? "开" : "关"));
        return saved;
    }

    private List<String> requireValidScenes(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        for (String v : raw) {
            if (!PayScenes.isValid(v)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        return List.copyOf(raw);
    }
}
