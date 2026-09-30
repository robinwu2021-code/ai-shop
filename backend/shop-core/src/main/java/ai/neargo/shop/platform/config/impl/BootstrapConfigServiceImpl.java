package ai.neargo.shop.platform.config.impl;

import ai.neargo.shop.platform.config.BootstrapConfigService;
import ai.neargo.shop.platform.config.ShopProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 配置文件驱动的实现（S0）。刻意做成 {@code interface + impl} 而不是一个具体类
 * —— powerbank 那边早期写成具体类的两个 Service，后来都要回填规整（TDD-backend §3.1）。
 */
@Service
@EnableConfigurationProperties(ShopProperties.class)
public class BootstrapConfigServiceImpl implements BootstrapConfigService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BootstrapConfigServiceImpl.class);

    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

    private final ShopProperties props;
    /**
     * 平台开关（运营端「功能开关」那一屏）。
     *
     * <p>setter 注入：切片测试里没有它时，bootstrap 退回只发 yml 里那几个 ——
     * 少几个开关不该让冷启动这条端点整个挂掉。
     */
    private ai.neargo.shop.platform.PlatformConfigService platformConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformConfig(ai.neargo.shop.platform.PlatformConfigService platformConfig) {
        this.platformConfig = platformConfig;
    }

    public BootstrapConfigServiceImpl(ShopProperties props) {
        this.props = props;
    }

    /**
     * <p><b>yml 里的开关与运营端的开关在这里合流</b>，端上只认一份。
     *
     * <p>为什么要合：运营端那一屏能改的开关此前<b>到不了 C 端</b> —— 端上拿到的只有
     * yml 里那几个，而 yml 改了要重启。于是「运营后台一键关」对 C 端的任何行为都不成立。
     * 合流之后，像 {@code merchant.apply.mp-visible}（小程序显不显示入驻入口）
     * 这种会影响小程序审核的开关，被驳回时**关一下就止血**，不用重新发版重新提审。
     *
     * <p>同名时以**运营端为准**：他改了就该生效，yml 是兜底不是覆盖。
     */
    @Override
    public BootstrapConfig get() {
        var features = new java.util.HashMap<>(props.getFeatures());
        if (platformConfig != null) {
            for (var f : platformConfig.featureFlags()) {
                features.put(f.key(), f.enabled());
            }
        }
        return new BootstrapConfig(
                props.getDefaultSkin(),
                Map.copyOf(features),
                props.getMinAppVer(),
                props.getServiceHours(),
                merchantApp());
    }

    /**
     * 商家版 App 那一档：**版本清单在就以它为准**。
     *
     * <p>清单（{@code /dl/latest.json}）由发版脚本写，是「最新版是哪个」的唯一真源。
     * 在这之前版本号写死在三处（官网 site.config、服务器 env、人的记性），
     * 每处都要手工跟，于是每处都会掉队 —— env 那处掉了二十多个版本
     * （0.4.98 vs 0.5.21），而掉队时下载照样 200、照样装得上，只是功能旧。
     *
     * <p><b>读不到就回落到 yml/env 的那两个值，不抛。</b> 清单是为了省掉手工同步，
     * 不是新增一个「它坏了整个冷启动就挂」的依赖：这条端点是 C 端冷启动的第一跳。
     */
    private MerchantApp merchantApp() {
        String android = props.getMerchantApp().getAndroid();
        String ios = props.getMerchantApp().getIos();
        String file = props.getMerchantApp().getManifestFile();
        if (file == null || file.isBlank()) {
            return new MerchantApp(android, ios, "");
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(file);
            if (!java.nio.file.Files.isReadable(path)) {
                log.warn("[app] 版本清单读不到，回落 env：{}", file);
                return new MerchantApp(android, ios, "");
            }
            var node = MAPPER.readTree(java.nio.file.Files.readString(path));
            String url = text(node, "url");
            String ver = text(node, "version");
            /*
             * **只认完整地址**（清单里写的就是 https://…）。
             * 半截路径（/dl/…）在小程序里打不开，而它「看起来是有值的」——
             * 端上不会报错，只是点了没反应。宁可继续用 env 那条旧地址。
             */
            if (url.startsWith("http://") || url.startsWith("https://")) {
                android = url;
            } else if (!url.isBlank()) {
                log.warn("[app] 版本清单里的 url 不是完整地址，仍用 env：{}", url);
            }
            return new MerchantApp(android, ios, ver);
        } catch (Exception e) {
            // 清单坏了不该让冷启动挂掉；记一条能查的日志，其余照旧
            log.warn("[app] 版本清单解析失败，回落 env：{}（{}）", file, e.toString());
            return new MerchantApp(android, ios, "");
        }
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        var v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
