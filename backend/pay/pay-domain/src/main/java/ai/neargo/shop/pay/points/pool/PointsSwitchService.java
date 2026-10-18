package ai.neargo.shop.pay.points.pool;

/**
 * Audited availability changes stop new promises and retain their outage history for expiry
 * extension.
 */
public interface PointsSwitchService {
    void change(Command command);

    enum Scope {
        GLOBAL,
        REGION,
        TYPE,
        SCENE,
        CLIENT,
        MERCHANT,
        STORE,
        USER
    }

    enum Flow {
        BOTH,
        EARN,
        REDEEM
    }

    record Command(
            String eventNo,
            Scope scope,
            String typeCode,
            String subjectNo,
            boolean enabled,
            long at,
            String actor,
            String reason,
            Flow flow) {
        public Command {
            if (flow == null) {
                flow = Flow.BOTH;
            }
        }

        public Command(
                String eventNo,
                Scope scope,
                String typeCode,
                String subjectNo,
                boolean enabled,
                long at,
                String actor,
                String reason) {
            this(eventNo, scope, typeCode, subjectNo, enabled, at, actor, reason, Flow.BOTH);
        }
    }
}
