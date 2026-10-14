package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.payclient.PointsPolicyAppService;
import ai.neargo.shop.pay.PointsService.ClientPointsPolicy;
import ai.neargo.shop.pay.setting.PaySettingService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class PointsPolicyAppServiceImpl implements PointsPolicyAppService {

    /**
     * 与 {@code PointsServiceImpl} 用同一个键，<b>而且必须是同一张表</b>。
     *
     * <h2>2026-09-01 修：这里曾经写 sys_setting，而支付域读 pay_setting</h2>
     * M2 把这个键搬进支付域自己的设置表（V285）时，<b>漏了运营端这一侧</b>。
     * 于是运营在页面上禁用某个端的积分发放，保存成功、页面回显正确，
     * 而<b>支付域完全读不到这个改动 —— 积分照发</b>。
     * 一个「改了没生效且不报错」的开关。
     *
     * <p>没有被发现是因为线上两张表里这个键<b>都没有值</b> ——
     * 运营从来没改过它，两边一直在用代码默认值。
     * 修的时候查过线上，因此不需要数据迁移。
     *
     * <p>旧注释写的是「改一处要改两处 —— 所以两边都写了这句」，
     * 它防的是<b>键名</b>不一致，而实际出问题的是<b>表</b>不一致。
     * 现在两边都走 {@link PaySettingService}，同类问题由
     * {@code SettingKeyOwnershipTest} 拦。
     */
    private static final String POLICY_KEY = "points.client.policy";
    private static final String POLICY_DEFAULT =
            "{\"earnDeny\":[],\"redeemDeny\":[],\"offlineRedeem\":true}";

    private final PaySettingService paySettings;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public PointsPolicyAppServiceImpl(PaySettingService paySettings, AuditLogPort auditLogPort,
                                      ObjectMapper json) {
        this.paySettings = paySettings;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @Override
    public ClientPointsPolicy policy() {
        return json.readValue(paySettings.get(POLICY_KEY, POLICY_DEFAULT), ClientPointsPolicy.class);
    }

    @Override
    public ClientPointsPolicy save(ClientPointsPolicy req, String operatorNo) {
        List<String> earn = requireValidScenes(req.earnDeny());
        List<String> redeem = requireValidScenes(req.redeemDeny());
        ClientPointsPolicy saved = new ClientPointsPolicy(earn, redeem, req.offlineRedeem());
        paySettings.put(POLICY_KEY, json.writeValueAsString(saved), operatorNo);
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

    /**
     * <b>校验放在这里而不是 controller</b>：它是业务规则（哪些端名合法），
     * 不是 HTTP 的事。放在 controller 里的话，将来多一个入口
     * （比如内部口或批量导入）就会漏掉这一层。
     */
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
