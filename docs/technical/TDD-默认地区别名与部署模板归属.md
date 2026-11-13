# 默认地区别名与部署模板归属

2026-11-13。档位1。状态：本地实现并验证；仅本地，不部署。依据：[变量处置表](reference/环境变量处置表.md) D01—D04及用户本轮授权。保留当前两仓库全部未提交改动。

## 0. 验收对应

| AC | 落点 | 验证 |
|---|---|---|
| 新部署只用SHOP_DEFAULT_MARKET；旧默认地区兼容 | near-pay现有YAML兼容链、规范模板 | CN/TW/AE解析、旧名过渡与直接属性冲突 |
| 迁移所有已支持旧别名，同值合并、异值拒绝 | near-pay config-env.py与phase1/phase2脚本 | 临时env含等号/字面特殊字符、重复键、旧新冲突；失败原文件不变 |
| 检查脚本与追加properties一致，无值泄漏 | check-config.sh、部署配套上传清单 | 地区覆盖、权限、密钥分离、追加加载路径校验；输出不含测试secret |
| 模板回归项目，无两份权威副本 | ai-shop deploy/env-templates；near-pay旧目录仅导航 | 变量集合/模板内容保留、加载路径不变、迁移后文档链接 |
| 保留未提交改动、不部署 | 两现有隔离工作树；临时目录本地验证 | 无远程写操作、无重启、无真实凭据修改 |

## 1. 方案

沿用near-pay YAML别名兼容，不删除已发布代码支持。新增一个Python3标准库配置工具，由既有shell脚本调用，集中别名映射、冲突判断和原子env替换，避免三份sed/awk映射分叉。工具不source文件、不展开变量、不记录值；输出只列键名/结论。部署脚本上传清单同时增加该工具。

地区不设TW自动迁移回退，必须来自原实例明确配置。旧新并存值不同拒绝；重复键不同也拒绝；同值规范化一次后再次执行不改内容或新增备份。cloud/secret中的显式near-pay.instance.region若与默认地区不一致，迁移与检查均拒绝。Java运行时已有冲突保护继续有效。未知Spring运行参数由正常启动校验负责，不声称静态脚本能模拟完整Spring环境。

phase1保留既有一次性密钥拆分语义，增加写入前别名预检并复用规范化工具；已拆分时继续拒绝重跑。phase2仅迁移env，幂等。备份/新env限制600，保留文件owner；不改原始凭据值和服务物理路径。

ai-shop模板搬至所属仓库deploy/env-templates；已确认独立ai-hxkey仓库存在且原工作树干净，hxkey模板归回其deploy/env-templates，明确非ai-shop运行配置。near-pay不保留可编辑重复模板，只留导航。库存模板已归库存模块，继续使用原路径。cloud/secret模板仍归near-pay，维持配置/密钥分离。

## 2. 实施计划

- [x] 测试：临时目录覆盖迁移幂等、所有别名、冲突不改文件、敏感输出、properties覆盖、权限、phase1预检与拆分、配套模板归属。
- [x] near-pay：统一模板、集中配置工具、三个既有迁移/检查入口与deploy-safe配套文件清单。
- [x] ai-shop：模板回归，补SHOP_DEFAULT_MARKET及统一存储入口，保留PAY_*内嵌支付模板；旧目录导航及引用更新。
- [x] 本地验证与一次只读复查，记录结果；不重复无关后端全量测试。

## 5. 验证结果

- `python3 -m unittest discover -s deploy/nearpay/prod/tests -v`：15项通过，1.363s，OK。全部使用临时目录和合成secret；覆盖别名映射与实际YAML全部兼容链一致、默认地区模板、值含等号/美元/反引号、同值去重/异值冲突、无变更重复执行、0600备份、两份properties覆盖、启动检查关闭拒绝、phase1先预检再拆分。
- shell入口与deploy-safe语法检查、跨仓库模板键集合/归属、明细引用和文档链接静态检查；复查无重要问题，密钥规则文档入口已修正为config-env.py。
- 上批Java地区解析14项通过证据仍在原存储TDD；本批未改Java/YAML，不重复编译。没有连接服务器、数据库或Docker；没有使用真实凭据、推送或部署。

## 6. 文件对账与偏差

near-pay：config-env.py、tests/test_config_env.py、apply-baseline/phase2/check-config/deploy-safe、默认地区模板及配套文档；旧ai-shop模板目录删除可编辑副本、仅留导航。ai-shop：deploy/env-templates四份模板及说明、TDD和处置表/明细同步。ai-hxkey：仅新增deploy/env-templates/hxkey.env.example。

原计划未确定hxkey实际源码路径，核对后找到干净的独立项目，模板直接归回该项目。其旧模板WX_TPL_ELEC_QUOTED并非本项目消费者读取名，已按application.yml修正为ELEC_WX_TPL_QUOTED；ai-shop对应旧变量未改。无数据库/运行代码变更，无原未提交代码覆盖。
