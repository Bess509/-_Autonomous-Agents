CREATE TABLE app_users (
    id UUID PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, role VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, role)
);
CREATE TABLE agent_definitions (
    id VARCHAR(80) PRIMARY KEY, display_name VARCHAR(120) NOT NULL, description TEXT NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE, risk_level VARCHAR(30) NOT NULL DEFAULT 'STANDARD'
);
CREATE TABLE mcp_servers (
    id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, transport VARCHAR(30) NOT NULL,
    endpoint TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE, config_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE capabilities (
    id VARCHAR(120) PRIMARY KEY, type VARCHAR(30) NOT NULL CHECK (type IN ('SKILL','MCP_TOOL','MCP_RESOURCE')),
    mcp_server_id UUID REFERENCES mcp_servers(id), display_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE, metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE user_agent_grants (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    agent_id VARCHAR(80) NOT NULL REFERENCES agent_definitions(id) ON DELETE CASCADE,
    action VARCHAR(20) NOT NULL DEFAULT 'USE', granted_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (user_id, agent_id, action)
);
CREATE TABLE agent_capability_grants (
    agent_id VARCHAR(80) NOT NULL REFERENCES agent_definitions(id) ON DELETE CASCADE,
    capability_id VARCHAR(120) NOT NULL REFERENCES capabilities(id) ON DELETE CASCADE,
    action VARCHAR(20) NOT NULL, granted_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (agent_id, capability_id, action)
);
CREATE TABLE conversation_threads (
    id VARCHAR(120) PRIMARY KEY, owner_user_id UUID NOT NULL REFERENCES app_users(id), title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE agent_runs (
    run_id VARCHAR(120) PRIMARY KEY, thread_id VARCHAR(120) NOT NULL REFERENCES conversation_threads(id),
    user_id UUID NOT NULL REFERENCES app_users(id), status VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ, result JSONB
);
CREATE TABLE permission_audit_logs (
    id BIGSERIAL PRIMARY KEY, actor_user_id UUID REFERENCES app_users(id), action VARCHAR(80) NOT NULL,
    subject_type VARCHAR(40), subject_id VARCHAR(120), resource_type VARCHAR(40), resource_id VARCHAR(120),
    decision VARCHAR(20) NOT NULL, reason VARCHAR(120) NOT NULL, run_id VARCHAR(120), thread_id VARCHAR(120),
    ip_address VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON permission_audit_logs(created_at DESC);

INSERT INTO agent_definitions(id, display_name, description, risk_level) VALUES
 ('consultation_agent','问诊助手','健康咨询与生活方式建议','STANDARD'),
 ('diagnostic_agent','风险分析助手','症状风险分层，不作最终诊断','HIGH'),
 ('research_agent','医学研究助手','指南与证据检索','STANDARD');
INSERT INTO capabilities(id,type,display_name) VALUES
 ('search_knowledge','SKILL','知识检索'),('recommend_lifestyle','SKILL','生活方式建议'),
 ('assess_risk','SKILL','风险评估'),('analyze_symptoms','SKILL','症状分析'),
 ('disease_code','SKILL','ICD-10 编码'),('clinical_guideline','SKILL','临床指南'),
 ('deep_research','SKILL','深度研究');
INSERT INTO agent_capability_grants(agent_id,capability_id,action) VALUES
 ('consultation_agent','search_knowledge','EXECUTE'),('consultation_agent','recommend_lifestyle','EXECUTE'),
 ('consultation_agent','assess_risk','EXECUTE'),('diagnostic_agent','assess_risk','EXECUTE'),
 ('diagnostic_agent','analyze_symptoms','EXECUTE'),('diagnostic_agent','disease_code','EXECUTE'),
 ('research_agent','clinical_guideline','EXECUTE'),('research_agent','deep_research','EXECUTE');
