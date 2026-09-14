-- AXMS-AI02-022: 원천 API 주기 점검이 남기는 변경 요약(신규·수정·소멸 건수와 확인 시각).
-- 알림 재료일 뿐이며, 이 값으로 어떤 자동 빌드·활성화도 일어나지 않는다.
ALTER TABLE app.knowledge_base
    ADD COLUMN source_change_summary JSONB;
