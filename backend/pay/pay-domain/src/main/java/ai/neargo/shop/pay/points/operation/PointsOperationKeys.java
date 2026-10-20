package ai.neargo.shop.pay.points.operation;
import ai.neargo.shop.spi.points.PointsOperationsPort.Operation;
import ai.neargo.shop.pay.points.policy.PointsDigests;
/** Length-prefixed identities avoid ambiguous delimiter concatenation. */
public final class PointsOperationKeys {
    private PointsOperationKeys() {
    }
    public static String key(Operation op) {
        if(op==null||op.source()==null||!valid(op.typeCode())||!valid(op.businessNo())||!valid(op.operationNo()))throw new IllegalArgumentException("POINTS_OPERATION_INPUT_INVALID");
        return "V2:"+PointsDigests.sha256(part(op.source().name())+part(op.typeCode())+part(op.businessNo())+part(op.operationNo()));
    }
    public static String grantRef(String type, String subOrder, String operationKey) {
        return operationKey==null?"GRANT:"+subOrder:"GRANT:V2:"+ai.neargo.shop.pay.points.model.PointsKeys.position("G", type, subOrder);
    }
    public static String grantPosition(String type, String ref) {
        return ref.startsWith("GRANT:V2:")?ref.substring(9):ai.neargo.shop.pay.points.model.PointsKeys.position("G", type, ref.substring(6));
    }
    public static String holdRoot(String order, String type, String key) {
        return key==null?ai.neargo.shop.pay.points.model.PointsKeys.position("H", "ORDER", order):ai.neargo.shop.pay.points.model.PointsKeys.position("H2", type, key);
    }
    public static String reference(String prefix, String order, String type, String key) {
        return prefix+":"+(key==null?order:holdRoot(order, type, key));
    }
    public static String part(String text) {
        return text.length()+":"+text;
    }
    private static boolean valid(String text) {
        return text!=null&&!text.isBlank()&&text.length()<=64;
    }
}
