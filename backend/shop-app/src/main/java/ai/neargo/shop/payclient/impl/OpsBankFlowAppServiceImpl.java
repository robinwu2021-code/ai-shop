package ai.neargo.shop.payclient.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.payclient.OpsBankFlowAppService;
import ai.neargo.shop.pay.entity.StlBankFlow;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.recon.BankFlowCsvParser;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.auth.SecurityUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 不限 profile：与同域的 OpsPayoutList/OpsSettleStats 一致 —— 只有 Controller 挂 @Profile("ops")，
// 服务层跟着限的话，场景测试就得多带一个 profile，而那会多出一个 Spring context（种子重放撞主键）
@Service
public class OpsBankFlowAppServiceImpl implements OpsBankFlowAppService {

    private final SettleMappers.BankFlowMapper flows;
    private final AuditLogPort auditLogPort;

    public OpsBankFlowAppServiceImpl(SettleMappers.BankFlowMapper flows, AuditLogPort auditLogPort) {
        this.flows = flows;
        this.auditLogPort = auditLogPort;
    }

    @Override
    @Transactional
    public ImportResultVO importCsv(String fileName, String csv) {
        BankFlowCsvParser.ParseResult parsed = BankFlowCsvParser.parse(csv);

        List<FailureVO> failures = new ArrayList<>();
        for (BankFlowCsvParser.Failure f : parsed.failures()) {
            failures.add(new FailureVO(f.line(), f.reason()));
        }

        /*
         * **判重查库，不靠唯一键抛异常。**
         * 靠异常判重会让整个事务回滚，前面成功入库的行一起没 ——
         * 而重复上传恰恰是常态，不该是「出错」路径。
         */
        List<String> nos = parsed.rows().stream().map(StlBankFlow::getFlowNo).toList();
        Set<String> existing = new HashSet<>();
        if (!nos.isEmpty()) {
            List<StlBankFlow> found = DataScopeContext.executeWithoutScope(() ->
                    flows.selectList(Wrappers.<StlBankFlow>lambdaQuery()
                            .select(StlBankFlow::getFlowNo)
                            .in(StlBankFlow::getFlowNo, nos)));
            for (StlBankFlow f : found) {
                existing.add(f.getFlowNo());
            }
        }

        // 未登录取不到就留空：这一列是留痕，不该成为导入失败的理由
        String operator = SecurityUtils.currentUserNoOrNull();
        long now = System.currentTimeMillis();
        int imported = 0;
        int skipped = 0;
        // 同一份文件里出现两次同号：第一条入库，第二条按已存在跳过
        Set<String> seen = new HashSet<>(existing);
        for (StlBankFlow row : parsed.rows()) {
            if (!seen.add(row.getFlowNo())) {
                skipped++;
                continue;
            }
            row.setImportedBy(operator);
            row.setImportedAt(now);
            row.setTenantNo("MAIN");
            DataScopeContext.executeWithoutScope(() -> flows.insert(row));
            imported++;
        }
        int total = imported + skipped + failures.size();
        /*
         * **留痕与 importedBy 写在同一处。** 银行流水是对账的判据，判据从哪来必须记下来。
         * 这段原本在 Controller 里，靠返回值再读一遍三个计数 ——
         * 而「取了操作人就必须留痕」那道闸扫的是取操作人的这一层，它是对的：
         * 分两处写，日后有人改了这里的计数口径，审计里那句话会悄悄变成假的。
         *
         * 审计里**不记流水内容**：对方户名与账号进审计与进日志是同一类泄露。
         */
        auditLogPort.record("BANK_FLOW_IMPORT",
                fileName == null || fileName.isBlank() ? "(未命名)" : fileName,
                "导入银行流水：入库 %d 条 · 已存在跳过 %d 条 · 解析失败 %d 条"
                        .formatted(imported, skipped, failures.size()),
                true);
        return new ImportResultVO(total, imported, skipped, failures.size(), failures);
    }
}
