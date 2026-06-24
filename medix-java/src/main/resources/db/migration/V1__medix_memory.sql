create extension if not exists vector;

create table if not exists conversation_summaries (
    id bigserial primary key,
    session_id varchar(128) not null,
    question text not null,
    answer text not null,
    summary text not null,
    embedding vector(384) not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_conversation_summaries_session
    on conversation_summaries(session_id);

create index if not exists idx_conversation_summaries_embedding
    on conversation_summaries
    using ivfflat (embedding vector_cosine_ops)
    with (lists = 32);
