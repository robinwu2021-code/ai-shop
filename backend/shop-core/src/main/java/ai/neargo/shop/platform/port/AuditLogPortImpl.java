package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.stereotype.Component;

/**
 * {@link AuditLogPort} 实现：转调 {@link OpsService#audit}。
 *
 * <p>这里薄得像没有——它的价值不在于做了什么，而在于**别的域只认这个接口**：
 * platform 域将来把审计换成事件流或独立服务，改的是这一个类。
 */
@Component
public class AuditLogPortImpl implements AuditLogPort {

    private final OpsService opsService;

    public AuditLogPortImpl(OpsService opsService) {
        this.opsService = opsService;
    }

    @Override
    public void record(String action, String target, String detail) {
        opsService.audit(action, target, detail);
    }
}
