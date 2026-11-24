package ai.neargo.shop.approval.opskit;

import ai.neargo.opskit.connector.approval.ApprovalClient;
import ai.neargo.shop.approval.port.OpsKitApprovalPort;
import ai.neargo.shop.approval.port.OpsKitWebhookPort;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.notify.OpsAlertPort;
import ai.neargo.shop.spi.platform.OpsStaffPort;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** OpsKit 审批对接装配。{@code shop.opskit.enabled=true} 才生效（TDD-OpsKit审批对接 §1.2）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.opskit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OpsKitProperties.class)
public class OpsKitApprovalConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpsKitApprovalConfiguration.class);

    @Bean
    ApprovalSubjects opskitApprovalSubjects(List<ApprovalSubject> all, OpsKitProperties props) {
        return new ApprovalSubjects(all, props);
    }

    @Bean
    ApprovalLinkStore opskitApprovalLinkStore(JdbcTemplate db) {
        return new ApprovalLinkStore(db);
    }

    @Bean
    ApprovalOutcomes opskitApprovalOutcomes(ApprovalSubjects subjects, ApprovalLinkStore links) {
        return new ApprovalOutcomes(subjects, links);
    }

    @Bean
    ApprovalClient opskitApprovalClient(OpsKitProperties props, ApprovalSubjects subjects, ApprovalOutcomes outcomes, JdbcTemplate db,
                                        ObjectProvider<OpsStaffPort> staff, ObjectProvider<OpsAlertPort> alerts) {
        OpsStaffPort staffPort = staff.getIfAvailable();
        OpsAlertPort alertPort = alerts.getIfAvailable();
        var notifier = staffPort == null || alertPort == null ? null : new OpsKitTodoNotifier(staffPort, alertPort);
        var b = ApprovalClient.builder()
                .baseUrl(props.baseUrl()).workspace(props.workspace())
                .credential(props.credential()).signingSecret(props.signingSecret())
                .timeout(props.timeout())
                .subjectPrefixes("shop.")
                .ledger(new DbEventLedger(db))
                .onFinished(outcomes::finished)
                .onTaskCreated(e -> {
                    if (notifier != null) {
                        notifier.taskCreated(e);
                    } else {
                        log.info("[opskit] 新待办 {}（没有站内消息出口）", e.idemKey());
                    }
                });
        for (ApprovalSubject s : subjects.all()) {
            b.action(ApprovalSubjects.actionOf(s), ctx -> outcomes.apply(s, ctx));
        }
        return b.build();
    }

    @Bean
    OpsKitApprovalPort opskitApprovalPort(ApprovalSubjects subjects, ApprovalLinkStore links, OutboxEventBus outbox) {
        return new OpsKitApprovalPort(subjects, links, outbox);
    }

    @Bean
    OpsKitWebhookPort opskitWebhookPort(ApprovalClient client) {
        return new OpsKitWebhookPort(client);
    }

    @Bean
    OpsKitSubmitConsumer opskitSubmitConsumer(ApprovalClient client, ApprovalSubjects subjects, ApprovalLinkStore links) {
        return new OpsKitSubmitConsumer(client, subjects, links);
    }

    @Bean
    OpsKitReconcileJob opskitReconcileJob(ApprovalClient client, ApprovalLinkStore links, ApprovalOutcomes outcomes) {
        return new OpsKitReconcileJob(client, links, outcomes);
    }

    @Bean
    OpsKitProcessRegistrar opskitProcessRegistrar(ApprovalClient client, ApprovalSubjects subjects, OpsKitProperties props) {
        return new OpsKitProcessRegistrar(client, subjects, props);
    }
}
