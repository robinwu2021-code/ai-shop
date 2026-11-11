"use client";
import { useState } from 'react';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useI18n } from '@/lib/i18n';
import { roleMatrixCell } from '@/lib/role-matrix';
import type { MenuFunction, RoleDef } from '@/lib/types';
import { Button } from '@/components/ui/button';

export function RoleMatrix({ roles, functions, loading, error }: { roles: RoleDef[]; functions: MenuFunction[]; loading?: boolean; error?: unknown }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [domain, setDomain] = useState('');
  const { locale, tNav } = useI18n();
  const en = locale === 'en';
  const queries = useQueries({ queries: roles.map(role => ({
    queryKey: ['role-points', role.roleCode], queryFn: () => api.getRolePoints(role.roleCode), enabled: open,
  })) });
  const labels = en ? { granted: 'Granted', denied: 'Unassigned', unavailable: 'Unavailable', planned: 'Planned' }
    : { granted: '已授权', denied: '未配置', unavailable: '不可用', planned: '待交付' };
  const pending = loading || queries.some(q => q.isPending);
  return <section className="mb-4">
    <Button size="sm" variant="outline" onClick={() => setOpen(!open)} aria-expanded={open}>{en ? 'Role matrix' : '角色矩阵'}</Button>
    {open && <div className="mt-3 space-y-3">
      <p className="text-sm text-muted-foreground">{en
        ? 'Configured point grants, not effective account access. Built-in rights and employee scope are enforced by the backend. AUDITOR is a reviewer; SUPER_ADMIN has wildcard access.'
        : '展示服务端功能点配置，不代表账号最终权限；内置权限和员工数据范围由后端计算。AUDITOR 是业务审核员，SUPER_ADMIN 持有全权限。'}</p>
      <select aria-label={en ? 'Module' : '功能模块'} value={domain} onChange={e => setDomain(e.target.value)} className="rounded border bg-background p-2">
        <option value="">{en ? 'All modules' : '全部模块'}</option>
        {functions.map(fn => <option key={fn.functionCode} value={fn.functionCode}>{tNav(fn.name)}</option>)}
      </select>
      {error || queries.some(q => q.isError) ? <div role="alert"><p>{en ? 'Grants could not be loaded.' : '授权数据加载失败。'}</p><Button size="sm" variant="outline" onClick={() => { for (const key of ['roles', 'perm-functions', 'role-points']) void client.invalidateQueries({ queryKey: [key] }); }}>{en ? 'Retry' : '重试'}</Button></div>
        : pending ? <p role="status">{en ? 'Loading grants…' : '正在读取授权…'}</p>
        : <div className="overflow-x-auto"><table className="w-full text-sm">
          <caption className="sr-only">{en ? 'Configured role grants' : '角色功能点配置矩阵'}</caption>
          <thead><tr><th scope="col" className="p-2 text-left">{en ? 'Permission' : '功能权限'}</th>
            {roles.map(role => <th scope="col" className="p-2" key={role.roleCode} title={role.roleCode}>{role.name}</th>)}
          </tr></thead>
          <tbody>{functions.filter(fn => !domain || fn.functionCode === domain).flatMap(fn => fn.points.map(point => <tr key={`${fn.functionCode}/${point.pointCode}`} className="border-t">
            <th scope="row" className="p-2 text-left font-normal"><div>{tNav(fn.name)} / {tNav(point.name)}</div>
              <div className="text-xs text-muted-foreground">{point.pointType === 'MENU' ? (en ? 'Menu' : '菜单') : (en ? 'Action' : '操作')} · {point.permCode ?? point.uiPermCode ?? '—'}</div></th>
            {roles.map((role, i) => <td className="p-2 text-center whitespace-nowrap" key={role.roleCode}>{labels[roleMatrixCell(point, queries[i].data!)]}</td>)}
          </tr>))}</tbody>
        </table></div>}
    </div>}
  </section>;
}
