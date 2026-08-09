package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PurchaseHistoryPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 「我买过的商家」的数据来源：**子订单**（一个子单 = 在一个商家下的一次单）。
 *
 * <p>不排除已取消的单：用户「买过」这家店是事实，取消过也算逛过 ——
 * 这个列表的用途是「再买一次」的入口，不是消费统计。
 */
@Component
public class PurchaseHistoryPortImpl implements PurchaseHistoryPort {

    private final SubOrderMapper subOrderMapper;

    public PurchaseHistoryPortImpl(SubOrderMapper subOrderMapper) {
        this.subOrderMapper = subOrderMapper;
    }

    @Override
    public List<MerchantPurchase> purchasedMerchants(String userNo) {
        List<OrdSubOrder> subs = subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, userNo));

        Map<String, List<OrdSubOrder>> byMerchant = subs.stream()
                .collect(Collectors.groupingBy(OrdSubOrder::getEntityNo));

        return byMerchant.entrySet().stream()
                .map(e -> new MerchantPurchase(e.getKey(), e.getValue().size(),
                        e.getValue().stream().mapToLong(this::createdAtMillis).max().orElse(0L)))
                .sorted(Comparator.comparingLong(MerchantPurchase::lastOrderAt).reversed())
                .toList();
    }

    private long createdAtMillis(OrdSubOrder s) {
        return s.getCreatedAt() == null ? 0L
                : s.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
