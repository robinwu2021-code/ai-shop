# 本地存储根路径与多云切换

状态：本地存储首批已实现并验证，后续已授权部署166；完整地区配置仍有P1待办。日期：2026-11-13。
依据：用户要求更新分支、梳理文件/图片保存位置，同时考虑腾讯云、Google Cloud和本地存储，先实现可切换本地存储，子目录沿用云对象key。沿用既有 [图片存储设计](design/TDD-图片存储与门店空间清理.md)，不另建存储接口或新表。

## 0. 验收对应

| AC | 实现落点 | 验证 |
|---|---|---|
| 统一规划存储根路径，沿用旧配置 | application.yml及现有本地适配器、读取与回填配置 | root变化后文件写入/读取/回填均指向同一路径；显式旧配置优先 |
| 子目录和云key相同 | LocalDiskMediaStore、现有MediaStore接口 | 商品图、私有凭证和CSV按原key落盘，URL不含物理根路径 |
| local/cos显式切换 | 原ConditionalOnProperty装配；本地资源映射限local | 各模式只有一个MediaStore，COS模式不暴露本地目录 |
| 本地私有文件可用 | MediaReadController | 有效签名可读；无签名、过期、篡改拒绝；PDF/CSV Content-Type正确 |
| 166真实图片闭环 | 双域名uploads/media路由、统一公开用途规则 | 真实登录上传与新旧域名字节回读；证照与核款凭证仍需签名 |
| 梳理位置、规划Google接入 | reference/文件与图片存储位置.md | 区分已实现和计划；不声称Google已可选，不迁移near-pay加密私有材料 |

## 1. 方案与取舍

选择复用MediaStore，而非新建一套通用对象存储或修改数据库模型。已有local与cos适配；本地实现并非从零开发，本批补齐统一根路径、切换及文件读取。

- 宿主机建议根：`/data0/app/ai-shop/storage`，容器统一挂载到`/data/storage`。
- 新配置：`shop.storage.root` / `SHOP_STORAGE_ROOT`，缺省`./data`，保持旧的`./data/uploads`、`./data/print-private`不变。
- 媒体：`${shop.upload.dir:${shop.storage.root:./data}/uploads}`。保留`shop.upload.dir`/`SHOP_UPLOAD_DIR`作为明确覆盖，现有部署无需立刻改路径。
- 打印：`${shop.print.artifacts.root:${shop.storage.root:./data}/print-private}`，保留原单独覆盖。
- 存储根只是磁盘落点，不加到asset_key或URL中。普通媒体的桶根对应`<root>/uploads`，其下逐字等于COS object key。
- root不变，切provider不会复制文件；先复制并核对key、字节及私有访问权限，再修改配置重启。不存在自动跨provider兜底。
- 公共图按现有SysMediaAsset声明允许goods、avatar、evidence（保留已有旧两段商品图兼容）；证件、付款凭证、报表签名或授权读取。禁止nginx把整个存储根直接alias成公开目录。
- Google Cloud作为下一适配器接入同一MediaStore；本批先规划bucket、key、签名及权限，未实现前不得配置为生产provider。
- near-pay进件附件、渠道认领凭证当前为数据库密文及Vault密钥，本批不改其存储/加密/归属模型。

## 2. 实施计划与文件

- [x] 根路径：LocalDiskMediaStore、MediaReadController、UploadResourceConfig、MediaBackfillService、PrivateLocalArtifactStore沿同一配置回退规则；application.yml配置和样例。
- [x] 切换：UploadResourceConfig仅在local模式装配；沿原COS适配、密钥及目录规则。
- [x] 文件读取：私有签名读取按真实扩展名设置MIME，未知类型用octet-stream，不把CSV/PDF当JPEG。
- [x] 验证：本地真实磁盘读写与删除、装配切换、旧配置覆盖、公共/私有HTTP和原COS路径回归；不连接或写入云桶。
- [x] 位置清单：记录媒体、打印、报表、付款凭证、near-pay密文等各自权威位置、配置和切换步骤。

不修改API结构、角色权限、历史SQL或业务文件记录；不自动搬文件，不部署到166。

## 5. 验证结果

JDK21，Maven `-pl shop-app -am test -Dtest=LocalStorageRootTest,LocalMediaHttpReadTest,PrivateArtifactRootTest,MediaKeyRoundTripTest,PrivateReportStreamTest,CosSignedUrlRebaseTest,CosThumbUrlTest -Dsurefire.failIfNoSpecifiedTests=false`：BUILD SUCCESS，7个测试类19项通过，无失败/跳过。编译覆盖受影响模块及其必要依赖。

- LocalStorageRootTest：统一root下真实写入/回读/删除、旧目录优先、COS排除本地实现。
- LocalMediaHttpReadTest：公开图、私有签名、无签名/过期/篡改拒绝、PDF/CSV/未知类型MIME、本地公共路由仅在local开放。
- PrivateArtifactRootTest：打印同根命名空间、原单独root优先。
- 原COS签名/缩略图和对象key回收解析回归通过；没有访问或写入云桶。
- 独立near-pay另行验证BUILD SUCCESS：DefaultMarketConfigurationTest 4项、CatalogStartupChecksTest 4项、ConfigBaselineTest 6项，共14项通过。实际SystemEnvironmentPropertySource覆盖CN/TW/AE、旧别名、历史缺省及冲突；现有启动钩子拒绝统一默认地区与最终地区不一致（startup-check默认开启）。

Google适配、完整地区默认语言启动接线不在上述已通过范围；详见配置清单。项目增量验证约定适用，不因文档修改或提交重复运行业务测试。

## 6. 地区配置补充

用户追加同一制品多地区部署要求：技术存储开关在运行时环境变量，地区业务配置只保留默认地区。已按源码整理 [运行时环境变量与地区配置清单](reference/运行时环境变量与地区配置清单.md)，币种、scale与支付产品amountScale沿用原数据owner；语言启动接线等缺口单列，不在本批伪造已实现。

## 7. 166发布补记

已部署本地根路径兼容及MIME修复，并补打印共享持久卷，普通媒体原挂载保留。实际配置、制品与验证见[发布记录](部署-地区配置与本地存储-166-2026-11-13.md)。前述暂不部署为初始阶段范围。

## 8. 图片实测补正（2026-11-13）

真实HTTPS商品图片上传成功并落盘，但公网回读失败；API/OPS本机均200，定位166双域名遗漏/uploads路由。网关只代理应用，不alias磁盘；/media保留签名校验并按原核款凭证方式在API404时交OPS验证。201通用转发无需改动，保留支付H5与现有首页。

同时对齐既有SysMediaAsset的公开用途（GOODS/AVATAR/EVIDENCE），复用MediaKeys供本地、COS和列表小图共同判断；证照、商家售后、核款及报表仍私有，COS新上传显式Private，不调整历史桶ACL。

- [x] HTTP回归先复现头像公开URL返回404；5项中1失败，符合根因。
- [x] 统一规则及COS ACL回归、本地打包：7类21项通过；COS历史头像/晒单列表缩略图保留签名读取，未迁移旧桶ACL。
- [x] 166双域名真实登录上传与商品图200字节一致；头像/晒单临时合成文件回读及私有未签名/伪造签名404共28项通过，临时文件已清理。管理/鉴权18项通过。有效私有签名正向读取本批在本地HTTP测试验证，未声称App界面或正式私有业务预览验收。详见[发布记录](部署-图片上传与双域名回读-166-2026-11-13.md)。

App后续补证：26项既有契约测试和1项真实Flutter上传/双域解码/组件显示测试通过；使用本地现有App工作树，未发布App。原生相册被隔离模拟器共享存储故障阻断，保持待验收，详见图片发布记录的App补充验证。
