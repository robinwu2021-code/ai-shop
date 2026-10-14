package ai.neargo.shop.pay.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayout;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PayoutMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SettleBatchMapper;
import ai.neargo.shop.spi.user.PayoutAccountPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link PayoutService} 实现。所有读写都绕数据域：放款跑在运营会话与无会话任务里 */
@Service
public class PayoutServiceImpl implements PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutServiceImpl.class);
    private static final String DEFAULT_CURRENCY = "CNY";

    private final BillMapper billMapper;
    private final SettleBatchMapper batchMapper;
    private final PayoutMapper payoutMapper;
    private final PayoutAccountPort payoutAccounts;
    private final PointsService pointsService;

    public PayoutServiceImpl(BillMapper billMapper, SettleBatchMapper batchMapper, PayoutMapper payoutMapper,
                             PayoutAccountPort payoutAccounts, PointsService pointsService) {
        this.billMapper = billMapper;
        this.batchMapper = batchMapper;
        this.payoutMapper = payoutMapper;
        this.payoutAccounts = payoutAccounts;
        this.pointsService = pointsService;
    }

    @Override
    @Transactional("payTxManager")
    public List<PayoutVO> releaseBatch(String batchNo, String operator) {
        StlSettleBatch batch = requireBatch(batchNo);
        if (!StlSettleBatch.RECONCILED.equals(batch.getStatus())) {
            /*
             * 只有自查全过的批次能放。RELEASED 再放一次是重复打款，
             * BLOCKED 放是绕过挂起 —— 两者都不该是 CONFLICT 这种看不出原因的码。
             */
            throw BizException.of(ErrorCode.BATCH_NOT_RELEASABLE, batch.getStatus());
        }
        List<StlBill> bills = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getBatchNo, batchNo)));
        List<StlBill> selfOperated = bills.stream()
                .filter(b -> StlBill.BIZ_SELF_OPERATED.equals(b.getBusinessMode()))
                .toList();

        List<PayoutVO> out = new ArrayList<>();
        if (!selfOperated.isEmpty()) {
            /*
             * 闸 1 · 票到付款。**逐张检查、报出全部不合格的单号**：
             * 只报第一张的话，运营核完一张再点一次又被下一张挡，一批 20 单要点 20 次。
             */
            List<String> noInvoice = selfOperated.stream()
                    .filter(b -> !StlBill.INV_VERIFIED.equals(b.getInvoiceStatus())
                            && !StlBill.INV_NONE.equals(b.getInvoiceStatus()))
                    .map(StlBill::getSettleNo).toList();
            if (!noInvoice.isEmpty()) {
                throw BizException.of(ErrorCode.PAYOUT_INVOICE_PENDING, String.join(" ", noInvoice));
            }
            // 闸 2 · 收款账户。activeAccount 只返回 ACTIVE 的 —— 未审核的账户在那里就被挡了
            var account = payoutAccounts.activeAccount(batch.getEntityNo())
                    .orElseThrow(() -> BizException.of(ErrorCode.PAYOUT_ACCOUNT_MISSING, batch.getEntityNo()));

            /*
             * 按收款号分组，一组一笔。自营一主体一账户时 payMerchantNo 多为空，
             * 那就是一笔 —— 但分组逻辑照写：它是 TDD 定的粒度，不因今天只有一组而省掉。
             */
            Map<String, List<StlBill>> groups = new LinkedHashMap<>();
            for (StlBill b : selfOperated) {
                groups.computeIfAbsent(b.getPayMerchantNo() == null ? "" : b.getPayMerchantNo(),
                        k -> new ArrayList<>()).add(b);
            }
            long now = System.currentTimeMillis();
            for (var e : groups.entrySet()) {
                StlPayout p = new StlPayout();
                p.setPayoutNo(BizKey.next(BizKey.PAYOUT));
                p.setBatchNo(batchNo);
                p.setEntityNo(batch.getEntityNo());
                p.setPayMerchantNo(e.getKey().isEmpty() ? null : e.getKey());
                p.setAccountName(account.accountName());
                p.setBankName(account.bankName());
                p.setBankBranch(account.bankBranch());
                p.setAccountNoMasked(account.accountMasked());
                p.setAmountMinor(e.getValue().stream().mapToLong(b -> nz(b.getNetMinor())).sum());
                p.setBillCount(e.getValue().size());
                p.setCurrency(batch.getCurrency() == null || batch.getCurrency().isBlank()
                        ? DEFAULT_CURRENCY : batch.getCurrency());
                p.setStatus(StlPayout.PENDING);
                p.setChannel(StlPayout.CHANNEL_MANUAL);
                p.setTenantNo("MAIN");
                p.setCreatedAt(LocalDateTime.now());
                p.setUpdatedAt(LocalDateTime.now());
                p.setCreatedBy(operator);
                DataScopeContext.executeWithoutScope(() -> payoutMapper.insert(p));
                for (StlBill b : e.getValue()) {
                    StlBill patch = new StlBill();
                    patch.setId(b.getId());
                    patch.setPayoutNo(p.getPayoutNo());
                    // 「双方认这个数」= 批次自查过了。CONFIRMED 仍是它在应付链上的含义
                    patch.setStatus(StlBill.CONFIRMED);
                    DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));
                }
                out.add(toVO(p, e.getValue().stream().map(StlBill::getSettleNo).toList()));
                log.warn("[payout] 批次 {} 生成放款 {}：{} 单 · {} 分 → {}（{}）",
                        batchNo, p.getPayoutNo(), p.getBillCount(), p.getAmountMinor(),
                        p.getAccountNoMasked(), operator);
            }
            batch.setReleasedAt(now);
        } else {
            batch.setReleasedAt(System.currentTimeMillis());
            log.info("[payout] 批次 {} 全是第三方单，置 RELEASED、不生成放款记录（分账轨另接）", batchNo);
        }
        StlSettleBatch bp = new StlSettleBatch();
        bp.setId(batch.getId());
        bp.setStatus(StlSettleBatch.RELEASED);
        bp.setReleasedAt(batch.getReleasedAt());
        bp.setDecidedBy(operator);
        DataScopeContext.executeWithoutScope(() -> batchMapper.updateById(bp));
        return out;
    }

    @Override
    public List<PayoutVO> list(String status, String entityNo) {
        boolean byStatus = status != null && !status.isBlank();
        boolean byEntity = entityNo != null && !entityNo.isBlank();
        // 不绕过：运营端全量队列，配了商家域的财务不该看到别家的放款
        List<StlPayout> rows = payoutMapper.selectList(Wrappers.<StlPayout>lambdaQuery()
                .eq(byStatus, StlPayout::getStatus, status)
                .eq(byEntity, StlPayout::getEntityNo, entityNo)
                .orderByDesc(StlPayout::getId));
        return rows.stream().map(p -> toVO(p, settleNosOf(p.getPayoutNo()))).toList();
    }

    @Override
    @Transactional("payTxManager")
    public int markExported(Collection<String> payoutNos, String operator) {
        if (payoutNos == null || payoutNos.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String no : payoutNos) {
            StlPayout p = requirePayout(no);
            if (!StlPayout.PENDING.equals(p.getStatus())) {
                continue;   // 幂等：已导出 / 已付的不动
            }
            StlPayout patch = new StlPayout();
            patch.setId(p.getId());
            patch.setStatus(StlPayout.EXPORTED);
            patch.setExportedAt(System.currentTimeMillis());
            patch.setUpdatedBy(operator);
            // 不绕数据域：它从 GET 付款清单里被调到，运营看不到的放款也不该被它翻成已导出
            payoutMapper.updateById(patch);
            n++;
        }
        return n;
    }

    @Override
    @Transactional("payTxManager")
    public PayoutVO markPaid(String payoutNo, String paymentRef, String operator) {
        if (paymentRef == null || paymentRef.isBlank()) {
            // 没有凭证号的「已付」等于没记：事后对不上银行流水，也说不清是谁付的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StlPayout p = requirePayout(payoutNo);
        if (StlPayout.PAID.equals(p.getStatus()) || StlPayout.MATCHED.equals(p.getStatus())) {
            return toVO(p, settleNosOf(payoutNo));   // 幂等
        }
        if (!StlPayout.PENDING.equals(p.getStatus()) && !StlPayout.EXPORTED.equals(p.getStatus())) {
            throw BizException.of(ErrorCode.PAYOUT_NOT_PAYABLE, p.getStatus());
        }
        long now = System.currentTimeMillis();
        p.setStatus(StlPayout.PAID);
        p.setPaymentRef(paymentRef.trim());
        p.setPaidAt(now);
        p.setPaidBy(operator);
        p.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> payoutMapper.updateById(p));
        /*
         * 结算单跟着 PAID，**带同一个凭证号**。此前凭证是逐张回填的，
         * 现在一笔放款一个号，结算单上的 payment_ref 只是镜像 —— 存量那条老路的读者还在读它。
         */
        for (StlBill b : billsOf(payoutNo)) {
            StlBill patch = new StlBill();
            patch.setId(b.getId());
            patch.setStatus(StlBill.PAID);
            patch.setPaymentRef(paymentRef.trim());
            patch.setPaidAt(now);
            DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));
            // 归集路径的积分兑付时点在这里（与老 markPaid 同一条规矩）
            pointsService.confirmDeduction(b.getSubOrderNo());
        }
        log.warn("[payout] {} 登记付款：凭证 {} · {} 分（{}）", payoutNo, paymentRef, p.getAmountMinor(), operator);
        return toVO(p, settleNosOf(payoutNo));
    }

    @Override
    @Transactional("payTxManager")
    public PayoutVO markFailed(String payoutNo, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        StlPayout p = requirePayout(payoutNo);
        if (StlPayout.FAILED.equals(p.getStatus())) {
            return toVO(p, List.of());
        }
        if (StlPayout.MATCHED.equals(p.getStatus())) {
            // 银行流水都勾上了还说失败，是对账轴与人工说法相反 —— 先去处理差异，不在这里改
            throw BizException.of(ErrorCode.PAYOUT_NOT_PAYABLE, p.getStatus());
        }
        p.setStatus(StlPayout.FAILED);
        p.setFailReason(reason.trim());
        p.setUpdatedBy(operator);
        DataScopeContext.executeWithoutScope(() -> payoutMapper.updateById(p));
        /*
         * **钱没出去就得让它能再放一次**：结算单回待对账、摘掉放款号与凭证；批次回可放款。
         * 不回退的话这一批永远「已放款」，而商家一分钱没收到、运营也找不到再放的入口。
         */
        for (StlBill b : billsOf(payoutNo)) {
            StlBill patch = new StlBill();
            patch.setId(b.getId());
            patch.setStatus(StlBill.PENDING_RECON);
            // updateById 跳过 null：清空要用 set(null) 的 update 语句
            DataScopeContext.executeWithoutScope(() -> billMapper.update(patch,
                    Wrappers.<StlBill>lambdaUpdate().eq(StlBill::getId, b.getId())
                            .set(StlBill::getPayoutNo, null)
                            .set(StlBill::getPaymentRef, null)
                            .set(StlBill::getPaidAt, null)));
        }
        StlSettleBatch batch = requireBatch(p.getBatchNo());
        StlSettleBatch bp = new StlSettleBatch();
        bp.setId(batch.getId());
        bp.setStatus(StlSettleBatch.RECONCILED);
        bp.setDecideRemark("放款 " + payoutNo + " 退回：" + reason.trim());
        DataScopeContext.executeWithoutScope(() -> batchMapper.update(bp,
                Wrappers.<StlSettleBatch>lambdaUpdate().eq(StlSettleBatch::getId, batch.getId())
                        .set(StlSettleBatch::getReleasedAt, null)));
        log.warn("[payout] {} 退回：{}（{}）—— 批次 {} 回到可放款", payoutNo, reason, operator, p.getBatchNo());
        return toVO(p, List.of());
    }

    private StlSettleBatch requireBatch(String batchNo) {
        StlSettleBatch b = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getBatchNo, batchNo).last("LIMIT 1")));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return b;
    }

    /**
     * **不绕数据域**：配了商家域的财务拿着别家的放款单号来登记，该是 NOT_FOUND，
     * 而不是登记成功。读放款记录的三条路（列表 / 登记 / 退回）都走这里。
     */
    private StlPayout requirePayout(String payoutNo) {
        StlPayout p = payoutMapper.selectOne(Wrappers.<StlPayout>lambdaQuery()
                .eq(StlPayout::getPayoutNo, payoutNo).last("LIMIT 1"));
        if (p == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return p;
    }

    /** 读本笔下的结算单。不绕数据域 —— 它出现在 GET 列表里；运营看不到的放款，单也看不到 */
    private List<StlBill> billsOf(String payoutNo) {
        return billMapper.selectList(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getPayoutNo, payoutNo));
    }

    private List<String> settleNosOf(String payoutNo) {
        return billsOf(payoutNo).stream().map(StlBill::getSettleNo).toList();
    }

    private static PayoutVO toVO(StlPayout p, List<String> settleNos) {
        return new PayoutVO(p.getPayoutNo(), p.getBatchNo(), p.getEntityNo(), p.getPayMerchantNo(),
                p.getAccountName(), p.getBankName(), p.getBankBranch(), p.getAccountNoMasked(),
                nz(p.getAmountMinor()), p.getBillCount() == null ? 0 : p.getBillCount(),
                p.getCurrency(), p.getStatus(), p.getChannel(), p.getPaymentRef(), p.getBankFlowNo(),
                p.getExportedAt(), p.getPaidAt(), p.getPaidBy(), p.getMatchedAt(), p.getFailReason(),
                settleNos);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
