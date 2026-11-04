package ai.neargo.commerce.api.common;

public record ActorContext(String tenantNo, SubjectKind subjectKind, String principalNo,
        String accountSessionFingerprint, RawPrincipalIdentity rawIdentity) {
    public ActorContext(String tenantNo, SubjectKind subjectKind, String principalNo, String accountSessionFingerprint) {
        this(tenantNo,subjectKind,principalNo,accountSessionFingerprint,null);
    }
}
