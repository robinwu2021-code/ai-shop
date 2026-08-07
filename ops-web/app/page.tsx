"use client";

// 工作台（矩阵 P-16.1 数据看板）。脚手架阶段只铺三块：KPI / 趋势 / 获客漏斗，
// 其中获客漏斗对应 P-16.1.4 —— 门店获客是一期的主路径，看板要能证明它在跑。
import { useQuery } from "@tanstack/react-query";
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatRow, PageTitle, Skeleton, StatCard } from "@/components/ui/misc";
import { fill, useCopy } from "@/lib/use-copy";
import { HOME_COPY } from "./copy";

export default function DashboardPage() {
  const { t } = useI18n();
  const c = useCopy(HOME_COPY);
  const FUNNEL_LABEL: Record<string, string> = {
    SCAN: c.funnelScan,
    ENTER_STORE: c.funnelEnterStore,
    REGISTER: c.funnelRegister,
    FIRST_ORDER: c.funnelFirstOrder,
  };
  const kpi = useQuery({ queryKey: ["dashboard", "kpi"], queryFn: () => api.getDashboardKpi() });
  const trend = useQuery({ queryKey: ["dashboard", "trend"], queryFn: () => api.getDashboardTrend() });
  const funnel = useQuery({ queryKey: ["dashboard", "funnel"], queryFn: () => api.getAcquisitionFunnel() });

  const k = kpi.data;

  return (
    <div>
      <PageTitle title={c.title} desc={c.desc} />

      {/* 加载态走 StatCard 自己的 loading：此前是拿 6 个等高灰块顶替整张卡，
          卡片的标签行不在，加载完成时这一排会跳一下 */}
      <StatRow>
        {kpi.isLoading || !k ? (
          [c.kpiGmv, c.kpiOrders, c.kpiAov, c.kpiPendingMerchant, c.kpiPendingAfterSale, c.kpiRedeemRate].map(
            (label) => <StatCard key={label} label={label} value={null} loading />,
          )
        ) : (
          <>
            <StatCard label={c.kpiGmv} value={money(k.gmv)} />
            <StatCard label={c.kpiOrders} value={k.orderCount.toLocaleString()} />
            <StatCard label={c.kpiAov} value={money(k.avgOrderValue)} />
            {/* 这三张是**待办**而不是统计：数字大代表有人在等，故用告警色调 */}
            <StatCard label={c.kpiPendingMerchant} value={k.pendingMerchantAudit} sub={c.kpiPendingMerchantSub} tone={k.pendingMerchantAudit > 0 ? "down" : undefined} />
            <StatCard label={c.kpiPendingAfterSale} value={k.pendingAfterSale} sub={c.kpiPendingAfterSaleSub} tone={k.pendingAfterSale > 0 ? "down" : undefined} />
            <StatCard label={c.kpiRedeemRate} value={`${Math.round(k.redeemRate * 100)}%`} sub={c.kpiRedeemRateSub} />
          </>
        )}
      </StatRow>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>{c.chartTrend}</CardTitle>
          </CardHeader>
          <CardContent>
            {trend.isLoading ? (
              <Skeleton className="h-[240px]" />
            ) : (
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={trend.data}>
                  {/* 图表颜色走 CSS 变量：换肤与明暗切换时图表要跟着变，写死 hex 就会在暗色下发飘 */}
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="date" stroke="var(--muted-foreground)" fontSize={12} />
                  <YAxis stroke="var(--muted-foreground)" fontSize={12} tickFormatter={(v) => fill(c.axisWan, { n: Math.round(v / 100_00) })} />
                  <Tooltip
                    contentStyle={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: "var(--r-card)" }}
                    formatter={(v: number, name) => (name === "gmv" ? money(v) : v)}
                  />
                  {/* isAnimationActive={false}：recharts 2 的入场动画走 react-smooth，在 React 19 下
                      实测停在第 0 帧 —— 折线只剩起点一个点、柱子高度为 0，看着像"没数据"。
                      运营看板本来也不需要入场动画，关掉比赌它修好更可靠。 */}
                  <Line type="monotone" dataKey="gmv" stroke="var(--primary)" strokeWidth={2} dot={false} isAnimationActive={false} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{c.chartFunnel}</CardTitle>
          </CardHeader>
          <CardContent>
            {funnel.isLoading ? (
              <Skeleton className="h-[240px]" />
            ) : (
              <ResponsiveContainer width="100%" height={240}>
                <BarChart data={(funnel.data ?? []).map((f) => ({ ...f, label: FUNNEL_LABEL[f.step] ?? f.step }))}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="label" stroke="var(--muted-foreground)" fontSize={12} />
                  <YAxis stroke="var(--muted-foreground)" fontSize={12} />
                  <Tooltip
                    contentStyle={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: "var(--r-card)" }}
                  />
                  <Bar dataKey="count" fill="var(--primary)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>
      </div>

      <p className="mt-4 txt-caption text-muted-foreground">
        {t("common.mockData")} · {c.soon}
      </p>
    </div>
  );
}
