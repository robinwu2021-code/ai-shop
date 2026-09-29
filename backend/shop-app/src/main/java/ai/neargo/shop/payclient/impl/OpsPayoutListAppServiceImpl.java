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

    public OpsPayoutListAppServiceImpl(SettleService settleService,
                                       PayoutAccountPort payoutAccounts,
                                       MerchantQueryPort merchantPort) {
        this.settleService = settleService;
        this.payoutAccounts = payoutAccounts;
        this.merchantPort = merchantPort;
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
        List<SettleBillVO> all = settleService.opsPayables(null, entityNo);

        // 已付的不再出现：它既不该重复付，也不属于「本该付而没付」
        Map<String, List<SettleBillVO>> byEntity = all.stream()
                .filter(b -> !StlBill.PAID.equals(b.status()))
                .collect(Collectors.groupingBy(SettleBillVO::merchantNo,
                        LinkedHashMap::new, Collectors.toList()));
        if (byEntity.isEmpty()) {
            return new PayoutListVO(List.of(), List.of(), 0L);
        }

        Map<String, MerchantQueryPort.MerchantBrief> briefs =
                merchantPort.findAll(Set.copyOf(byEntity.keySet()));

        List<PayoutRow> rows = new ArrayList<>();
        List<BlockedRow> blocked = new ArrayList<>();
        long total = 0L;

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
                    payable.stream().map(SettleBillVO::settleNo).toList()));
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
