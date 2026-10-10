package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.dto.SettleBillVO;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.payclient.OpsPayoutListAppService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.PayoutAccountPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link OpsPayoutListAppService} 实现。 */
@Service
public class OpsPayoutListAppServiceImpl implements OpsPayoutListAppService {

    private final SettleService settleService;
    private final PayoutAccountPort payoutAccounts;
    private final MerchantQueryPort merchantPort;
    private final ai.neargo.shop.pay.PayoutService payoutService;

    public OpsPayoutListAppServiceImpl(SettleService settleService,
                                       PayoutAccountPort payoutAccounts,
                                       MerchantQueryPort merchantPort,
                                       ai.neargo.shop.pay.PayoutService payoutService) {
        this.settleService = settleService;
        this.payoutAccounts = payoutAccounts;
        this.merchantPort = merchantPort;
        this.payoutService = payoutService;
    }

    @Override
    public PayoutListVO list(String entityNo) {
        /*
         * **取全部自营应付，而不是只取 CONFIRMED。**
         *
         * 只取能付的那批，「这一期怎么少了一家」就答不出来 ——
         * 而那正是财务第一个会问的问题。下面按原因分流，
         * 付不了的也要带着原因出现在结果里。
         */
        List<PayoutRow> rows = new ArrayList<>();
        List<BlockedRow> blocked = new ArrayList<>();
        long total = 0L;

        /*
         * **先出放款记录（V391）。** 三道闸（对账、票、账户）在放款那一步已经过了，
         * 这里只是把它们装进清单；账号明文仍只在这一刻解一次。
         * 导出即置 EXPORTED —— 财务拿着去网银了，再导一次该看得出「已经导过」。
         */
        List<ai.neargo.shop.pay.PayoutService.PayoutVO> payouts = payoutService.list(null, entityNo).stream()
                .filter(p -> ai.neargo.shop.pay.entity.StlPayout.PENDING.equals(p.status())
                        || ai.neargo.shop.pay.entity.StlPayout.EXPORTED.equals(p.status()))
                .toList();
        if (!payouts.isEmpty()) {
            Map<String, MerchantQueryPort.MerchantBrief> pb = merchantPort.findAll(
                    payouts.stream().map(ai.neargo.shop.pay.PayoutService.PayoutVO::entityNo)
                            .collect(Collectors.toSet()));
            for (var p : payouts) {
                var brief = pb.get(p.entityNo());
                var account = payoutAccounts.activeAccount(p.entityNo());
                if (account.isEmpty()) {
                    // 放款之后账户被停了：不能付、要说出来，而不是悄悄少一行
                    blocked.add(new BlockedRow(p.entityNo(), brief == null ? p.entityNo() : brief.merchantName(),
                            p.amountMinor(), p.billCount(), "放款后收款账户已失效，先处理账户再导"));
                    continue;
                }
                var acc = account.get();
                total += p.amountMinor();
                rows.add(new PayoutRow(p.entityNo(), brief == null ? p.entityNo() : brief.merchantName(),
                        acc.accountType(), acc.accountName(),
                        payoutAccounts.decryptAccountNumber(acc.accountNo()),
                        acc.bankName(), acc.bankBranch(), p.amountMinor(), p.billCount(),
                        // 附言带放款单号：银行回单上这个号能直接勾到记录
                        "货款-" + p.entityNo() + "-" + p.payoutNo(), p.settleNos(), p.payoutNo()));
            }
            payoutService.markExported(rows.stream().map(PayoutRow::payoutNo)
                    .filter(java.util.Objects::nonNull).toList(), null);
        }

        /*
         * **再出存量老路**：没入批、没放款记录的自营单（起始日之前成交的那些），
         * 仍按逐张 confirm / paid 走。两条路并存到存量清零，见 TDD-账期推进与放款记录 §2.6。
         */
        List<SettleBillVO> all = settleService.opsPayables(null, entityNo);

        // 已付的不再出现：它既不该重复付，也不属于「本该付而没付」；已入批的走放款记录，不在这儿重复出现
        Map<String, List<SettleBillVO>> byEntity = all.stream()
                .filter(b -> !StlBill.PAID.equals(b.status()))
                .filter(b -> b.batchNo() == null || b.batchNo().isBlank())
                .collect(Collectors.groupingBy(SettleBillVO::merchantNo,
                        LinkedHashMap::new, Collectors.toList()));
        if (byEntity.isEmpty()) {
            return new PayoutListVO(rows, blocked, total);
        }

        Map<String, MerchantQueryPort.MerchantBrief> briefs =
                merchantPort.findAll(Set.copyOf(byEntity.keySet()));


        for (var e : byEntity.entrySet()) {
            String ent = e.getKey();
            List<SettleBillVO> bills = e.getValue();
            var brief = briefs.get(ent);
            String name = brief == null ? ent : brief.merchantName();

            // ① 未对账的 —— 双方还没认这个数，付了就是按一个没认的数付
            List<SettleBillVO> notConfirmed = bills.stream()
                    .filter(b -> !StlBill.CONFIRMED.equals(b.status())).toList();
            if (!notConfirmed.isEmpty()) {
                blocked.add(row(ent, name, notConfirmed, "还没确认对账"));
            }

            List<SettleBillVO> confirmed = bills.stream()
                    .filter(b -> StlBill.CONFIRMED.equals(b.status())).toList();
            if (confirmed.isEmpty()) {
                continue;
            }

            // ② 票没了结的 —— 票到付款是硬规则：没票这笔支出在税上不存在
            List<SettleBillVO> noInvoice = confirmed.stream()
                    .filter(b -> !StlBill.INV_VERIFIED.equals(b.invoiceStatus())
                            && !StlBill.INV_NONE.equals(b.invoiceStatus())).toList();
            if (!noInvoice.isEmpty()) {
                blocked.add(row(ent, name, noInvoice, "进项票还没核验，也没标无票供应商"));
            }

            List<SettleBillVO> payable = confirmed.stream()
                    .filter(b -> StlBill.INV_VERIFIED.equals(b.invoiceStatus())
                            || StlBill.INV_NONE.equals(b.invoiceStatus())).toList();
            if (payable.isEmpty()) {
                continue;
            }

            /*
             * ③ 账户闸。**未审核的账户不进清单**，这条闸落在 activeAccount 里
             * （它只返回 ACTIVE），而不是在这里判状态 —— 将来多一个调用方也不会漏。
             */
            var account = payoutAccounts.activeAccount(ent);
            if (account.isEmpty()) {
                blocked.add(row(ent, name, payable, "没有生效中的收款账户，钱不知道打给谁"));
                continue;
            }
            var acc = account.get();

            long amount = payable.stream().mapToLong(SettleBillVO::netMinor).sum();
            total += amount;
            rows.add(new PayoutRow(ent, name, acc.accountType(), acc.accountName(),
                    // **唯一一处解密**。调用方把它写进导出文件后即丢弃
                    payoutAccounts.decryptAccountNumber(acc.accountNo()),
                    acc.bankName(), acc.bankBranch(), amount, payable.size(),
                    // 银行附言要能回勾：给的是主体号不是商家名 —— 名字会改，号不会
                    "货款-" + ent,
                    payable.stream().map(SettleBillVO::settleNo).toList(), null));
        }
        return new PayoutListVO(rows, blocked, total);
    }

    private static BlockedRow row(String entityNo, String name,
                                  List<SettleBillVO> bills, String reason) {
        return new BlockedRow(entityNo, name,
                bills.stream().mapToLong(SettleBillVO::netMinor).sum(),
                bills.size(), reason);
    }
}
