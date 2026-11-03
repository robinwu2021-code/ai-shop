package ai.neargo.commerce.api.common;


public record ActorContext(String tenantNo, SubjectKind subjectKind, String principalNo, String accountSessionFingerprint) {}
