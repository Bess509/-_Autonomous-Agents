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
type ChatMessage={id:string;role:'user'|'assistant';content:string}
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
 if(!user)return <AuthScreen onLogin={setUser} error={error} setError={setError}/>
 return <Workspace user={user} logout={async()=>{try{await api('/api/v1/auth/logout',{method:'POST'})}catch{}setUser(null)}}/>
}

function AuthScreen({onLogin,error,setError}:{onLogin:(user:User)=>void,error:string,setError:(value:string)=>void}){
 const[mode,setMode]=useState<'login'|'register'>('login')
 async function submit(event:React.FormEvent<HTMLFormElement>){
  event.preventDefault();const form=new FormData(event.currentTarget)
  const password=String(form.get('password')??'')
  if(mode==='register'&&password!==String(form.get('confirmPassword')??'')){setError('两次输入的密码不一致');return}
  try{const response=await api(`/api/v1/auth/${mode==='login'?'login':'register'}`,{method:'POST',body:JSON.stringify({username:form.get('username'),password,displayName:form.get('displayName')})});onLogin(await response.json())}
  catch(reason){setError(reason instanceof Error?reason.message:'请求失败')}
 }
 function switchMode(next:'login'|'register'){setMode(next);setError('')}
 return <main className="login"><form onSubmit={submit}><Brand/><p className="eyebrow">可信赖的健康信息工作台</p><h1>{mode==='login'?'欢迎回来':'创建账户'}</h1><p className="muted">{mode==='login'?'登录后开始安全、可追溯的医疗咨询。':'注册后即可使用健康咨询 Agent。'}</p>{mode==='register'&&<label>显示名称<input name="displayName" autoComplete="name" minLength={2} maxLength={50} required/></label>}<label>账号<input name="username" autoComplete="username" pattern="[a-z0-9_]{3,40}" title="3–40 位小写字母、数字或下划线" required/></label><label>密码<input name="password" type="password" autoComplete={mode==='login'?'current-password':'new-password'} minLength={mode==='register'?8:undefined} required/></label>{mode==='register'&&<><label>确认密码<input name="confirmPassword" type="password" autoComplete="new-password" minLength={8} required/></label><small className="password-hint">密码需为 8–72 位，并同时包含字母和数字。</small></>}{error&&<p className="error" role="alert">{error}</p>}<button className="primary">{mode==='login'?'安全登录':'注册并登录'}</button><button className="auth-switch" type="button" onClick={()=>switchMode(mode==='login'?'register':'login')}>{mode==='login'?'没有账户？立即注册':'已有账户？返回登录'}</button><small>本系统不替代医生诊断；紧急情况请立即联系急救。</small></form></main>
}

function Brand(){return <div className="brand"><span className="brand-mark">M</span><span>MediX</span></div>}

function Workspace({user,logout}:{user:User;logout:()=>void}){
 const[agents,setAgents]=useState<Agent[]>([]),[threads,setThreads]=useState<Thread[]>([])
 const[state,setState]=useState<RunState>(initialState),[question,setQuestion]=useState(''),[messages,setMessages]=useState<ChatMessage[]>([])
 const[threadId,setThreadId]=useState<string>(()=>crypto.randomUUID()),[page,setPage]=useState<'chat'|'permissions'|'account'|'admin'>('chat')
 const[managing,setManaging]=useState(false),[selectedThreads,setSelectedThreads]=useState<Set<string>>(new Set()),[threadNotice,setThreadNotice]=useState('')
 const refresh=()=>Promise.all([api('/api/v1/me/agents').then(r=>r.json()).then(setAgents),api('/api/v1/me/threads').then(r=>r.json()).then(setThreads)])
 useEffect(()=>{refresh()},[])
 useEffect(()=>{
  if(!threads.some(item=>item.id===threadId))return
  api(`/api/v1/me/threads/${encodeURIComponent(threadId)}/messages`).then(response=>response.json())
   .then(stored=>setMessages(stored.map((message:{id:number;role:'user'|'assistant';content:string})=>({id:String(message.id),role:message.role,content:message.content}))))
   .catch(()=>setMessages([]))
 },[threadId,threads])
 async function send(event:React.FormEvent){
  event.preventDefault();const prompt=question.trim();if(!prompt||!agents[0]||state.status==='running')return
  const runId=crypto.randomUUID(),assistantMessageId=crypto.randomUUID();let current=initialState;setState(current);setMessages(items=>[...items,{id:crypto.randomUUID(),role:'user',content:prompt},{id:assistantMessageId,role:'assistant',content:''}]);setQuestion('')
  try{
   const response=await api('/api/v1/agui',{method:'POST',headers:{Accept:'text/event-stream'},body:JSON.stringify({threadId,runId,state:{},messages:[{id:crypto.randomUUID(),role:'user',content:prompt}],tools:[],context:[],forwardedProps:{agentId:agents[0].id}})})
   for await(const item of streamSse(response)){current=reduceEvent(current,item);setState({...current});setMessages(items=>items.map(message=>message.id===assistantMessageId?{...message,content:current.text}:message))}
   await refresh()
  }catch(error){setState({...current,status:'error',error:error instanceof Error?error.message:'运行失败'})}
 }
 function newThread(){setThreadId(crypto.randomUUID());setQuestion('');setMessages([]);setState(initialState)}
 async function selectThread(id:string){
  setThreadId(id);setQuestion('');setState(initialState)
  try{const stored=await api(`/api/v1/me/threads/${encodeURIComponent(id)}/messages`).then(response=>response.json());setMessages(stored.map((message:{id:number;role:'user'|'assistant';content:string})=>({id:String(message.id),role:message.role,content:message.content})))}
  catch(reason){setMessages([]);setThreadNotice(reason instanceof Error?reason.message:'加载会话失败')}
 }
 function toggleThread(id:string){setSelectedThreads(current=>{const next=new Set(current);next.has(id)?next.delete(id):next.add(id);return next})}
 async function removeThreads(ids:string[]){
  if(!ids.length||!window.confirm(`确定删除 ${ids.length} 个会话及其全部运行记录吗？此操作不可撤销。`))return
  try{const path=ids.length===1?`/api/v1/me/threads/${encodeURIComponent(ids[0])}`:'/api/v1/me/threads';await api(path,{method:'DELETE',body:ids.length===1?undefined:JSON.stringify({threadIds:ids})});setThreads(items=>items.filter(item=>!ids.includes(item.id)));setSelectedThreads(new Set());setThreadNotice(`已删除 ${ids.length} 个会话`);if(ids.includes(threadId))newThread()}
  catch(reason){setThreadNotice(reason instanceof Error?reason.message:'删除失败')}
 }
 const activeAgent=agents[0]?.id
 return <div className="shell">
  <header className="topbar"><Brand/><nav aria-label="主导航"><button className={page==='chat'?'active':''} onClick={()=>setPage('chat')}>问诊</button><button className={page==='permissions'?'active':''} onClick={()=>setPage('permissions')}>我的能力</button><button className={page==='account'?'active':''} onClick={()=>setPage('account')}>账户设置</button>{user.roles.includes('ADMIN')&&<button className={page==='admin'?'active':''} onClick={()=>setPage('admin')}>权限与 MCP</button>}</nav><div className="account"><span className="avatar">{user.displayName.slice(0,1)}</span><span>{user.displayName}</span><button onClick={logout}>退出</button></div></header>
  {page==='chat'?<div className="workspace">
   <aside className="sessions"><div className="aside-title"><span>会话记录</span><button className="manage-button" onClick={()=>{setManaging(value=>!value);setSelectedThreads(new Set());setThreadNotice('')}}>{managing?'完成':'管理'}</button></div><button className="new-thread" onClick={newThread}>＋ 新建问诊</button>{managing&&threads.length>0&&<div className="thread-actions"><button onClick={()=>setSelectedThreads(selectedThreads.size===threads.length?new Set():new Set(threads.map(item=>item.id)))}>{selectedThreads.size===threads.length?'取消全选':'全选'}</button><button className="danger" disabled={!selectedThreads.size} onClick={()=>removeThreads([...selectedThreads])}>删除所选 ({selectedThreads.size})</button></div>}{threadNotice&&<p className="thread-notice" role="status">{threadNotice}</p>}<div className="thread-list">{threads.length?threads.map(item=><div className="thread-row" key={item.id}>{managing&&<input aria-label={`选择会话 ${item.title}`} type="checkbox" checked={selectedThreads.has(item.id)} onChange={()=>toggleThread(item.id)}/>}<button className={item.id===threadId?'thread active':'thread'} onClick={()=>setThreadId(item.id)}><span>{item.title}</span><small>{new Date(item.updatedAt).toLocaleDateString('zh-CN')}</small></button>{managing&&<button className="thread-delete" aria-label={`删除会话 ${item.title}`} title="删除此会话" onClick={()=>removeThreads([item.id])}>×</button>}</div>):<p className="empty-note">还没有历史会话</p>}</div></aside>
   <main className="chat-main"><div className="chat-heading"><div><p className="eyebrow">MEDICAL ASSISTANT</p><h1>医疗咨询</h1></div><span className="agent-pill"><i/> {AGENT_NAMES[activeAgent]??'正在加载 Agent'}</span></div>
    <div className="conversation" aria-live="polite">{!messages.length?<Welcome onPick={setQuestion}/>:messages.map(message=>message.role==='user'?<div className="message user-message" key={message.id}><span className="message-label">你</span><p>{message.content}</p></div>:<div className="message assistant-message" key={message.id}><span className="assistant-icon">M</span><div><span className="message-label">MediX</span>{state.status==='running'&&message===messages.at(-1)&&state.thinkingStatus&&<ThinkingPanel text={state.thinking} status={state.thinkingStatus}/>} {state.status==='running'&&message===messages.at(-1)&&!message.content?<LoadingAnswer/>:<AssistantAnswerText text={message.content}/>}</div></div>)}{state.error&&<p className="error error-card" role="alert">{state.error}</p>}</div>
    <form className="composer" onSubmit={send}><textarea aria-label="医疗问题" placeholder="描述症状、持续时间，或询问健康知识…" maxLength={4000} value={question} onChange={event=>setQuestion(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();event.currentTarget.form?.requestSubmit()}}}/><div className="composer-footer"><span>Enter 发送 · Shift + Enter 换行</span><button className="send-button" disabled={state.status==='running'||!question.trim()}>{state.status==='running'?'处理中':'发送'}</button></div></form>
   </main>
   <TracePanel state={state}/>
  </div>:page==='permissions'?<main className="panel"><p className="eyebrow">ACCESS</p><h1>我的能力</h1><p className="muted">这里展示当前账户可使用的 Agent 与医疗能力。</p>{agents.map(agent=><section className="permission-card" key={agent.id}><h2>{AGENT_NAMES[agent.id]??agent.id}</h2><div className="tag-list">{agent.capabilities.length?agent.capabilities.map(item=><span key={item}>{TOOL_NAMES[item]??item}</span>):<span>暂无可用能力</span>}</div></section>)}</main>:page==='account'?<AccountSettings user={user} onPasswordChanged={logout}/>:<Admin/>}
 </div>
}

function Welcome({onPick}:{onPick:(value:string)=>void}){const samples=['我最近总是睡不好，有什么建议？','头痛两天了，需要注意什么？','帮我查一下高血压的健康知识'];return <div className="welcome"><div className="welcome-mark">M</div><p className="eyebrow">你好，我是 MediX</p><h2>今天想了解什么健康问题？</h2><p>我可以协助健康科普、症状风险提示与医学资料检索。</p><div className="suggestions">{samples.map(item=><button key={item} type="button" onClick={()=>onPick(item)}>{item}<span>→</span></button>)}</div></div>}

function LoadingAnswer(){return <div className="loading-answer"><span/><span/><span/><small>正在分析问题并进行安全检查…</small></div>}

export function AssistantAnswerText({text}:{text:string}){return <p data-testid="assistant-answer">{text}</p>}

function ThinkingPanel({text,status}:{text:string;status:'running'|'finished'}){
 return <details className="thinking-panel" open={status==='running'}><summary><span className={`thinking-dot ${status}`}/>{status==='running'?'DeepSeek 正在思考':'DeepSeek 思考完成'}</summary><div data-testid="thinking-content">{text||'正在生成分析…'}</div></details>
}

function TracePanel({state}:{state:RunState}){
 const statusLabel={idle:'等待提问',running:'处理中',finished:'已完成',error:'运行异常'}[state.status]
 return <aside className="trace-panel"><div className="aside-title"><span>处理链路</span><span className={`status ${state.status}`}><i/>{statusLabel}</span></div><p className="trace-hint">实时展示 DeepSeek 返回的思考内容、路由、Agent 与工具记录。</p><div className="trace-section"><h3>分析步骤</h3>{state.steps.length?<div className="timeline">{state.steps.map(item=><div className="trace-item" key={item.id}><span className={`trace-dot ${item.status}`}>{item.status==='finished'?'✓':'·'}</span><div><strong>{friendlyStep(item.name)}</strong><small>{item.status==='finished'?'已完成':'正在处理'}</small></div></div>)}</div>:<EmptyTrace text="提交问题后显示路由和 Agent"/>}</div><div className="trace-section"><h3>工具调用 <span>{state.tools.length}</span></h3>{state.tools.length?<div className="tool-list">{state.tools.map(item=><div className="tool-card" key={item.id}><span className="tool-icon">⌘</span><div><strong>{TOOL_NAMES[item.name]??item.name}</strong><small>{item.result??(item.status==='finished'?'调用完成':'调用中')}</small></div><em>{item.status==='finished'?'完成':'运行'}</em></div>)}</div>:<EmptyTrace text={state.status==='finished'?'本次回答无需调用工具':'需要检索或评估时会显示'}/>}</div>{state.route&&<div className="route-note"><span>路由依据</span><code>{state.route}</code></div>}</aside>
}

function EmptyTrace({text}:{text:string}){return <div className="empty-trace"><span>◇</span><p>{text}</p></div>}
function friendlyStep(value:string){if(AGENT_NAMES[value])return AGENT_NAMES[value];if(value.startsWith('路由 · '))return '意图路由';return value}

function AccountSettings({user,onPasswordChanged}:{user:User;onPasswordChanged:()=>void}){
 const[notice,setNotice]=useState('')
 async function change(event:React.FormEvent<HTMLFormElement>){
  event.preventDefault();const form=new FormData(event.currentTarget),currentPassword=String(form.get('currentPassword')??''),newPassword=String(form.get('newPassword')??''),confirmPassword=String(form.get('confirmPassword')??'')
  if(newPassword!==confirmPassword){setNotice('两次输入的新密码不一致');return}
  try{await api('/api/v1/auth/password',{method:'PUT',body:JSON.stringify({currentPassword,newPassword})});setNotice('密码已修改，即将返回登录页…');window.setTimeout(onPasswordChanged,900)}
  catch(reason){setNotice(reason instanceof Error?reason.message:'修改失败')}
 }
 return <main className="panel account-panel"><p className="eyebrow">ACCOUNT</p><h1>账户设置</h1><section className="profile-card"><span className="avatar large">{user.displayName.slice(0,1)}</span><div><strong>{user.displayName}</strong><small>@{user.username}</small></div></section><form className="password-form" onSubmit={change}><h2>修改密码</h2><p className="muted">修改成功后需要使用新密码重新登录。</p><label>当前密码<input name="currentPassword" type="password" autoComplete="current-password" required/></label><label>新密码<input name="newPassword" type="password" autoComplete="new-password" minLength={8} required/></label><label>确认新密码<input name="confirmPassword" type="password" autoComplete="new-password" minLength={8} required/></label><small>密码需为 8–72 位，并同时包含字母和数字。</small>{notice&&<p className="form-notice" role="status">{notice}</p>}<button className="primary">保存新密码</button></form></main>
}

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
