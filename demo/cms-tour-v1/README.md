# CMS 여행 데모 v1

팀원 LLM은 [최종 적용 안내](../../docs/CMS_DEMO_TEAM_HANDOFF.md)를 먼저 읽는다.

`manifest.json`은 메뉴 15개, 콘텐츠 8개, 게시판 3개, 샘플 게시글 90개,
코드 그룹 4개/코드 17개, 이미지 3개, 템플릿 1개, 사이트 1개의 업무 필드만 포함한다.
이미지는 AI로 생성한 가상 시연 자료이며 실제 여행 장소의 사진이라고 표시하지 않는다.

작성자·원본 ID·개인 테스트 자료·AI 설정/Job/이력·RAG/임베딩·계정·Secret은 배포하지 않는다.
DDL/Flyway seed가 아니다. 기본 dry-run 후 대상 PC 사용자가 승인한 계획만 기존 CMS API로 반영한다.

```powershell
python scripts/cms-demo/import_demo.py
```

이 파일 묶음은 고정된 v1 패키지다. 적용된 패키지의 manifest/이미지와 journal을 임의 수정하지 않는다.
다음 데이터 배포는 새 패키지 ID와 별도 검증·승인으로 진행한다.
