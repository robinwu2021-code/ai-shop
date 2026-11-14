package ai.neargo.shop.spi.food;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Restaurant-owned inventory contract, deliberately distinct from legacy inventory providers. */
public interface FoodLocalStockPort {
    record Scope(String entityNo, String storeNo) {}
    record Command(String operationKey, String requestHash, String sourceSystem, String sourceNo,
                   long sourceVersion, long lifecycleVersion) {}
    record ItemRef(String itemNo, String baseUnit) {}
    record Leaf(String leafRef, String sourceComponentRef, String itemNo, String baseUnit,
                BigDecimal quantity, String phase, String definitionSnapshot, String definitionHash) {}
    record Plan(String planHash, LocalDateTime expiresAt, List<Leaf> leaves,
                String sourcePlanHash,int protocolVersion) {
        public Plan(String planHash,LocalDateTime expiresAt,List<Leaf> leaves) {
            this(planHash,expiresAt,leaves,null,1);
        }
        public Plan { leaves = List.copyOf(leaves); }
    }
    record Target(String leafRef, BigDecimal targetQty) {}
    record ReturnTarget(String leafRef, String originalMovementNo, BigDecimal targetQty,
                        String returnRef, String disposition,String reasonCode,String reasonDetail,String actorNo,
                        boolean requireUniformDisposition,boolean incremental) {
        public ReturnTarget(String leafRef,String originalMovementNo,BigDecimal targetQty,String returnRef,String disposition,
                            String reasonCode,String reasonDetail,String actorNo,boolean requireUniformDisposition) {
            this(leafRef,originalMovementNo,targetQty,returnRef,disposition,reasonCode,reasonDetail,actorNo,requireUniformDisposition,false);
        }
        public ReturnTarget(String leafRef,String originalMovementNo,java.math.BigDecimal targetQty,String returnRef,String disposition){
            this(leafRef,originalMovementNo,targetQty,returnRef,disposition,null,null,null,false);
        }
    }
    record Progress(String leafRef, String itemNo, String baseUnit, BigDecimal original,
                    BigDecimal issued, BigDecimal released, BigDecimal returned,String ledgerRef,String documentRef,String trackingMode) {
        public Progress(String leafRef,String itemNo,String baseUnit,BigDecimal original,BigDecimal issued,BigDecimal released,
                        BigDecimal returned,String ledgerRef,String documentRef) {
            this(leafRef,itemNo,baseUnit,original,issued,released,returned,ledgerRef,documentRef,null);
        }
        public Progress(String leafRef,String itemNo,String baseUnit,BigDecimal original,
                        BigDecimal issued,BigDecimal released,BigDecimal returned) {
            this(leafRef,itemNo,baseUnit,original,issued,released,returned,null,null);
        }
    }
    record Receipt(String commandNo, String reservationNo, String owner, String planHash,
                   String status, long lifecycleVersion, List<Progress> items, List<String> movementRefs) {
        public Receipt { items = List.copyOf(items); movementRefs = List.copyOf(movementRefs); }
    }
    record Availability(String itemNo, String baseUnit, String status, BigDecimal availableQty) {}
    record Lookup(String status, Receipt receipt) {}
    record ReservationView(String reservationNo,String sourceSystem,String sourceNo,long sourceVersion,
                           String planHash,String status,long lifecycleVersion,String sourcePlanHash,Integer protocolVersion,
                           LocalDateTime expiresAt,List<Progress> items) {
        public ReservationView {items=List.copyOf(items);}
    }
    ReservationView getReservation(Scope scope,String reservationNo);
    ReservationView findReservation(Scope scope,String sourceSystem,String sourceNo,long sourceVersion);
    List<Availability> findAvailability(Scope scope, List<ItemRef> items);
    Receipt reserve(Scope scope, Command command, Plan plan);
    Receipt confirm(Scope scope, Command command, String reservationNo);
    /** Atomic actual extra consumption owned by an authenticated original production event. */
    Receipt consumeSupplement(Scope scope, Command command, Plan plan);
    /** Durable withdrawal tombstone also rejects a late reserve that has not arrived yet. */
    Receipt withdraw(Scope scope, Command command, Plan originalPlan);
    /** Expire only a still-held reservation with its original elapsed expiry. */
    Receipt expire(Scope scope, Command command, Plan originalPlan);
    Receipt issueDelta(Scope scope, Command command, String reservationNo, List<Target> increments);
    Receipt issue(Scope scope, Command command, String reservationNo, List<Target> targets);
    Receipt release(Scope scope, Command command, String reservationNo, List<Target> targets);
    Receipt returnIssued(Scope scope, Command command, String reservationNo, List<ReturnTarget> targets);
    Lookup findCommand(Scope scope, String action, String operationKey, String requestHash);
}
