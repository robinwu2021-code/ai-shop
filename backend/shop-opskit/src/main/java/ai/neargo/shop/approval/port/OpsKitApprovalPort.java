package ai.neargo.shop.approval.port;

import ai.neargo.shop.approval.opskit.ApprovalLinkStore;
import ai.neargo.shop.approval.opskit.ApprovalSubjects;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubmitRequested;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ApprovalPort} 的 OpsKit 实现：送审 = 同事务写 approval_link + 发件箱（事件号由关联键派生，重复送审幂等）。 */
public class OpsKitApprovalPort implements ApprovalPort {

    private final ApprovalSubjects subjects;
    private final ApprovalLinkStore links;
    private final OutboxEventBus outbox;

    public OpsKitApprovalPort(ApprovalSubjects subjects, ApprovalLinkStore links, OutboxEventBus outbox) {
        this.subjects = subjects;
        this.links = links;
        this.outbox = outbox;
    }

    @Override
    public boolean enabled(String subjectType) {
        return subjects.find(subjectType).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(String subjectType, String subjectRef, long revision, Submitter submitter) {
        var subject = subjects.find(subjectType)
                .orElseThrow(() -> new IllegalStateException("业务未启用审批：" + subjectType));
        if (links.insertIfAbsent(subjectType, subjectRef, revision, subject.processCode(), submitter.staffNo(), submitter.displayName())) {
            outbox.publish(eventNo(subjectType, subjectRef, revision), new ApprovalSubmitRequested(subjectType, subjectRef, revision));
        }
    }

    @Override
    public Optional<Link> latest(String subjectType, String subjectRef) {
        return links.latest(subjectType, subjectRef).map(ApprovalLinkStore.Row::toLink);
    }

    static String eventNo(String subjectType, String subjectRef, long revision) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((subjectType + ":" + subjectRef + ":" + revision).getBytes(StandardCharsets.UTF_8));
            return "APV" + HexFormat.of().formatHex(h).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
