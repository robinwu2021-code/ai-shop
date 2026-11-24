package ai.neargo.shop.merchant.service;

/** Employee PIN credentials and the business entity's lock-screen policy. */
public interface PosStaffPinService {
    record Scope(String tenant, String entity, String store, String subjectKind, String actor) {}
    record Status(boolean available, boolean configured, boolean enabled) {}
    enum Outcome { SAVED, MATCH, WRONG, LOCKED, NOT_SET, CURRENT_REQUIRED, WEAK, UNAVAILABLE, DISABLED }
    record Result(Outcome outcome, Integer remaining, Long lockedUntilMs) {
        public static Result of(Outcome outcome) { return new Result(outcome, null, null); }
    }

    Status status(Scope scope);
    boolean enabled(Scope scope);
    void setEnabled(Scope scope, boolean enabled);
    Result save(Scope scope, String newPin, String currentPin);
    Result verify(Scope scope, String pin);

    /** Called only after the caller consumes a scoped recovery grant in the same transaction. */
    Result reset(Scope scope, String newPin);

    static boolean weak(String pin) {
        return pin.equals("1234") || pin.equals("4321") || pin.chars().distinct().count() == 1;
    }
}
