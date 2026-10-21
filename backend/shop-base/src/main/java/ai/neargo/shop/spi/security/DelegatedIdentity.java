package ai.neargo.shop.spi.security;
/** Token-verified current identity namespace; no roles, permission or financial authority is granted. */
public record DelegatedIdentity(String tenantNo, String realm, String subjectKind, String actorNo) {
    public DelegatedIdentity {
        if (!"MAIN".equals(tenantNo) || actorNo == null || actorNo.isBlank() || actorNo.length() > 64
                || !("CONSUMER".equals(realm) && "USR".equals(subjectKind)
                || "MERCHANT".equals(realm) && ("MCH".equals(subjectKind) || "USR".equals(subjectKind)))) {
            throw new IllegalArgumentException("valid current delegated identity required");
        }
    }
}
