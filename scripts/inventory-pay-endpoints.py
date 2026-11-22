"""盘点 ai-shop 网关模块里与支付相关的入口，输出 Markdown 表（见 TDD-支付三端经网关透传与权限归属-附录-入口盘点.md）。

用法：python3 scripts/inventory-pay-endpoints.py <仓库根目录>
形态是启发式初判：B 转发 / C1 薄转调 / C2 ai-shop 有业务，最终以人工核对为准。
"""
import re,os,subprocess,sys
root=sys.argv[1]
base=os.path.join(root,'backend/shop-gateway/src/main/java/ai/neargo/shop/portal')
rows=[]
for dp,_,fs in os.walk(base):
    for f in fs:
        if not f.endswith('Controller.java'): continue
        p=os.path.join(dp,f); s=open(p,encoding='utf-8').read()
        imps=re.findall(r'import ai\.neargo\.shop\.([\w.]+);',s)
        payc=[i for i in imps if i.startswith('payclient') or i.startswith('pay.client')]
        local=[i for i in imps if i.startswith('pay.') and not i.startswith('pay.client') and re.search(r'(Service|Port|Mapper|Repository)$',i.split('.')[-1])]
        dom=[i for i in imps if re.match(r'(merchant|trade|platform|order|spi)\.',i) and re.search(r'(Service|Port|Mapper)$',i.split('.')[-1])]
        if not (payc or local or 'Forwarder' in s): continue
        cls=re.search(r'@PreAuthorize\((.*)\n(?:@\w+.*\n)*public class',s)
        m=re.search(r'@RequestMapping\("([^"]+)"',s); pre=m.group(1) if m else ''
        end='运营' if '/ops/' in p else 'B端' if '/biz/' in p else 'C端' if '/mp/' in p else '?'
        fwd='Forwarder' in s and '/biz/pay/v2' in s
        eps=[]
        for mm in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\((?:value\s*=\s*)?"?([^",)]*)"?[^)]*\)\s*(?:\n\s*@\w+(?:\([^)]*\))?)*',s):
            blk=s[mm.start():mm.start()+600]
            pa=re.search(r'@PreAuthorize\((.*)',blk) or cls
            perm=''
            if pa:
                line=pa.group(1)
                codes=[c for c in re.findall(r"'([a-z][\w:.-]+)'",line)]+re.findall(r'Perms\.(\w+)',line)
                perm=', '.join(codes) if codes else re.sub(r'[@"()]','',line.split(')')[0])[:40]
            eps.append((mm.group(1).upper(),pre+mm.group(2),perm))
        if fwd: kind='B 转发'
        elif local or dom: kind='C2 初判'
        else: kind='C1 初判'
        rows.append((end,kind,f[:-5],len(eps),eps,payc[:2],local[:2]+dom[:1]))
rows.sort(key=lambda r:(r[0],r[1],r[2]))
print('| 端 | 形态 | Controller | 接口数 | 判权码（ai-shop 现状） | 调用 / 依赖 |')
print('|---|---|---|---|---|---|')
tot={}
for end,kind,c,n,eps,pc,lo in rows:
    perms=sorted({e[2] for e in eps if e[2]}) or ['（无注解）']
    dep=', '.join(x.split('.')[-1] for x in pc+lo)
    print(f"| {end} | {kind} | `{c}` | {n} | {'; '.join(perms)[:120]} | {dep} |")
    tot[(end,kind)]=tot.get((end,kind),0)+n
print()
print('| 端 | 形态 | 接口数 |'); print('|---|---|---|')
for k,v in sorted(tot.items()): print(f'| {k[0]} | {k[1]} | {v} |')
