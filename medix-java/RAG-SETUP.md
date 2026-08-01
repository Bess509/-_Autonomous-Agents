# DX medical RAG setup

1. Start PostgreSQL with the pgvector extension available. The Flyway migration
   V3__medical_rag.sql creates the medical_rag table and its HNSW index.

2. Pull the local Ollama embedding model:

    ollama pull bge-m3

Ollama manages model loading and uses GPU acceleration when supported by the
local runtime. No Python service is required.

3. Import a JSON array of DX records. Each item must contain an ID plus one of
question/query/instruction and one of answer/response/output:

    $env:MEDIX_RAG_IMPORT_FILE = 'F:\DX.json'
    mvn spring-boot:run

The importer is idempotent on (source, external_id). It stores all DX records
with confidence=high, extracts local Aho-Corasick entity metadata, and embeds
only the question with the 1024-dimensional bge-m3 model through Ollama.

4. For runtime retrieval:

    $env:MEDIX_RAG_ENABLED = 'true'

When enabled, search_knowledge retrieves high-confidence rows from medical_rag
and combines 85% cosine similarity with 15% exact entity-match score. Without
this flag the original bundled Markdown fallback remains active.
