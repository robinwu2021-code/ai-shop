package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchFulfillmentChannel;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.FulfillmentChannelMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.spi.user.AdmissionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoreFulfillmentServiceImpl implements StoreFulfillmentService {

    /**
     * 商家可配的四路，<b>顺序即端上开关顺序</b>。
     * STORE_VERIFY / APPOINTMENT 不在此列 —— 那是服务类商品的属性，不是门店能力。
     */
    private static final List<String> CONFIGURABLE = List.of(
            Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP,
            Fulfillments.MERCHANT_DELIVERY, Fulfillments.EXPRESS);

    private static final String READONLY = "READONLY";

    private final FulfillmentChannelMapper channelMapper;
    private final MchStoreMapper storeMapper;
    private final AdmissionPort admissionPort;

    public StoreFulfillmentServiceImpl(FulfillmentChannelMapper channelMapper,
                                       MchStoreMapper storeMapper,
                                       AdmissionPort admissionPort) {
        this.channelMapper = channelMapper;
        this.storeMapper = storeMapper;
        this.admissionPort = admissionPort;
    }

    @Override
    public FulfillmentVO get(String merchantNo, String storeNo) {
        MchStore store = requireStore(merchantNo, storeNo);
        Map<String, MchFulfillmentChannel> rows = rowsOf(store.getStoreNo());
        List<ChannelVO> out = new ArrayList<>(CONFIGURABLE.size());
        for (String ch : CONFIGURABLE) {
            MchFulfillmentChannel row = rows.get(ch);
            out.add(new ChannelVO(ch,
                    row != null && Boolean.TRUE.equals(row.getEnabled()),
                    deniedByMatrix(merchantNo, ch),
                    row == null ? null : templateNoOf(row.getConfig())));
        }
        return new FulfillmentVO(store.getStoreNo(), out);
    }

    @Override
    @Transactional
    public FulfillmentVO save(String merchantNo, String storeNo, List<ChannelCmd> channels) {
        if (channels == null || channels.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchStore store = requireStore(merchantNo, storeNo);

        Map<String, ChannelCmd> byChannel = new LinkedHashMap<>();
        for (ChannelCmd cmd : channels) {
            // 值域在写入口拦：自由字符串写进库的下场见 fulfillment_reach 的历史（"ABC" 能保存，
            // 之后按范围查商品静默漏店）。重复的 channel 同样是端上出了错，拒掉而不是取末条
            if (cmd.channel() == null || !CONFIGURABLE.contains(cmd.channel())
                    || byChannel.put(cmd.channel(), cmd) != null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }

        boolean anyEnabled = byChannel.values().stream().anyMatch(ChannelCmd::enabled);
        if (!anyEnabled && !READONLY.equals(store.getStatus())) {
            /*
             * 一路都不开的店等于开不了张 —— 与「PICKUP 且清空覆盖项」同一形状的硬规则，
             * 同样没有任何报错症状（商品在架、订单为零），所以必须在写入口拦。
             * READONLY（套餐降级）的店除外：它本来就不接新单。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        boolean storePickupOn = byChannel.containsKey(Fulfillments.STORE_PICKUP)
                && byChannel.get(Fulfillments.STORE_PICKUP).enabled();
        if (storePickupOn && (store.getAddress() == null || store.getAddress().isBlank())) {
            // 门店自取的取货地址就是门店地址（刻意不另存一份），没有地址的自取是空承诺
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        for (ChannelCmd cmd : byChannel.values()) {
            if (cmd.enabled()) {
                // 准入矩阵：主体类型不允许的路，开不了。抛 FULFILLMENT_TIER_DENIED，
                // 端上据此显示原因 —— 与下单时同一个矩阵、同一个错误码
                admissionPort.requireFulfillmentAllowed(merchantNo, cmd.channel(), null);
            }
        }

        Map<String, MchFulfillmentChannel> rows = rowsOf(store.getStoreNo());
        for (ChannelCmd cmd : byChannel.values()) {
            MchFulfillmentChannel row = rows.get(cmd.channel());
            if (row == null) {
                row = new MchFulfillmentChannel();
                row.setStoreNo(store.getStoreNo());
                row.setEntityNo(merchantNo);
                row.setChannel(cmd.channel());
                row.setScopeMode(MchFulfillmentChannel.SCOPE_ALL);
            }
            row.setEnabled(cmd.enabled());
            if (Fulfillments.EXPRESS.equals(cmd.channel())) {
                row.setConfig(expressConfig(cmd.templateNo()));
            }
            if (row.getId() == null) {
                channelMapper.insert(row);
            } else {
                channelMapper.updateById(row);
            }
        }
        return get(merchantNo, store.getStoreNo());
    }

    @Override
    public List<StoreFulfillmentVO> byMerchant(String merchantNo) {
        List<MchStore> stores = storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getEntityNo, merchantNo)
                .orderByDesc(MchStore::getIsDefault).orderByAsc(MchStore::getId));
        List<StoreFulfillmentVO> out = new ArrayList<>(stores.size());
        for (MchStore store : stores) {
            FulfillmentVO vo = get(merchantNo, store.getStoreNo());
            out.add(new StoreFulfillmentVO(store.getStoreNo(), store.getName(),
                    store.getStatus(), vo.channels()));
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private MchStore requireStore(String merchantNo, String storeNo) {
        MchStore store;
        if (storeNo == null || storeNo.isBlank()) {
            store = storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getEntityNo, merchantNo)
                    .eq(MchStore::getIsDefault, true).last("LIMIT 1"));
        } else {
            store = storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getStoreNo, storeNo).last("LIMIT 1"));
            // 归属校验走 NOT_FOUND 不走 FORBIDDEN：别家门店号对本商家而言就是不存在，
            // 403 会把「存在哪些门店号」泄给猜号的人
            if (store != null && !merchantNo.equals(store.getEntityNo())) {
                store = null;
            }
        }
        if (store == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return store;
    }

    private Map<String, MchFulfillmentChannel> rowsOf(String storeNo) {
        Map<String, MchFulfillmentChannel> out = new LinkedHashMap<>();
        for (MchFulfillmentChannel row : channelMapper.selectList(
                Wrappers.<MchFulfillmentChannel>lambdaQuery()
                        .eq(MchFulfillmentChannel::getStoreNo, storeNo))) {
            out.put(row.getChannel(), row);
        }
        return out;
    }

    private boolean deniedByMatrix(String merchantNo, String channel) {
        try {
            admissionPort.requireFulfillmentAllowed(merchantNo, channel, null);
            return false;
        } catch (BizException e) {
            return true;
        }
    }

    /**
     * config 只有一个键，手写拼与手写拆 —— 引 Jackson 进来处理一个内部业务键
     * 是拿大炮换牙签，而 templateNo 是我们自己发的号，不含需要转义的字符。
     */
    private static String expressConfig(String templateNo) {
        return templateNo == null || templateNo.isBlank()
                ? null : "{\"templateNo\":\"" + templateNo + "\"}";
    }

    private static String templateNoOf(String config) {
        if (config == null) {
            return null;
        }
        int i = config.indexOf("\"templateNo\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + "\"templateNo\":\"".length();
        int end = config.indexOf('"', start);
        return end < 0 ? null : config.substring(start, end);
    }
}
