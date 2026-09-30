package ai.neargo.shop.elec.api.c;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.MeDtos.MeView;
import ai.neargo.shop.elec.service.ElecMeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /elec/me}：<b>唯一一个跨买家与供应商两面的接口</b>，只给身份与角标、不给内容。
 *
 * <p>放在买家面的包里：它服务的是「这个小程序账号」，而账号本身是 C 端账号 ——
 * 还不是供应商的人也要调它，放进 {@code /elec/b} 就成了「不是供应商也能调的供应商接口」。
 *
 * <p>要登录、<b>不要</b>手机号：它要告诉端上「你还没绑」。
 */
@ConditionalOnElec
@RestController
public class ElecMeController {

    private final ElecMeService me;

    public ElecMeController(ElecMeService me) {
        this.me = me;
    }

    @GetMapping("/elec/me")
    public MeView me() {
        return me.me(SecurityUtils.currentUserNo());
    }
}
