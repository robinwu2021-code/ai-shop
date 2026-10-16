package ai.neargo.shop.pay.points.lifecycle;

/** Audited account freeze preserves promises and stops the inactivity clock. */
public interface PointsAccountControlService {
    void freeze(Command command);
    record Command(String eventNo,String typeCode,String userNo,boolean frozen,long at,String actor,String reason) { }
}
