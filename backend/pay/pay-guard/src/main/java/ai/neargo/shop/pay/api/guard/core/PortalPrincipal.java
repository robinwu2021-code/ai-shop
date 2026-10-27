package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.api.guard.PortalFace;
import java.util.Objects;
import java.util.Set;

/**
 * 接入层认出的调用方。写进四向记录的「来源端 / 来源」两列就取自这里（渠道封装设计 §8）。
 *
 * @param face        从哪个入口进来（来源端）
 * @param subjectType 运营人员 / 商家员工 / 第三方应用 / 终端 / 收银会话
 * @param subjectId   该主体的标识（员工号、AppId、终端号、收银令牌号）
 * @param storeId     能确定门店时的门店 id（按门店收敛数据域）；平台级调用为 {@code null}
 * @param permissions 已解析好的权限码（运营端 {@code pay:*}、商家端门店内权限）；第三方与终端为空集
 */
public record PortalPrincipal(PortalFace face, String subjectType, String subjectId, Long storeId,
                              Set<String> permissions) {

    public PortalPrincipal {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
