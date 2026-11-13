# ai-shop 部署环境模板

模板权威位置已从独立near-pay仓库归回本仓库。只含占位/非密说明，不是可直接上线的配置。

| 模板 | 加载入口 | 服务器原路径 |
|---|---|---|
| shop-app.env.example | deploy/tencent/systemd/ai-shop.service | /data/app/ai-shop/shop-app/shop-app.env |
| pay.env.example | deploy/tencent/systemd/ai-shop-pay.service | /data/app/ai-shop/pay-svc/pay.env |
| job.env.example | backend/deploy/tencent/ai-shop-job.service | /data/app/ai-shop/shop-job/job.env |
| logwatch.env.example | deploy/tencent/logwatch.sh | /data/app/ai-shop/ops/logwatch.env |

这些是既有腾讯部署入口；166容器使用其实际env-file，不因模板位置调整改变运行路径。

- 地区只显式配置SHOP_DEFAULT_MARKET；系统语言/币种/精度/时区尚需按地区运行配置接线，不另加地区环境变量。
- 本地/COS存储最小示例见[存储模板](../storage/README.md)。主模板按需开启注释项，不能复制空密钥上线。
- 独立库存模板继续在[库存模块](../../backend/inventory/deploy/inventory.env.example)，不建立重复副本。
- 独立near-pay规范名NEARPAY_*属于另一个项目，本目录pay.env.example仍使用本项目PAY_*。
- hxkey模板已归ai-hxkey仓库deploy/env-templates，不由ai-shop或near-pay维护第二份。
- 单个服务只加载自身需要的配置和凭据，不创建所有容器共用的大env。

本批未部署、未移动服务器上的私有文件。
