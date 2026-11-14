package ai.neargo.shop.merchant.organization;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.organization.OperatingEntityModels.*;
import java.util.List;

public interface OperatingEntityService {
    Context context(Actor actor);
    List<View> list(Actor actor);
    View get(Actor actor, String no);
    View result(Actor actor, String key);
    List<LegalSubject> legalSubjects(Actor actor);
    View create(Actor actor, Create command, String key);
}
