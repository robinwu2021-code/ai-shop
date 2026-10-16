package ai.neargo.shop.pay.points.policy;

import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Command;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import java.util.ArrayList;
import java.util.List;

/** Missing source context fails closed if that scope has an OFF restriction. */
public final class SwitchPolicy {
    private SwitchPolicy() { }
    public record Key(String scope,String key) { }
    public static String scope(Command c) { return scope(c.scope(),c.flow()); }
    public static String scope(Scope scope,Flow flow) {
        return flow==Flow.BOTH?scope.name():flow.name()+"_"+scope.name();
    }
    public static String key(Scope scope,String type,String subject) {
        if (subject==null) { return null; }
        return switch (scope) {
            case GLOBAL -> "ALL";
            case REGION,TYPE -> subject;
            default -> PointsKeys.position(scope.name(),type,subject);
        };
    }
    public static List<Key> selectors(Availability c) {
        if (c.flow()==null || c.flow()==Flow.BOTH) { throw new IllegalArgumentException("POINTS_FLOW_REQUIRED"); }
        var keys=new ArrayList<Key>();
        for (Scope scope:Scope.values()) {
            String subject=switch (scope) {
                case GLOBAL -> "ALL";
                case REGION -> c.market();
                case TYPE -> c.typeCode();
                case SCENE -> c.scene();
                case CLIENT -> c.client();
                case MERCHANT -> c.merchantNo();
                case STORE -> c.storeNo();
                case USER -> c.userNo();
            };
            String key=key(scope,c.typeCode(),subject);
            keys.add(new Key(scope(scope,Flow.BOTH),key)); keys.add(new Key(scope(scope,c.flow()),key));
        }
        return List.copyOf(keys);
    }
    public static List<Key> expiryKeys(String type,String user,String market) {
        return selectors(new Availability(type,user,null,null,market,null,null,Flow.REDEEM)).stream()
                .filter(k -> k.key()!=null).toList();
    }
}
