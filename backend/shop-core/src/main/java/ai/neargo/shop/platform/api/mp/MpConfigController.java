package ai.neargo.shop.platform.api.mp;

import ai.neargo.shop.platform.config.BootstrapConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端配置端点（[API 清单 §2.14]）。游客可访问 —— 冷启动时还没有登录态。
 *
 * <p>Controller 的样子就该是这样：一行路由 + 一次 Service 调用，没有业务。
 * 返回值不包 {@code ApiResult}，由 {@code ApiResponseWrapper} 统一处理。
 */
@Profile("api")
@RestController
@RequestMapping("/mp/config")
public class MpConfigController {

    private final BootstrapConfigService configService;

    public MpConfigController(BootstrapConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/bootstrap")
    public BootstrapConfigService.BootstrapConfig bootstrap() {
        return configService.get();
    }
}
