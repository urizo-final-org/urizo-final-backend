-- AXMS-AI02-020: 지식베이스당 한 번 확정·동결되는 골든 질문 평가셋.
-- 버전 비교는 같은 시험지로만 성립하므로 knowledge_version이 아니라 knowledge_base에 둔다.
-- status가 CONFIRMED인 세트만 빌드 평가가 읽는다. DRAFT는 검수 대기 상태다.
ALTER TABLE app.knowledge_base
    ADD COLUMN evaluation_question_set JSONB;
