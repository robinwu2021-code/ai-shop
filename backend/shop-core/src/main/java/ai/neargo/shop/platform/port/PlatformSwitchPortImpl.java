package ai.neargo.shop.platform.service;

import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.platform.PlatformConfigService.FeatureFlagVO;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 平台开关的跨域读口。
 *
 * <p><b>不是新机制</b>：底下就是既有的 feature flags（{@code sys_setting} 的
 * {@code platform.flags} 一行 JSON，运营端 {@code /ops/feature-flags} 读写）。
 * 那套东西 V10 就在了，只是一直没有界面用它 —— 默认值还是 {@code []}。
 * 我一开始另建了一张 sys_config 表，那是重复造：同一件事有两套配置机制之后，
 * 下一个人改开关会改错地方，而两边都「看起来是对的」。
 *
 * <p>存在的理由是**跨域**：商品域要判「上架拦不拦」、商家域要判「摆货架拦不拦」，
 * 而开关属于平台域。让那两个域直接依赖 PlatformConfigService，域边界就没了。
 *
 * <p><b>忽略 rolloutPercent。</b>feature flag 带灰度百分比，但资质闸门不能灰度 ——
 * 一半商家被拦一半不被拦，客服无法解释，商家自己也无法复现。
 * 这一类「规则型」开关只认 enabled。
 */
@Service
public class PlatformSwitchService implements PlatformSwitchPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformSwitchService.class);

    private final PlatformConfigService configService;

    public PlatformSwitchService(PlatformConfigService configService) {
        this.configService = configService;
    }

    @Override
    public boolean bool(String key, boolean def) {
        try {
            for (FeatureFlagVO f : configService.featureFlags()) {
                if (key.equals(f.key())) {
                    return f.enabled();
                }
            }
            return def;
        } catch (Exception e) {
            /*
             * 读不到就走默认值，**不抛** —— 让一次配置读失败把整个上架流程打成 500
             * 是不成比例的：开关是策略，不是数据。打 WARN 是为了别让它静悄悄地
             * 一直走默认值（那样看起来一切正常，而开关其实从未生效过）。
             */
            log.warn("[平台开关] 读 {} 失败，本次走默认值 {}", key, def, e);
            return def;
        }
    }
}
