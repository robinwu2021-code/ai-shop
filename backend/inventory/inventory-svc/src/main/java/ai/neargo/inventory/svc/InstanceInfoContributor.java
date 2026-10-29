package ai.neargo.inventory.svc;

import ai.neargo.inventory.guard.InstanceProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code /actuator/info} 报出这一套是谁、为哪些系统服务。
 *
 * <p>调用方启动时比对它：生产两套各连各的库，地址配错的症状不是报错，而是数据写进了另一本账。
 */
@Component
public class InstanceInfoContributor implements InfoContributor {

    private final InstanceProperties instance;

    public InstanceInfoContributor(InstanceProperties instance) {
        this.instance = instance;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("instance", Map.of(
                "code", instance.getCode(),
                "allowedSystems", instance.getAllowedSystems()));
    }
}
