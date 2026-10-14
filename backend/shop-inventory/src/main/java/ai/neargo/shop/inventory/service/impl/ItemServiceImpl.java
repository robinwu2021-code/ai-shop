package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.service.ItemService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 物料上商家能改的那部分。今天只有安全库存。 */
@ConditionalOnInventory
@Service
public class ItemServiceImpl implements ItemService {

    /**
     * 阈值上限。**不是怕数据库存不下** —— 是拦住手滑：库存输入框是数字键盘，
     * 多按一个零就把「50」变成「500」，而那之后这件货**永远显示缺货**，
     * 商家看到的是「补了货还是红的」，不会想到是阈值的问题。
     */
    private static final int MAX_SAFETY = 999_999;

    private final ItemMapper itemMapper;
    private final BalanceMapper balanceMapper;

    public ItemServiceImpl(ItemMapper itemMapper, BalanceMapper balanceMapper) {
        this.itemMapper = itemMapper;
        this.balanceMapper = balanceMapper;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void setSafetyStock(String ownerId, String itemId, String locationId,
                               Integer qty, String operator) {
        if (qty != null && (qty < 0 || qty > MAX_SAFETY)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // **先确认这件货是他的**。进销存不走平台的 DataScope，每个查询都要显式带
        // ownerId —— 漏一处就是跨商家写入，而它不会报错。
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, itemId));
        if (item == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        if (locationId == null || locationId.isBlank()) {
            // 物料上的默认值：那一列 NOT NULL，null 在这里没有含义
            if (qty == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            InvItem patch = new InvItem();
            patch.setId(item.getId());
            patch.setSafetyStock(qty);
            patch.setUpdatedBy(operator);
            itemMapper.updateById(patch);
            return;
        }

        // 库位覆盖：qty 为 null 就是撤掉覆盖、跟随默认值
        if (balanceMapper.setSafetyStock(ownerId, itemId, locationId, qty, operator) == 0) {
            // 这件货在这个库位上还没有余额行 —— 一次都没进过货。
            // 报 NOT_FOUND 而不是替他建一行：建出来的行 on_hand=0，
            // 会凭空出现在库存列表里，且立刻因为「低于阈值」而标红
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
}
