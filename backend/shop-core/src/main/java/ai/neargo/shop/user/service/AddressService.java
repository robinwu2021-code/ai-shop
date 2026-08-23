package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.AddressVO;

import java.util.List;

/**
 * 地址簿（[API 清单 §2.2] / R1）。**送货上门与快递两条履约线的前置**。
 *
 * <p>四个方法**全部返回整个列表**而不是单条：端上每次操作后都要刷新列表，
 * 返回单条会逼前端自己合并状态 —— 而「设为默认」这类操作会同时改动两条记录，
 * 前端合不对就会出现两个默认地址的假象。
 */
public interface AddressService {

    List<AddressVO> list();

    /** {@code addressId} 为空即新增。首个地址自动成为默认。 */
    List<AddressVO> save(SaveCommand cmd);

    /** 软删除（契约禁止 delete*）。 */
    List<AddressVO> archive(String addressId);

    List<AddressVO> setDefault(String addressId);

    /**
     * @param latE6 收货地址坐标（gcj02，E6）。<b>null = 这次不改</b> ——
     *              老版本端上不传这两个字段，把缺省当清空会把已标好的点抹掉。
     *              这两列 V1 就建了（注释写着「配送范围校验用」），但在此之前<b>全链路无人写入</b>，
     *              于是「商家自送半径」这个设置一直算不出任何东西
     */
    record SaveCommand(String addressId, String name, String phone, String region,
                       String province, String city, String district, String detail,
                       Boolean isDefault, String tag,
                       Integer latE6, Integer lngE6) {

        /** 不带坐标的老形状 */
        public SaveCommand(String addressId, String name, String phone, String region,
                           String province, String city, String district, String detail,
                           Boolean isDefault, String tag) {
            this(addressId, name, phone, region, province, city, district, detail, isDefault, tag, null, null);
        }
    }
}
