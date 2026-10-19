package ai.neargo.shop.payclient;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.security.access.AccessDeniedException;

import java.util.List;

/** Public operations boundary; ledger scope bypass is never an authorization decision. */
public final class PointsOpsAccess {
    private PointsOpsAccess() {}

    public static void metadata() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR || user.dataScope() == null)
            throw new AccessDeniedException("Points operator required");
    }

    public static void platform() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR || user.dataScope() == null || !user.dataScope().all())
            throw new AccessDeniedException("Points platform scope required");
    }

    public static void store(StoreDirectoryPort stores, String storeNo) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR || user.dataScope() == null)
            throw new AccessDeniedException("Points store scope required");
        if (user.dataScope().all()) return;
        if (stores == null || storeNo == null || storeNo.isBlank())
            throw new AccessDeniedException("Points store scope required");
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card != null
                && user.dataScope().rules() != null
                && user.dataScope().rules().stream()
                        .anyMatch(
                                r ->
                                        ScopeDim.MERCHANT.equals(r.dim())
                                                && r.refs() != null
                                                && r.refs().contains(card.entityNo()))) return;
        throw new AccessDeniedException("Points store outside authorized scope");
    }
}
