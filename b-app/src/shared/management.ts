import {ROUTES} from './nav';
export function managementGo(page:keyof typeof ROUTES,params:Record<string,string|undefined>={}) {
 const query=Object.entries(params).filter(([,v])=>v!==undefined).map(([k,v])=>encodeURIComponent(k)+'='+encodeURIComponent(v!)).join('&');
 uni.navigateTo({url:ROUTES[page]+(query?'?'+query:'')});
}
export function managementTime(value?:string){if(!value)return '—';return new Date(value).toLocaleString();}
