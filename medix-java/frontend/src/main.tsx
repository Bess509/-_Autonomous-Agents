import React,{useEffect,useState}from'react'
import{createRoot}from'react-dom/client'
import{initialState,reduceEvent,RunState,streamSse}from'./agui'
import'./style.css'

type User={id:string;username:string;displayName:string;roles:string[]}
type Agent={id:string;capabilities:string[]}
type Thread={id:string;title:string;updatedAt:string}
type AdminUser=User&{agents:string[]}
type Capability={id:string;type:string;display_name:string;enabled:boolean}
type McpServer={id:string;name:string;transport:string;endpoint:string;enabled:boolean}
const ALL_AGENTS=['consultation_agent','diagnostic_agent','research_agent']
const AGENT_NAMES:Record<string,string>={consultation_agent:'健康咨询 Agent',diagnostic_agent:'症状风险 Agent',research_agent:'医学循证 Agent'}
const TOOL_NAMES:Record<string,string>={search_knowledge:'知识库检索',recommend_lifestyle:'生活方式建议',assess_risk:'风险评估',analyze_symptoms:'症状分析',disease_code:'ICD-10 查询',clinical_guideline:'临床指南检索',deep_research:'深度医学研究'}

async function api(path:string,init?:RequestInit){
 const response=await fetch(path,{credentials:'include',...init,headers:{'Content-Type':'application/json',...(init?.headers||{})}})
 if(!response.ok){const body=await response.json().catch(()=>({message:'请求失败'}));throw new Error(body.message||body.code||'请求失败')}
 return response
}

function App(){
 const[user,setUser]=useState<User|null>(null),[error,setError]=useState('')
 useEffect(()=>{api('/api/v1/auth/me').then(r=>r.json()).then(setUser).catch(()=>{})},[])
 if(!user)return <Login onLogin={setUser} error={error} setError={setError}/>
 return <Workspace user={user} logout={async()=>{await api('/api/v1/auth/logout',{method:'POST'});setUser(null)}}/>
}

function Login({onLogin,error,setError}:{onLogin:(user:User)=>void,error:string,setError:(value:string)=>void}){
 async function submit(event:React.FormEvent<HTMLFormElement>){
  event.preventDefault();const form=new FormData(event.currentTarget)
  try{const response=await api('/api/v1/auth/login',{method:'POST',body:JSON.stringify({username:form.get('username'),password:form.get('password')})});onLogin(await response.json())}
  catch{setError('账号或密码错误')}
 }
 return <main className="login"><form onSubmit={submit}><Brand/><p className="eyebrow">可信赖的健康信息工作台</p><h1>欢迎回来</h1><p className="muted">登录后开始安全、可追溯的医疗咨询。</p><label>账号<input name="username" autoComplete="username" required/></label><label>密码<input name="password" type="password" autoComplete="current-password" required/></label>{error&&<p className="error" role="alert">{error}</p>}<button className="primary">安全登录</button><small>本系统不替代医生诊断；紧急情况请立即联系急救。</small></form></main>
}

function Brand(){return <div className="brand"><span className="brand-mark">M</span><span>MediX</span></div>}

function Workspace({user,logout}:{user:User;logout:()=>void}){
 const[agents,setAgents]=useState<Agent[]>([]),[threads,setThreads]=useState<Thread[]>([])
 const[state,setState]=useState<RunState>(initialState),[question,setQuestion]=useState(''),[lastQuestion,setLastQuestion]=useState('')
 const[threadId,setThreadId]=useState<string>(()=>crypto.randomUUID()),[page,setPage]=useState<'chat'|'permissions'|'admin'>('chat')
 const refresh=()=>Promise.all([api('/api/v1/me/agents').then(r=>r.json()).then(setAgents),api('/api/v1/me/threads').then(r=>r.json()).then(setThreads)])
 useEffect(()=>{refresh()},[])
 async function send(event:React.FormEvent){
  event.preventDefault();const prompt=question.trim();if(!prompt||!agents[0]||state.status==='running')return
  const runId=crypto.randomUUID();let current=initialState;setState(current);setLastQuestion(prompt);setQuestion('')
  try{
   const response=await api('/api/v1/agui',{method:'POST',headers:{Accept:'text/event-stream'},body:JSON.stringify({threadId,runId,state:{},messages:[{id:crypto.randomUUID(),role:'user',content:prompt}],tools:[],context:[],forwardedProps:{agentId:agents[0].id}})})
   for await(const item of streamSse(response)){current=reduceEvent(current,item);setState({...current})}
   await refresh()
  }catch(error){setState({...current,status:'error',error:error instanceof Error?error.message:'运行失败'})}
 }
 function newThread(){setThreadId(crypto.randomUUID());setQuestion('');setLastQuestion('');setState(initialState)}
 const activeAgent=agents[0]?.id
 return <div className="shell">
  <header className="topbar"><Brand/><nav aria-label="主导航"><button className={page==='chat'?'active':''} onClick={()=>setPage('chat')}>问诊</button><button className={page==='permissions'?'active':''} onClick={()=>setPage('permissions')}>我的能力</button>{user.roles.includes('ADMIN')&&<button className={page==='admin'?'active':''} onClick={()=>setPage('admin')}>权限与 MCP</button>}</nav><div className="account"><span className="avatar">{user.displayName.slice(0,1)}</span><span>{user.displayName}</span><button onClick={logout}>退出</button></div></header>
  {page==='chat'?<div className="workspace">
   <aside className="sessions"><div className="aside-title"><span>会话记录</span><button className="icon-button" aria-label="新建问诊" title="新建问诊" onClick={newThread}>＋</button></div><button className="new-thread" onClick={newThread}>＋ 新建问诊</button><div className="thread-list">{threads.length?threads.map(item=><button className={item.id===threadId?'thread active':'thread'} key={item.id} onClick={()=>setThreadId(item.id)}><span>{item.title}</span><small>{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</small></button>):<p className="empty-note">还没有历史会话</p>}</div></aside>
   <main className="chat-main"><div className="chat-heading"><div><p className="eyebrow">MEDICAL ASSISTANT</p><h1>医疗咨询</h1></div><span className="agent-pill"><i/> {AGENT_NAMES[activeAgent]??'正在加载 Agent'}</span></div>
    <div className="conversation" aria-live="polite">{!lastQuestion&&!state.text?<Welcome onPick={setQuestion}/>:<><div className="message user-message"><span className="message-label">你</span><p>{lastQuestion}</p></div><div className="message assistant-message"><span className="assistant-icon">M</span><div><span className="message-label">MediX</span>{state.status==='running'&&!state.text?<LoadingAnswer/>:<AssistantAnswerText text={state.text}/>}</div></div></>}{state.error&&<p className="error error-card" role="alert">{state.error}</p>}</div>
    <form className="composer" onSubmit={send}><textarea aria-label="医疗问题" placeholder="描述症状、持续时间，或询问健康知识…" maxLength={4000} value={question} onChange={event=>setQuestion(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();event.currentTarget.form?.requestSubmit()}}}/><div className="composer-footer"><span>Enter 发送 · Shift + Enter 换行</span><button className="send-button" disabled={state.status==='running'||!question.trim()}>{state.status==='running'?'处理中':'发送'}</button></div></form>
   </main>
   <TracePanel state={state}/>
  </div>:page==='permissions'?<main className="panel"><p className="eyebrow">ACCESS</p><h1>我的能力</h1><p className="muted">这里展示当前账户可使用的 Agent 与医疗能力。</p>{agents.map(agent=><section className="permission-card" key={agent.id}><h2>{AGENT_NAMES[agent.id]??agent.id}</h2><div className="tag-list">{agent.capabilities.length?agent.capabilities.map(item=><span key={item}>{TOOL_NAMES[item]??item}</span>):<span>暂无可用能力</span>}</div></section>)}</main>:<Admin/>}
 </div>
}

function Welcome({onPick}:{onPick:(value:string)=>void}){const samples=['我最近总是睡不好，有什么建议？','头痛两天了，需要注意什么？','帮我查一下高血压的健康知识'];return <div className="welcome"><div className="welcome-mark">M</div><p className="eyebrow">你好，我是 MediX</p><h2>今天想了解什么健康问题？</h2><p>我可以协助健康科普、症状风险提示与医学资料检索。</p><div className="suggestions">{samples.map(item=><button key={item} type="button" onClick={()=>onPick(item)}>{item}<span>→</span></button>)}</div></div>}

function LoadingAnswer(){return <div className="loading-answer"><span/><span/><span/><small>正在分析问题并进行安全检查…</small></div>}

export function AssistantAnswerText({text}:{text:string}){return <p data-testid="assistant-answer">{text}</p>}

function TracePanel({state}:{state:RunState}){
 const statusLabel={idle:'等待提问',running:'处理中',finished:'已完成',error:'运行异常'}[state.status]
 return <aside className="trace-panel"><div className="aside-title"><span>处理链路</span><span className={`status ${state.status}`}><i/>{statusLabel}</span></div><p className="trace-hint">展示路由、Agent 与工具记录，不展示模型私有思维。</p><div className="trace-section"><h3>分析步骤</h3>{state.steps.length?<div className="timeline">{state.steps.map(item=><div className="trace-item" key={item.id}><span className={`trace-dot ${item.status}`}>{item.status==='finished'?'✓':'·'}</span><div><strong>{friendlyStep(item.name)}</strong><small>{item.status==='finished'?'已完成':'正在处理'}</small></div></div>)}</div>:<EmptyTrace text="提交问题后显示路由和 Agent"/>}</div><div className="trace-section"><h3>工具调用 <span>{state.tools.length}</span></h3>{state.tools.length?<div className="tool-list">{state.tools.map(item=><div className="tool-card" key={item.id}><span className="tool-icon">⌘</span><div><strong>{TOOL_NAMES[item.name]??item.name}</strong><small>{item.result??(item.status==='finished'?'调用完成':'调用中')}</small></div><em>{item.status==='finished'?'完成':'运行'}</em></div>)}</div>:<EmptyTrace text={state.status==='finished'?'本次回答无需调用工具':'需要检索或评估时会显示'}/>}</div>{state.route&&<div className="route-note"><span>路由依据</span><code>{state.route}</code></div>}</aside>
}

function EmptyTrace({text}:{text:string}){return <div className="empty-trace"><span>◇</span><p>{text}</p></div>}
function friendlyStep(value:string){if(AGENT_NAMES[value])return AGENT_NAMES[value];if(value.startsWith('路由 · '))return '意图路由';return value}

function Admin(){
 const[users,setUsers]=useState<AdminUser[]>([]),[matrix,setMatrix]=useState<Record<string,string[]>>({}),[capabilities,setCapabilities]=useState<Capability[]>([]),[servers,setServers]=useState<McpServer[]>([]),[notice,setNotice]=useState('')
 const load=()=>Promise.all([api('/api/v1/admin/users').then(r=>r.json()).then(setUsers),api('/api/v1/admin/permissions/matrix').then(r=>r.json()).then(setMatrix),api('/api/v1/admin/capabilities').then(r=>r.json()).then(setCapabilities),api('/api/v1/admin/mcp-servers').then(r=>r.json()).then(setServers)])
 useEffect(()=>{load()},[])
 async function toggleUser(user:AdminUser,agent:string){const grant=!user.agents.includes(agent);await api(`/api/v1/admin/users/${user.id}/agents/${agent}`,{method:grant?'PUT':'DELETE'});await load()}
 async function toggleCapability(agent:string,capability:string){const grant=!(matrix[agent]||[]).includes(capability);await api(`/api/v1/admin/agents/${agent}/capabilities/${capability}`,{method:grant?'PUT':'DELETE'});await load()}
 async function register(event:React.FormEvent<HTMLFormElement>){event.preventDefault();const form=new FormData(event.currentTarget);try{await api('/api/v1/admin/mcp-servers',{method:'POST',body:JSON.stringify({name:form.get('name'),transport:form.get('transport'),endpoint:form.get('endpoint')})});setNotice('MCP Server 已登记，默认保持停用');event.currentTarget.reset();await load()}catch(error){setNotice(error instanceof Error?error.message:'登记失败')}}
 return <main className="panel wide"><p className="eyebrow">ADMINISTRATION</p><h1>权限管理与 MCP</h1><p className="muted">所有授权默认拒绝，数据库授权不能突破医疗安全边界。</p><h2>用户 → Agent</h2><div className="matrix">{users.map(user=><section key={user.id}><strong>{user.displayName}</strong><small>{user.username}</small>{ALL_AGENTS.map(agent=><label key={agent}><input type="checkbox" checked={user.agents.includes(agent)} onChange={()=>toggleUser(user,agent)}/>{AGENT_NAMES[agent]}</label>)}</section>)}</div><h2>Agent → Skill / MCP</h2><div className="matrix">{ALL_AGENTS.map(agent=><section key={agent}><strong>{AGENT_NAMES[agent]}</strong>{capabilities.map(capability=><label key={capability.id}><input type="checkbox" checked={(matrix[agent]||[]).includes(capability.id)} onChange={()=>toggleCapability(agent,capability.id)}/>{capability.display_name} <small>{capability.type}</small></label>)}</section>)}</div><h2>MCP Servers</h2><form className="mcp-form" onSubmit={register}><input name="name" aria-label="MCP名称" placeholder="名称" required/><select name="transport" aria-label="MCP传输"><option>STREAMABLE_HTTP</option><option>SSE</option></select><input name="endpoint" type="url" aria-label="MCP端点" placeholder="https://mcp.example.com" required/><button className="primary">安全登记</button></form>{notice&&<p role="status">{notice}</p>}{servers.map(server=><section key={server.id}><strong>{server.name}</strong><p>{server.transport} · {server.endpoint} · {server.enabled?'启用':'停用'}</p></section>)}</main>
}

const rootElement=document.getElementById('root')
if(rootElement)createRoot(rootElement).render(<App/>)
