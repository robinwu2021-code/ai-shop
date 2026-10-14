package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.spi.product.InvManagedPort;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/** 记不记库存的跨域出口。<b>只转发</b>，判据在 {@link InvManagedService} 一处 */
@Component
public class InvManagedPortImpl implements InvManagedPort {

    private final InvManagedService invManaged;

    public InvManagedPortImpl(InvManagedService invManaged) {
        this.invManaged = invManaged;
    }

    @Override
    public Set<String> managedSkus(Collection<String> skuNos) {
        return invManaged.managedSkus(skuNos);
    }
}
