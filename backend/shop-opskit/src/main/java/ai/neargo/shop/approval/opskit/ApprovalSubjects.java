package ai.neargo.shop.approval.opskit;

import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 已切到审批的业务（容器里的 {@link ApprovalSubject} ∩ {@code shop.opskit.enabled-subjects}）。 */
public class ApprovalSubjects {

    private final Map<String, ApprovalSubject> bySubjectType = new LinkedHashMap<>();

    public ApprovalSubjects(List<ApprovalSubject> all, OpsKitProperties props) {
        for (ApprovalSubject s : all) {
            if (!s.subjectType().startsWith("shop.")) {
                throw new IllegalStateException("ApprovalSubject.subjectType 必须以 shop. 开头：" + s.subjectType());
            }
            if (bySubjectType.containsKey(s.subjectType())) {
                throw new IllegalStateException("重复的 ApprovalSubject：" + s.subjectType());
            }
            if (props.enabledSubjects().contains(s.subjectType())) {
                bySubjectType.put(s.subjectType(), s);
            }
        }
    }

    public Optional<ApprovalSubject> find(String subjectType) {
        return Optional.ofNullable(bySubjectType.get(subjectType));
    }

    public Iterable<ApprovalSubject> all() {
        return bySubjectType.values();
    }

    public static String actionOf(ApprovalSubject s) {
        return s.subjectType() + ".apply";
    }
}
