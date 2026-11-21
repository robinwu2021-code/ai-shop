package ai.neargo.shop.gateway.core.function.port;

import ai.neargo.shop.gateway.core.function.FunctionRegistry;
import ai.neargo.shop.spi.platform.ModuleAvailabilityPort;
import org.springframework.stereotype.Component;

/** 「模块装没装」由功能点登记表回答。 */
@Component
public class RegistryModuleAvailability implements ModuleAvailabilityPort {

    private final FunctionRegistry registry;

    public RegistryModuleAvailability(FunctionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean available(String moduleCode) {
        return moduleCode == null || moduleCode.isBlank() || registry.modules().contains(moduleCode);
    }
}
