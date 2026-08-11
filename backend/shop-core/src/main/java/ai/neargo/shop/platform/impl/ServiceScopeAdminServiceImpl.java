package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.ServiceScopeAdminService;
import ai.neargo.shop.platform.ServiceScopeService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** {@link ServiceScopeAdminService} 实现。**叶子 bean** —— 没有任何 Service 依赖它。 */
@Service
public class ServiceScopeAdminServiceImpl implements ServiceScopeAdminService {

    private final ServiceScopeService scopeService;
    private final MerchantQueryPort merchantQuery;

    public ServiceScopeAdminServiceImpl(ServiceScopeService scopeService,
                                        MerchantQueryPort merchantQuery) {
        this.scopeService = scopeService;
        this.merchantQuery = merchantQuery;
    }

    @Override
    public List<ServiceScopeVO> list() {
        Set<String> enabled = scopeService.enabledScopes();
        List<ServiceScopeVO> out = new ArrayList<>();
        for (String scope : ServiceScopeServiceImpl.ORDER) {
            out.add(new ServiceScopeVO(scope, enabled.contains(scope),
                    merchantQuery.countByServiceScope(scope)));
        }
        return out;
    }

    @Override
    public List<ServiceScopeVO> setEnabled(String scope, boolean enabled, String reason) {
        scopeService.setEnabled(scope, enabled, reason);
        return list();
    }
}
