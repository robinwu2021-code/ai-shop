"use client";
import { useState } from 'react';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useI18n } from '@/lib/i18n';
import { useCopy } from '@/lib/use-copy';
import { roleMatrixCell } from '@/lib/role-matrix';
import type { MenuFunction, RoleDef } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/ui/data-table';
import { IAM_COPY } from './copy';

type MatrixRow = { functionCode: string; functionName: string; point: MenuFunction['points'][number] };

export function RoleMatrix({ roles, functions, loading, error }: { roles: RoleDef[]; functions: MenuFunction[]; loading?: boolean; error?: unknown }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [domain, setDomain] = useState('');
  const { tNav } = useI18n();
  const c = useCopy(IAM_COPY);
  const queries = useQueries({ queries: roles.map(role => ({
    queryKey: ['role-points', role.roleCode], queryFn: () => api.getRolePoints(role.roleCode), enabled: open,
  })) });
  const labels = { granted: c.matrixGranted, denied: c.matrixDenied, unavailable: c.matrixUnavailable, planned: c.matrixPlanned };
  const pending = loading || queries.some(q => q.isPending);
  const failed = error ?? queries.find(q => q.isError)?.error;
  const retry = () => { for (const key of ['roles', 'perm-functions', 'role-points']) void client.invalidateQueries({ queryKey: [key] }); };
  const rows: MatrixRow[] = functions.filter(fn => !domain || fn.functionCode === domain)
    .flatMap(fn => fn.points.map(point => ({ functionCode: fn.functionCode, functionName: fn.name, point })));
  const columns: Column<MatrixRow>[] = [
    { header: c.matrixPermission, cell: row => <div>
      <div>{tNav(row.functionName)} / {tNav(row.point.name)}</div>
      <div className="txt-caption text-muted-foreground">{row.point.pointType === 'MENU' ? c.matrixMenu : c.matrixAction} · {row.point.permCode ?? row.point.uiPermCode ?? '—'}</div>
    </div> },
    ...roles.map((role, i): Column<MatrixRow> => ({
      header: <span title={role.roleCode}>{role.name}</span>, align: 'center', className: 'whitespace-nowrap',
      cell: row => labels[roleMatrixCell(row.point, queries[i].data!)],
    })),
  ];
  return <section className="mb-4">
    <Button size="sm" variant="outline" onClick={() => setOpen(!open)} aria-expanded={open}>{c.matrixToggle}</Button>
    {open && <div className="mt-3 space-y-3">
      <p className="txt-body text-muted-foreground">{c.matrixNote}</p>
      <select aria-label={c.matrixModule} value={domain} onChange={e => setDomain(e.target.value)} className="focus-ring rounded-field border bg-background p-2">
        <option value="">{c.matrixAllModules}</option>
        {functions.map(fn => <option key={fn.functionCode} value={fn.functionCode}>{tNav(fn.name)}</option>)}
      </select>
      {pending && !failed ? <p role="status" className="txt-body">{c.matrixLoading}</p>
        : <DataTable columns={columns} rows={failed ? [] : rows} error={failed ? new Error(c.matrixLoadFailed) : undefined}
            onRetry={retry} empty={c.matrixEmpty} rowKey={row => `${row.functionCode}/${row.point.pointCode}`} />}
    </div>}
  </section>;
}
