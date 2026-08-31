package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.payclient.PointsPolicyAppService;
import ai.neargo.shop.settle.PointsService.ClientPointsPolicy;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class PointsPolicyAppServiceImpl implements PointsPolicyAppService {

    /** 与 {@code PointsServiceImpl} 用同一个键。改一处要改两处 —— 所以两边都写了这句。 */
    private static final String POLICY_KEY = "points.client.policy";
    private static final String POLICY_DEFAULT =
            "{\"earnDeny\":[],\"redeemDeny\":[],\"offlineRedeem\":true}";

    private final SettingPort settingPort;
    private final AuditLogPort auditLogPort;
    private final ObjectMapper json;

    public PointsPolicyAppServiceImpl(SettingPort settingPort, AuditLogPort auditLogPort,
                                      ObjectMapper json) {
        this.settingPort = settingPort;
        this.auditLogPort = auditLogPort;
        this.json = json;
    }

    @Override
    public ClientPointsPolicy policy() {
        return json.readValue(settingPort.get(POLICY_KEY, POLICY_DEFAULT), ClientPointsPolicy.class);
    }

    @Override
    public ClientPointsPolicy save(ClientPointsPolicy req, String operatorNo) {
        List<String> earn = requireValidScenes(req.earnDeny());
        List<String> redeem = requireValidScenes(req.redeemDeny());
        ClientPointsPolicy saved = new ClientPointsPolicy(earn, redeem, req.offlineRedeem());
        settingPort.put(POLICY_KEY, json.writeValueAsString(saved), operatorNo);
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
