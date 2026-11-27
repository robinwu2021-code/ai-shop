package ai.neargo.shop.pay.channel.port;

import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.spi.dict.DictItem;
import ai.neargo.shop.spi.dict.DictType;
import ai.neargo.shop.spi.dict.DictionarySource;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 支付渠道的来源：旧表 {@code sys_pay_channel}（支付独立数据源，读经 {@link PayChannelMasterService}）。
 * 基础能力库落地后由 {@code sys_dict_item} 来源替换。
 *
 * <p>只给字典该有的：码、名、启停、排序。能不能下单（开着 ∩ 有网关实现）、费率、结算周期是支付的业务判断，
 * 仍在 {@code PayChannelMasterPort}。
 */
@Component
public class PayChannelDictionarySource implements DictionarySource {

    private final PayChannelMasterService master;

    public PayChannelDictionarySource(PayChannelMasterService master) {
        this.master = master;
    }

    @Override
    public DictType type() {
        return DictType.PAY_CHANNEL;
    }

    @Override
    public List<DictItem> items() {
        return master.all().stream()
                .map(c -> new DictItem(c.getTenantNo(), DictType.PAY_CHANNEL, c.getPayChannel(), null, null,
                        c.getName(), c.getName() == null ? Map.of() : Map.of(DictItem.LANG_ZH_CN, c.getName()),
                        sortOf(c), Boolean.TRUE.equals(c.getEnabled()), Map.of()))
                .toList();
    }

    /** 表里没有排序列，按建行顺序（与 {@link PayChannelMasterService#all()} 一致） */
    private static int sortOf(SysPayChannel c) {
        return c.getId() == null ? 0 : c.getId().intValue();
    }
}
