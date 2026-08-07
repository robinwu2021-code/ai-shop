"use client";

// 第一层：原语（components/ui/*，无业务语义）。判据见 components/README.md。
import * as React from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";
import { Tabs } from "@/components/ui/tabs";
import { Progress } from "@/components/ui/progress";
import { Notice } from "@/components/ui/notice";
import { StatCard, EmptyState, Skeleton, PageTitle, Pagination } from "@/components/ui/misc";
import { Tooltip } from "@/components/ui/tooltip";
import { Tree, type TreeNode } from "@/components/ui/tree";
import { Section, Row, Cell, Flaw, Hint, Missing, Specimen } from "./kit";
import { Probe } from "./probe";

const BTN_VARIANTS = ["default", "outline", "secondary", "ghost", "destructive"] as const;
const BTN_SIZES = ["default", "sm", "lg", "icon"] as const;
/** 各 variant 的 hover 终态类（从 button.tsx 的 cva 里逐条抄下来做**静态复刻**）。
 *  CSS 没法从外部强制 :hover，所以这里并排放一份"长这样"的复刻件。 */
const BTN_HOVER: Record<(typeof BTN_VARIANTS)[number], string> = {
  default: "opacity-90",
  outline: "bg-accent text-accent-foreground",
  secondary: "bg-accent",
  ghost: "bg-accent text-accent-foreground",
  destructive: "opacity-90",
};

const TONES: BadgeTone[] = ["default", "success", "warning", "danger", "info", "muted", "outline"];

export function PrimitiveSections() {
  return (
    <>
      <ButtonSection />
      <BadgeSection />
      <InputSection />
      <SelectSection />
      <DateInputSection />
      <CardSection />
      <TableSection />
      <TabsSection />
      <ProgressSection />
      <NoticeSection />
      <StatCardSection />
      <EmptyStateSection />
      <SkeletonSection />
      <PageTitleSection />
      <PaginationSection />
      <TooltipSection />
      <TreeSection />
    </>
  );
}

// ────────────────────────────────────────────────────────────── Button
function ButtonSection() {
  return (
    <Section id="button" name="Button" layer="原语" file="ui/button.tsx" purpose="所有可点击动作的唯一入口；5 variant × 4 size。">
      {BTN_VARIANTS.map((v) => (
        <Row key={v} label={v} note="default / hover(复刻) / focus 环(复刻) / disabled">
          {BTN_SIZES.map((s) => (
            <Cell key={s} label={s}>
              <Button variant={v} size={s}>{s === "icon" ? <Loader2 /> : "操作"}</Button>
            </Cell>
          ))}
          <Cell label="hover 复刻">
            <Button variant={v} className={BTN_HOVER[v]}>操作</Button>
          </Cell>
          <Cell label="focus 环复刻">
            <Button variant={v} className="ring-2 ring-ring">操作</Button>
          </Cell>
          <Cell label="disabled">
            <Button variant={v} disabled>操作</Button>
          </Cell>
        </Row>
      ))}

      <Row label="文字对比度" note="主按钮/危险按钮的文字压在实色底上，AA 阈值 4.5（14px bold 不算大字）">
        {BTN_VARIANTS.map((v) => (
          <Probe key={v}><Button variant={v}>操作</Button></Probe>
        ))}
      </Row>

      <Row label="disabled 对比度" note="opacity-50 后文字对比度会掉一半；WCAG 对 disabled 不做强制要求，但可读性仍是真实问题">
        <Probe><Button disabled>操作</Button></Probe>
        <Probe><Button variant="outline" disabled>操作</Button></Probe>
      </Row>

      <Hint>
        真正的 <code>:focus-visible</code> 无法用 CSS 从外部强制触发，上面第 6 列是**静态复刻**。
        要验真实效果：点一下这段文字，然后连按 Tab 走一遍。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Badge
function BadgeSection() {
  return (
    <Section id="badge" name="Badge" layer="原语" file="ui/badge.tsx" purpose="全站色调联合的唯一真源（BadgeTone）；状态徽标的底层件。">
      <Row label="全部 tone" note="11px / font-bold / 药丸" stack>
        {TONES.map((t) => (
          <Cell key={t} label={t}>
            <Badge tone={t}>运行中</Badge>
          </Cell>
        ))}
      </Row>

      <Row label="实测对比度" note="每个 tone 的 --*-ink 压在 --*-tint 上的真实比值；随明暗/皮肤实时重量" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-4">
          {TONES.map((t) => (
            <div key={t} className="rounded-field bg-muted/60 p-2">
              <div className="mb-1 text-xs font-medium text-muted-foreground">tone=&quot;{t}&quot;</div>
              <Probe><Badge tone={t}>运行中</Badge></Probe>
            </div>
          ))}
        </div>
      </Row>

      <Row label="长文本 / 数字" note="徽标不换行；表格里常塞进「已归还(超时)」这类长词">
        <Badge tone="warning">已归还 · 超时 3 小时</Badge>
        <Badge tone="info" className="tabular-nums">1,204 台</Badge>
        <Badge tone="muted">—</Badge>
      </Row>

      <Hint>
        Badge 没有 disabled / loading / error 态 —— 它是纯展示件，这是**对的**，不算缺陷。
        它的"状态"维度就是 tone。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Input
const ERR_RING = "ring-2 ring-destructive"; // 抄自 form-drawer.tsx，组件本身不提供 invalid 入参

function InputSection() {
  const [v, setV] = React.useState("已填内容");
  return (
    <Section id="input" name="Input" layer="原语" file="ui/input.tsx" purpose="裸文本输入。筛选下拉请用 FilterSelect，搜索框在调用处传 rounded-full。">
      <Row label="default / 有值">
        <Input className="w-52" placeholder="请输入名称" />
        <Input className="w-52" value={v} onChange={(e) => setV(e.target.value)} />
      </Row>
      <Row label="focus 环复刻" note="真实 focus 请按 Tab">
        <Input className="w-52 ring-2 ring-ring" defaultValue="聚焦态" />
      </Row>
      <Row label="disabled / readOnly">
        <Input className="w-52" disabled placeholder="禁用" />
        <Input className="w-52" readOnly value="只读值" />
      </Row>
      <Row label="error" note="错误态由 FormDrawer 在调用处拼 ring-2 ring-destructive，Input 自己没有 invalid 入参">
        <div className="flex flex-col gap-1">
          <Input className={`w-52 ${ERR_RING}`} defaultValue="abc" />
          <span className="text-xs text-destructive">请输入数字</span>
        </div>
      </Row>
      <Row label="type 变体">
        <Input className="w-40" type="number" defaultValue={42} />
        <Input className="w-40" type="password" defaultValue="secret" />
        <Input className="w-52 rounded-full" placeholder="搜索（药丸变体）" />
      </Row>
      <Row label="对比度">
        <Probe pick={(r) => r.firstElementChild}><Input className="w-52" defaultValue="正文对比度" /></Probe>
        <Probe pick={(r) => r.firstElementChild}><Input className="w-52" placeholder="占位文字对比度" /></Probe>
      </Row>
      <Hint>
        占位文字用 <code>--muted-foreground</code>（对白底 3.9:1）。它低于 4.5 是**已知取舍**：
        占位符不是必读内容。但同一个色也用在表头/次要说明上，那些地方就需要复核。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Select
function SelectSection() {
  return (
    <Section id="select" name="Select" layer="原语" file="ui/input.tsx" purpose="裸原生下拉。列表页筛选请用组合件 FilterSelect。">
      <Row label="default / 有值">
        <Select defaultValue="a"><option value="a">已部署</option><option value="b">故障</option></Select>
        <Select className="w-52" defaultValue="b"><option value="a">已部署</option><option value="b">故障</option></Select>
      </Row>
      <Row label="focus 环复刻"><Select className="ring-2 ring-ring" defaultValue="a"><option value="a">聚焦态</option></Select></Row>
      <Row label="disabled"><Select disabled defaultValue="a"><option value="a">禁用</option></Select></Row>
      <Row label="error（调用处拼）"><Select className={ERR_RING} defaultValue=""><option value="">请选择</option></Select></Row>
      <Row label="空选项 / 长选项">
        <Select defaultValue=""><option value="">（无可选项）</option></Select>
        <Select className="max-w-64" defaultValue="a"><option value="a">杭州 · 西湖区 · 锦绣花园（邻家便利）</option></Select>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── DateInput
function DateInputSection() {
  return (
    <Section id="date-input" name="DateInput" layer="原语" file="ui/date-input.tsx" purpose="原生 date 输入，值恒为 YYYY-MM-DD。">
      <Row label="default / 有值">
        <DateInput className="w-44" />
        <DateInput className="w-44" defaultValue="2026-07-30" />
      </Row>
      <Row label="focus 环复刻"><DateInput className="w-44 ring-2 ring-ring" defaultValue="2026-07-30" /></Row>
      <Row label="disabled"><DateInput className="w-44" disabled defaultValue="2026-07-30" /></Row>
      <Row label="error（调用处拼）"><DateInput className={`w-44 ${ERR_RING}`} defaultValue="2026-07-30" /></Row>
      <Row label="min / max"><DateInput className="w-44" min="2026-07-01" max="2026-07-31" defaultValue="2026-07-30" /></Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Card
function CardSection() {
  return (
    <Section id="card" name="Card" layer="原语" file="ui/card.tsx" purpose="内容容器。无 tone = 白底+阴影；有 tone = 语义 tint 底、无阴影。">
      <Row label="default（含子件）" stack>
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>商家 M901</CardTitle>
            <CardDescription>杭州 · 锦绣花园 · 12 个 SKU</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">CardContent 内容区（p-5 pt-0）。</CardContent>
        </Card>
      </Row>
      <Row label="全部 tone" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {(["primary", "success", "warning", "danger", "info"] as const).map((t) => (
            <Card key={t} tone={t} className="p-4 text-xs font-medium">tone=&quot;{t}&quot;</Card>
          ))}
        </div>
      </Row>
      <Row label="tone 上的文字对比度" note="tint 底 + 继承 --card-foreground，没有配套的 ink 色" stack>
        <div className="grid w-full gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {(["primary", "success", "warning", "danger", "info"] as const).map((t) => (
            <Probe key={t}><Card tone={t} className="p-4 text-xs font-medium">tone={t}</Card></Probe>
          ))}
        </div>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Table
function TableSection() {
  return (
    <Section id="table" name="Table / THead / TBody / TR / TH / TD" layer="原语" file="ui/table.tsx" purpose="裸表格：色块表头 + 隔行浅底，无行线。列表页请用 DataTable。">
      <Row label="有数据" stack>
        <Table>
          <THead><TR><TH>编号</TH><TH>社区</TH><TH className="text-end">在售商品</TH></TR></THead>
          <TBody>
            {[1, 2, 3].map((i) => (
              <TR key={i}><TD>M90{i}</TD><TD>邻家便利</TD><TD className="text-end tabular-nums">12</TD></TR>
            ))}
          </TBody>
        </Table>
      </Row>
      <Row label="空 tbody" note="裸 Table 不管空态 —— 空态是 DataTable 的职责" stack>
        <Table><THead><TR><TH>编号</TH></TR></THead><TBody /></Table>
      </Row>
      <Row label="表头文字对比度" note="--muted-foreground 压在 --muted 上">
        <Probe pick={(r) => r.querySelector("th")}>
          <Table><THead><TR><TH>编号</TH></TR></THead><TBody /></Table>
        </Probe>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tabs
function TabsSection() {
  const [a, setA] = React.useState("all");
  const [b, setB] = React.useState("t3");
  return (
    <Section id="tabs" name="Tabs" layer="原语" file="ui/tabs.tsx" purpose="页内维度切换的分段控件（非 tab 导航，导航用 TabHeader）。">
      <Row label="两项" stack>
        <Tabs tabs={[{ key: "all", label: "全部" }, { key: "on", label: "在线" }]} value={a} onChange={setA} />
      </Row>
      <Row label="多项换行" note="容器 flex-wrap，窄屏会折行" stack>
        <Tabs
          tabs={Array.from({ length: 8 }, (_, i) => ({ key: `t${i}`, label: `维度 ${i + 1}` }))}
          value={b}
          onChange={setB}
        />
      </Row>
      <Row label="disabled 项" note="灰显但仍可见 —— 直接不渲染的话，用户不知道还有这个维度" stack>
        <Tabs
          tabs={[{ key: "all", label: "全部" }, { key: "x", label: "跨市场对比", disabled: true, title: "需开通多市场后可用" }]}
          value="all"
          onChange={() => {}}
        />
      </Row>
      <Row label="单项" note="只有一项时仍渲染整条槽（没有退化处理）" stack>
        <Tabs tabs={[{ key: "only", label: "唯一维度" }]} value="only" onChange={() => {}} />
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Progress
function ProgressSection() {
  return (
    <Section id="progress" name="Progress" layer="原语" file="ui/progress.tsx" purpose="「已用/总数 (百分比)」+ 细条；warnAt 到阈值转红。">
      <Row label="0 / 中间 / 满" stack>
        <div className="flex flex-wrap gap-6">
          <Progress value={0} total={12} />
          <Progress value={7} total={12} />
          <Progress value={12} total={12} />
        </div>
      </Row>
      <Row label="loading（不确定态）" note="必须与 0/0 区分：后者读起来是「额度为零」，是一件坏事" stack>
        <div className="flex flex-wrap gap-6">
          <Progress value={0} total={0} loading />
          <Progress value={0} total={12} />
        </div>
      </Row>
      <Row label="warnAt=90" note="未达阈值走主色，达到转 destructive" stack>
        <div className="flex flex-wrap gap-6">
          <Progress value={8} total={10} warnAt={90} />
          <Progress value={10} total={10} warnAt={90} />
        </div>
      </Row>
      <Row label="showText=false"><Progress className="w-40" value={3} total={10} showText={false} /></Row>
      <Row label="total=0（除零）" note="组件内做了 total>0 判断，退化为 0%"><Progress value={0} total={0} /></Row>
      <Row label="超额（value>total）" note="pct 被 Math.min 夹到 100，但文字仍显示 15/12"><Progress value={15} total={12} /></Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Notice
function NoticeSection() {
  return (
    <Section id="notice" name="Notice" layer="原语" file="ui/notice.tsx" purpose="页内灰底提示条，说明「当前视图为什么少了点什么」。">
      <Row label="default" stack><Notice>当前仅展示未归档记录。</Notice></Row>
      <Row label="长文本" stack>
        <Notice>
          当前角色的数据权限为「本机构及下级」，列表已按此过滤；跨机构数据需要平台超管授权后才能查看，
          导出同样受此范围限制。
        </Notice>
      </Row>
      <Row label="四档 tone" note="页面遇到「这条是警告」不该再自己拼彩色 div" stack>
        <Notice className="mb-0" tone="info">本页数据每 5 分钟刷新一次。</Notice>
        <Notice className="mb-0" tone="warning">该商家有 2 笔待处理的平台介入，处理完才能解除限制。</Notice>
        <Notice className="mb-0" tone="danger">封禁后该商家的在售商品会立即下架，且不可批量恢复。</Notice>
      </Row>
      <Row label="对比度"><Probe><Notice className="mb-0">灰底灰字</Notice></Probe></Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── StatCard
function StatCardSection() {
  return (
    <Section id="stat-card" name="StatCard" layer="原语" file="ui/misc.tsx" purpose="工作台 KPI 卡：标签 + 大数 + 同比小字。">
      <Row label="全部形态" stack>
        <div className="grid w-full gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="今日订单" value="1,204" />
          <StatCard label="今日营收" value="¥8,430" sub="+12.4% 环比" tone="up" />
          <StatCard label="待审商家" value="37" sub="+2 家" tone="down" />
          <StatCard label="加载中" value={<Skeleton className="h-6 w-20" />} />
        </div>
      </Row>
      <Row label="sub 文字对比度" note="tone=up 直接用 --success 原值当文字色（不是 --success-ink）" stack>
        <div className="flex flex-wrap gap-3">
          <Probe pick={(r) => r.querySelector(".text-xs")}><StatCard label="上升" value="1" sub="+12.4%" tone="up" /></Probe>
          <Probe pick={(r) => r.querySelector(".text-xs")}><StatCard label="下降" value="1" sub="+2 家" tone="down" /></Probe>
        </div>
      </Row>
      <Row label="loading" note="骨架高度 = 大数那一行的行高，加载完成时这一排不跳" stack>
        <div className="grid w-full grid-cols-2 gap-4">
          <StatCard label="近 7 日 GMV" value={null} loading />
          <StatCard label="近 7 日订单" value="1,204" />
        </div>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── EmptyState
function EmptyStateSection() {
  return (
    <Section id="empty-state" name="EmptyState" layer="原语" file="ui/misc.tsx" purpose="空态占位。文案要写清「为什么空、下一步做什么」。">
      <Row label="仅标题" stack><EmptyState title="暂无数据" /></Row>
      <Row label="标题 + 说明（推荐写法）" stack>
        <EmptyState title="当前筛选条件下没有商家" desc="试试清空「状态」筛选，或让 BD 先拉一家店入驻。" />
      </Row>
      <Row label="对比度">
        <Probe pick={(r) => r.querySelector(".text-xs")}>
          <EmptyState title="标题" desc="说明文字压在 muted/50 底上" />
        </Probe>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Skeleton
function SkeletonSection() {
  return (
    <Section id="skeleton" name="Skeleton" layer="原语" file="ui/misc.tsx" purpose="加载骨架块。">
      <Row label="常见尺寸" stack>
        <div className="w-full max-w-md space-y-2">
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="size-10 rounded-full" />
        </div>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── PageTitle
function PageTitleSection() {
  return (
    <Section id="page-title" name="PageTitle" layer="原语" file="ui/misc.tsx" purpose="紧凑页头：标题 + 说明小字 + 右侧动作。">
      <Row label="仅标题" stack><PageTitle title="商家档案" /></Row>
      <Row label="标题 + 说明" stack><PageTitle title="商家档案" desc="共 1,204 家，其中待审 37 家" /></Row>
      <Row label="带动作" stack><PageTitle title="商家档案" desc="共 1,204 家" action={<Button size="sm">新增商家</Button>} /></Row>
      <Row label="超长说明（截断）" stack>
        <PageTitle title="商家档案" desc={"很长的说明".repeat(20)} action={<Button size="sm">新增</Button>} />
      </Row>
      <Hint>与 TabHeader 是竞争关系：两者都是页头，字号一致（17px/extrabold）但一个带 tab 一个不带。</Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Pagination
function PaginationSection() {
  const [p, setP] = React.useState(3);
  return (
    <Section id="pagination" name="Pagination" layer="原语" file="ui/misc.tsx" purpose="上一页/下一页 + 总数。">
      <Row label="首页（上一页禁用）" stack><Pagination page={1} size={20} total={200} onPage={() => {}} /></Row>
      <Row label="中间页（可交互）" stack><Pagination page={p} size={20} total={200} onPage={setP} /></Row>
      <Row label="末页（下一页禁用）" stack><Pagination page={10} size={20} total={200} onPage={() => {}} /></Row>
      <Row label="只有一页" stack><Pagination page={1} size={20} total={3} onPage={() => {}} /></Row>
      <Row label="空列表（total=0）" stack><Pagination page={1} size={20} total={0} onPage={() => {}} /></Row>
      <Row label="每页条数 + 跳页" note="超过 5 页才出跳页框；越界不静默纠正，退回当前页" stack>
        <Pagination page={p} size={20} total={2000} onPage={setP} onSize={() => {}} />
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tooltip
function TooltipSection() {
  return (
    <Section id="tooltip" name="Tooltip" layer="原语" file="ui/tooltip.tsx" purpose="自绘文字提示（portal + fixed），替代原生 title。">
      <Row label="四个方向" note="悬停或键盘聚焦触发，延迟 120ms">
        {(["top", "right", "bottom", "left"] as const).map((s) => (
          <Tooltip key={s} label={`提示：${s}`} side={s}>
            {(p) => <Button {...p} variant="outline" size="sm">{s}</Button>}
          </Tooltip>
        ))}
      </Row>
      <Row label="label 为空" note="完全透传，不挂任何事件">
        <Tooltip>{(p) => <Button {...p} variant="ghost" size="sm">无提示</Button>}</Tooltip>
      </Row>
      <Row label="超长文本" note="max-w-240px + truncate">
        <Tooltip label="这是一段很长很长的提示文本，用来验证浮层是否会被截断以及是否会撑破视口边界">
          {(p) => <Button {...p} variant="outline" size="sm">长文本</Button>}
        </Tooltip>
      </Row>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Tree
const TREE: TreeNode[] = [
  {
    key: "org",
    label: "杭州运营中心",
    extra: <Badge tone="muted">32 人</Badge>,
    children: [
      { key: "org-a", label: "运维一组", extra: <Badge tone="muted">12 人</Badge>, children: [
        { key: "org-a1", label: "张三（组长）" },
        { key: "org-a2", label: "李四" },
      ] },
      { key: "org-b", label: "客服组", extra: <Badge tone="muted">8 人</Badge> },
    ],
  },
];

function TreeSection() {
  const [checked, setChecked] = React.useState<string[]>(["org-a1"]);
  return (
    <Section id="tree" name="Tree" layer="原语" file="ui/tree.tsx" purpose="层级本身即信息的列表：组织架构、权限码目录。勾选树只认叶子 key。">
      <Row label="只读树" stack><Tree nodes={TREE} empty="暂无组织" /></Row>
      <Row label="勾选树（含父节点半选）" stack>
        <Tree nodes={TREE} empty="暂无组织" checkable checkedKeys={checked} onCheckedChange={setChecked} />
      </Row>
      <Row label="disabled" stack>
        <Tree nodes={TREE} empty="暂无组织" checkable checkedKeys={checked} onCheckedChange={() => {}} disabled />
      </Row>
      <Row label="collapseFrom=1（深层默认收起）" stack><Tree nodes={TREE} empty="暂无组织" collapseFrom={1} /></Row>
      <Row label="loading" stack><Tree nodes={TREE} empty="暂无组织" loading /></Row>
      <Row label="empty" stack><Tree nodes={[]} empty="该角色还没有分配任何功能权限，点右上「编辑」开始分配。" /></Row>
    </Section>
  );
}
