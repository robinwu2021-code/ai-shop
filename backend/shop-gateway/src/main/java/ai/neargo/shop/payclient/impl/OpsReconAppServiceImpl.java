package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.payclient.OpsReconAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OpsReconAppServiceImpl implements OpsReconAppService {

    private final ReconService reconService;
    private final AuditLogPort auditLogPort;

    public OpsReconAppServiceImpl(ReconService reconService, AuditLogPort auditLogPort) {
        this.reconService = reconService;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public PageData<ReconService.ReconDiffVO> diffs(String status, long page, long size) {
        return PageData.ofAll(reconService.diffs(status), page, size);
    }

    @Override
    public List<ReconService.AxisReport> axes() {
        return reconService.scanAllAxes(System.currentTimeMillis());
    }

    @Override
    public ReconService.Coverage coverage() {
        return reconService.coverage();
    }

    @Override
    public ReconService.ReconDiffVO resolve(String diffNo, String resolution) {
        String operator = SecurityUtils.currentUserNo();
        var vo = reconService.decide(diffNo, false, resolution, operator);
        // 钱的事必须能追到是谁在什么时候下的结论
        auditLogPort.record("RECON_RESOLVE", diffNo, resolution);
        return vo;
    }

    @Override
    public ReconService.ReconDiffVO ignore(String diffNo, String resolution) {
        String operator = SecurityUtils.currentUserNo();
        var vo = reconService.decide(diffNo, true, resolution, operator);
        auditLogPort.record("RECON_IGNORE", diffNo, resolution);
        return vo;
    }
}
