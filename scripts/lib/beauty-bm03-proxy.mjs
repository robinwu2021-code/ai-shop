import http from 'node:http';

/** Infrastructure-only fault injection: forward exact bytes, then discard a real upstream response. */
export async function startFaultProxy(target) {
 const events=[];let drop=null,block=null;
 const server=http.createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);
  const body=Buffer.concat(chunks);let parsed;try{parsed=JSON.parse(body);}catch{}
  const event={path:req.url,method:req.method,body:parsed,rawBody:body.toString(),dropped:false};
  events.push(event);
  if(block?.(event)){event.blocked=true;res.destroy();return;}
  const upstream=http.request(target+req.url,{method:req.method,headers:{...req.headers,host:new URL(target).host}},r=>{
   const data=[];r.on('data',b=>data.push(b));r.on('end',()=>{
    const bytes=Buffer.concat(data);event.status=r.statusCode;
    try{event.response=JSON.parse(bytes);}catch{event.response=bytes.toString();}
    if(drop?.(event)){drop=null;event.dropped=true;res.destroy();return;}
    res.writeHead(r.statusCode,r.headers);res.end(bytes);
   });
  });
  upstream.on('error',error=>{event.error=error.code;res.destroy();});upstream.end(body);
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 return {url:'http://127.0.0.1:'+server.address().port,events,
  dropOnce(predicate){drop=predicate;},block(predicate){block=predicate;},
  close(){server.closeAllConnections();return new Promise(resolve=>server.close(resolve));}};
}
