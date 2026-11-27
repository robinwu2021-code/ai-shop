package ai.neargo.shop.spi.logistics;

import ai.neargo.shop.spi.trade.WxShippingPort.ShippingItem;

import java.util.List;

/**
 * 微信发货信息上传成功后，把对应运单记成“已上传”。
 *
 * <p>上传台账与运单同库，调用方在自己的事务里直接调，不经发件箱。
 */
public interface WaybillWxUploadPort {

    /**
     * @param outTradeNo 微信支付商户单号，运单按它查
     * @param at         上传成功的时间（毫秒）
     * @param shipments  这次上传的包裹；空表示旧格式，只认没有来源发货号的运单
     */
    void markUploaded(String outTradeNo, long at, List<ShippingItem> shipments);
}
