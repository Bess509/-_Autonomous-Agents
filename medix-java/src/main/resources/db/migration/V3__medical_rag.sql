create table if not exists medical_rag (
    id bigserial primary key,
    external_id text not null,
    question text not null,
    answer text not null,
    confidence varchar(20) not null default 'high',
    source varchar(30) not null default 'DX',
    metadata jsonb not null default '{}'::jsonb,
    embedding vector(1024) not null,
    content_hash char(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_medical_rag_source_external unique (source, external_id)
);

create index if not exists idx_medical_rag_embedding
    on medical_rag using hnsw (embedding vector_cosine_ops);
create index if not exists idx_medical_rag_confidence on medical_rag(confidence);
create index if not exists idx_medical_rag_metadata on medical_rag using gin(metadata);
