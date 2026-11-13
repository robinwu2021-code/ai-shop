# 本地与云存储配置

实现和目录：[文件与图片存储位置](../../docs/technical/reference/文件与图片存储位置.md)。

`local.env.example`为本地模板，`cos.env.example`为原COS模板。复制到仓库外的私有配置目录并补齐密钥；样例不是直接可用于生产的凭据文件。本批未部署或切换166。

建议宿主机根`/data0/app/ai-shop/storage`，容器挂载`/data/storage`，设置`SHOP_STORAGE_ROOT=/data/storage`。媒体落`uploads/<原object-key>`，打印落`print-private/<原打印key>`。默认root仍为`./data`，旧目录及明确的单独覆盖继续生效。

API与OPS必须共用持久目录和媒体签名密钥。只通过应用的公开goods路径和私有签名路径读取，不把存储根直接配置为nginx公开alias。166新域名、新旧域名的网关都需按现有后端路由转发；本批不修改线上网关。

切换前复制对象并核对key、字节、哈希及原链接；provider切换不搬文件、不改业务URL、不做跨provider自动兜底。COS模式不装配本地公共目录和私有签名读取器，不能指望旧本地URL继续从磁盘返回。只有完成原URL/域名适配和对象验收后才能撤下旧根/旧桶。

Google Cloud Storage尚未实现。本批仅在设计中列出公共/私有桶、ADC和V4签名接入要求，不提供可用的`provider=gcs`样例。

地区配置只设置默认地区`SHOP_DEFAULT_MARKET`，不在环境样例放语言、币种、小数位或支付小数布尔值；完整归属、启动流程及现有差距见 [运行时环境变量与地区配置清单](../../docs/technical/reference/运行时环境变量与地区配置清单.md)。
