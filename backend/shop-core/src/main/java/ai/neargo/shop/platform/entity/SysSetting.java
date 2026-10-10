package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台可调参数：一行一组，值是 JSON。
 *
 * <p>结构由使用方定义，这张表只负责「存住 + 留痕」。校验（比如三维权重之和必须为 100）
 * 留在各自的 Service 里 —— 放进这一层就得为每种参数写一段判断，
 * 那是把领域知识塞进基础设施。
 */
@Getter
@Setter
@TableName("sys_setting")
public class SysSetting extends BaseEntity {

    private String settingKey;
    private String settingValue;

    /** 给运营看的说明：这组参数是干什么的。 */
    private String remark;
}
