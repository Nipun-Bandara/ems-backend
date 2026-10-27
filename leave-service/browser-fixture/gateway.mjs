import http from 'node:http';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
// TEST ONLY: synthetic identities, bound to loopback. Never use as an operational gateway.
const portFile=fileURLToPath(new URL('../target/browser-fixture/port',import.meta.url));
const controlFile=fileURLToPath(new URL('../target/browser-fixture/control.json',import.meta.url));
const sessions={hr:{userId:6101,roles:['HR_MANAGER']},employee:{userId:6101,roles:['EMPLOYEE']},empty:{userId:6102,roles:['EMPLOYEE']},breakdown:{userId:6103,roles:['EMPLOYEE']}};
const user=(kind)=>({...sessions[kind],email:`${kind}@example.test`,username:`Fixture ${kind}`,isBanned:false,emailVerified:true,departmentId:null,departmentName:null,token:`fixture-${kind}`,refreshToken:`fixture-refresh-${kind}`});
http.createServer(async(req,res)=>{
 try {
  const body=await new Promise(resolve=>{let s='';req.on('data',v=>s+=v);req.on('end',()=>resolve(s));});
  const reply=(status,value)=>{res.writeHead(status,{'content-type':'application/json'});res.end(JSON.stringify(value));};
  if(req.url==='/api/auth/login') {
   const kind=JSON.parse(body).email?.split('@')[0];
   if(!sessions[kind]) return reply(401,{message:'Unknown synthetic fixture user'});
   return reply(200,user(kind));
  }
  const kind=req.headers.authorization?.replace('Bearer fixture-','');
  if(!sessions[kind]) return reply(401,{status:401,error:'Unauthorized',message:'Fixture session required'});
  if(req.url==='/api/auth/me') return reply(200,user(kind));
  if(req.url==='/api/auth/logout') return reply(200,{});
  if(req.url.startsWith('/api/employees/')) return reply(404,{message:'No fixture profile'});
  if(!req.url.startsWith('/api/leaves/')) return reply(404,{message:'Fixture route missing'});
  let control={};try{control=JSON.parse(readFileSync(controlFile,'utf8'));}catch{}
  if(control.delay) await new Promise(resolve=>setTimeout(resolve,control.delay));
  if(control.status) return reply(control.status,{status:control.status,error:'Fixture error',message:'Synthetic temporary leave service failure'});
  const port=readFileSync(portFile,'utf8').trim();
  const response=await fetch(`http://127.0.0.1:${port}${req.url}`,{method:req.method,headers:{'content-type':'application/json','X-User-Id':String(sessions[kind].userId),'X-User-Roles':sessions[kind].roles.join(',')},body:['GET','HEAD'].includes(req.method)?undefined:body});
  res.writeHead(response.status,{'content-type':'application/json'});res.end(await response.text());
 }catch(error){res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({status:502,error:'Fixture unavailable',message:error.message}));}
}).listen(18080,'127.0.0.1',()=>process.stdout.write('Synthetic identity/gateway fixture listening on 18080\n'));
