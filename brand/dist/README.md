# brand/dist —— 按端分发

> 由 `brand/build.py` 的 `build_dist()` 生成。**这是镜像，不是真源。**
> 真源是 `brand/build.py` 的参数；改参数重跑，这里全部刷新。
> 往这里手放文件下次会被删掉 —— 需要新增产物请加进生成器。

| 目录 | 谁用 |
|---|---|
| `c-app/` | C 端 App 与 H5 |
| `b-app/` | B 端 App 与 H5 |
| `mini-program/` | 微信小程序（两端 + 中文版）|
| `ops-web/` | 运营端 |
| `site-hxmall/` | **子业务官网 hxmall.top**（白底 + 红方章）|
| `site-hxtech/` | **母品牌官网 hxtech.top**（墨底 + 亮红弧）|
| `print/` | 印刷物料、名片 |
| `trademark/` | 商标申报 |

名片是**模板 + 数据表**：改 `brand/print/people.csv` 后跑 `python3 brand/gen-print.py`，每个人一套正反面。
