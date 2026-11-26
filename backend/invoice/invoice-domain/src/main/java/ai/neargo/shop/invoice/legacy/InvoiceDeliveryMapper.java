package ai.neargo.shop.invoice.legacy;

import ai.neargo.shop.invoice.entity.IvcInvoiceDelivery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 邮件投递留在主库原表，等通知模块对齐新库（TDD-多数据源与发票打印对齐新库 §2.4 乙类）。 */
public interface InvoiceDeliveryMapper extends BaseMapper<IvcInvoiceDelivery> {}
