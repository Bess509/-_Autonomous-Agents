CREATE TABLE conversation_messages (
    id BIGSERIAL PRIMARY KEY,
    thread_id VARCHAR(120) NOT NULL REFERENCES conversation_threads(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_messages_thread_created ON conversation_messages(thread_id, created_at DESC);

INSERT INTO capabilities(id, type, display_name) VALUES
    ('safe_medical_guidance', 'SKILL', '安全医疗咨询引导')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_capability_grants(agent_id, capability_id, action) VALUES
    ('consultation_agent', 'safe_medical_guidance', 'EXECUTE'),
    ('diagnostic_agent', 'safe_medical_guidance', 'EXECUTE')
ON CONFLICT (agent_id, capability_id, action) DO NOTHING;
