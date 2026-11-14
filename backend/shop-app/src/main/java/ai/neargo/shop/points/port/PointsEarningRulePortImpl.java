package ai.neargo.shop.points.port;

import ai.neargo.shop.points.earning.PointsEarningRuleService;
import ai.neargo.shop.spi.points.PointsEarningRulePort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Keeps cross-domain earning policy calls on the existing transactional business service. */
@Component
public class PointsEarningRulePortImpl implements PointsEarningRulePort {
    private final PointsEarningRuleService service;

    public PointsEarningRulePortImpl(PointsEarningRuleService service) {
        this.service = service;
    }

    @Override
    public List<Type> availableTypes() {
        return service.availableTypes();
    }

    @Override
    public List<PublishedRule> published(String typeCode) {
        return service.published(typeCode);
    }

    @Override
    public Calculation calculate(Query query) {
        return service.calculate(query);
    }
}
