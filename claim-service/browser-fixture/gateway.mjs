// TEST ONLY: loopback synthetic identity for the disposable browser fixture.
import http from "node:http";
import { readFileSync } from "node:fs";
const portFile=new URL("../target/browser-fixture/port",import.meta.url);
const sessions={employee:6101,other:6102,manager:6201,unrelated:6202,hr:6301};
const user=kind=>({userId:sessions[kind],email:`${kind}@example.test`,username:kind,roles:["EMPLOYEE"],isBanned:false,emailVerified:true,departmentId:null,departmentName:null,token:`fixture-${kind}`,refreshToken:`fixture-refresh-${kind}`});
let failFinalizeOnce = process.env.CLAIM_RECEIPT_FAIL_FINALIZE_ONCE === "true";
http.createServer(async(req,res)=>{
  const reply=(status,value)=>{res.writeHead(status,{"content-type":"application/json"});res.end(JSON.stringify(value));};
  try {
    const body=await new Promise(resolve=>{let s="";req.on("data",v=>s+=v);req.on("end",()=>resolve(s));});
    if(req.url==="/api/auth/login") {const kind=JSON.parse(body).email?.split("@")[0];return sessions[kind]?reply(200,user(kind)):reply(401,{message:"Unknown fixture user"});}
    const kind=req.headers.authorization?.replace("Bearer fixture-","");
    if(!sessions[kind]) return reply(401,{status:401,error:"Unauthorized",message:"Fixture session required"});
    if(req.url==="/api/auth/me") return reply(200,user(kind));
    if(req.url==="/api/auth/logout") return reply(200,{});
    if(!req.url.startsWith("/api/claims")) return reply(404,{message:"No fixture route"});
    const response=await fetch(`http://127.0.0.1:${readFileSync(portFile,"utf8").trim()}${req.url}`,{method:req.method,headers:{"content-type":"application/json","X-User-Id":String(sessions[kind]),"X-User-Roles":"EMPLOYEE"},body:["GET","HEAD"].includes(req.method)?undefined:body});
    // Optional test fault: the backend committed, but the browser lost its confirmation.
    if(failFinalizeOnce && req.method === "PUT" && req.url.endsWith("/finalize") && response.ok) {
      failFinalizeOnce=false;
      await response.text();
      return reply(503,{status:503,error:"Fixture fault",message:"Receipt confirmation interrupted; retry the same upload."});
    }
    res.writeHead(response.status,{"content-type":"application/json"});res.end(await response.text());
  } catch { reply(502,{message:"Disposable fixture unavailable"}); }
}).listen(18087,"127.0.0.1",()=>process.stdout.write("Claim browser identity fixture: 18087\n"));
