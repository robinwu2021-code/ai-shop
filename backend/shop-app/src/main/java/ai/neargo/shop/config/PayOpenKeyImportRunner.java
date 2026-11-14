package ai.neargo.shop.config;

import ai.neargo.shop.pay.secret.OpenKeyImport;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 试点接入方密钥迁入密钥模块的一次性动作（开发计划 M3.10；手册：
 * {@code docs/technical/支付服务-开放API密钥迁入密钥模块-运维手册.md}）。与 {@link EstateImportRunner} 同一做法：
 * <b>默认不存在</b>，配了 {@code shop.pay.open.key-import.action} 才在启动时跑一次，结果打进日志，跑完把配置撤掉。
 *
 * <pre>
 * shop.pay.open.key-import.action   = upload | enable | status
 * shop.pay.open.key-import.apps     = APP-A,APP-B
 * shop.pay.open.key-import.operator = 员工号（upload 与 enable 必须是两个人）
 * </pre>
 * 出错不拖垮启动：这是补数据的动作，失败记 error 走人。日志里只有版本、状态、指纹，没有任何密钥。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.open.key-import.action")
public class PayOpenKeyImportRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PayOpenKeyImportRunner.class);

    private final OpenKeyImport keyImport;
    private final String action;
    private final List<String> apps;
    private final String operator;

    public PayOpenKeyImportRunner(OpenKeyImport keyImport,
                                  @Value("${shop.pay.open.key-import.action}") String action,
                                  @Value("${shop.pay.open.key-import.apps:}") String apps,
                                  @Value("${shop.pay.open.key-import.operator:}") String operator) {
        this.keyImport = keyImport;
        this.action = action.trim();
        this.apps = Arrays.stream(apps.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.operator = operator.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> report = switch (action) {
                case "upload" -> keyImport.upload(apps, operator);
                case "enable" -> keyImport.enable(apps, operator);
                case "status" -> keyImport.status(apps);
                default -> List.of("unknown action '" + action + "' (upload | enable | status)");
            };
            report.forEach(line -> LOG.warn("[pay-key-import] {} {}", action, line));
            LOG.warn("[pay-key-import] {} done for {} - remove shop.pay.open.key-import.* now", action, apps);
        } catch (RuntimeException e) {
            LOG.error("[pay-key-import] {} failed: {}", action, e.toString());
        }
    }
}
