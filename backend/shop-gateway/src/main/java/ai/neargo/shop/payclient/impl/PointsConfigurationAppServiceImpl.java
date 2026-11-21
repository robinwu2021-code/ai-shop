package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.AgreementPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.ChangeResult;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Create;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackRule;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Terms;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TermsChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeRename;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.payclient.PointsConfigurationAppService;
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PointsConfigurationAppServiceImpl implements PointsConfigurationAppService {
    @Autowired(required = false)
    private StoreDirectoryPort stores;

    private final PointsAdministrationService service;

    private final AuditLogPort audit;
    private final ObjectMapper json = new ObjectMapper();

    public PointsConfigurationAppServiceImpl(PointsAdministrationService service, AuditLogPort audit) {
        this.service = service;
        this.audit = audit;
    }

    private ChangeResult logged(Object request, Supplier<ChangeResult> work) {
        var result = work.get();
        String before = null;
        String after = json.writeValueAsString(request);
        if (request instanceof TypeRename rename) {
            before = json.writeValueAsString(Map.of("name", rename.expectedName()));
            after = json.writeValueAsString(Map.of("name", rename.name().trim()));
        }
        audit.record("POINTS_" + result.action(), result.typeCode(),
                "积分配置操作 " + result.action() + "，操作号：" + result.requestId(),
                !(request instanceof TypeRename), before, after);
        return result;
    }

    @Override
    public List<PointType> types() {
        PointsOpsAccess.metadata();
        return PointsOpsErrors.call(() -> service.types());
    }

    @Override
    public ChangeResult create(Create r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.create(r, actor)));
    }

    @Override
    public ChangeResult transition(TypeChange r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.transition(r, actor)));
    }

    @Override
    public ChangeResult rename(TypeRename r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.rename(r, actor)));
    }

    @Override
    public FallbackRule fallback(String type) {
        PointsOpsAccess.metadata();
        return PointsOpsErrors.call(() -> service.fallback(type));
    }

    @Override
    public ChangeResult saveFallback(FallbackChange r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.saveFallback(r, actor)));
    }

    @Override
    public SwitchPage switches(String type, long after, int limit) {
        PointsOpsAccess.platform();
        return PointsOpsErrors.call(() -> service.switches(type, after, limit));
    }

    @Override
    public ChangeResult changeSwitch(SwitchChange r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.changeSwitch(r, actor)));
    }

    @Override
    public Terms terms(String type) {
        PointsOpsAccess.metadata();
        return PointsOpsErrors.call(() -> service.terms(type));
    }

    @Override
    public ChangeResult publishTerms(TermsChange r, String actor) {
        PointsOpsAccess.platform();
        return logged(r, () -> PointsOpsErrors.call(() -> service.publishTerms(r, actor)));
    }

    @Override
    public AgreementPage agreements(String type, String store, long after, int limit) {
        PointsOpsAccess.store(stores, store);
        return PointsOpsErrors.call(() -> service.agreements(type, store, after, limit));
    }
}
