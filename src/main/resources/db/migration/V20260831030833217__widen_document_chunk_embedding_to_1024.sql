-- app.document_chunk.embedding: vector(32) -> vector(1024)
-- 32차원은 해시 픽스처 임베딩 전용 폭이다. 실제 임베딩 모델 출력 폭으로 넓힌다.
-- 기존 값은 재임베딩 대상이므로 보존하지 않는다. 컬럼은 nullable이고 검색 경로가
-- embedding IS NOT NULL로 걸러내므로, 재빌드 전까지 해당 Version은 결과를 반환하지 않는다.

DROP INDEX IF EXISTS app.idx_document_chunk_embedding_hnsw;

ALTER TABLE app.document_chunk
    ALTER COLUMN embedding TYPE vector(1024) USING NULL::vector(1024);

CREATE INDEX idx_document_chunk_embedding_hnsw
    ON app.document_chunk USING hnsw (embedding vector_cosine_ops);
