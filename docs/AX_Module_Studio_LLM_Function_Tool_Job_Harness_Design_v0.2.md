# AX Module Studio Spring–LangGraph Tool·Job Harness Design v0.2

> Authority scope: Backend/Orchestrator Tool·Job 기술 설계의 역사적 근거다. 현재 제품 역할, 일반 Approval/Audit 범위, Wave/Slice, Git 및 승인 정책은 workspace sibling `urizo-final-master`만 소유하며 이 문서의 Stage 0 표현이 이를 변경하지 않는다.
>
> 상태: Stage 0 Contract 기준  
> 변경: FastAPI Control Plane을 Spring Control Plane·Model Gateway·Tool Gateway로 대체

## 1. 책임 경계

```text
Spring Control Plane
  Public API·Auth·Role·Project·Job·Approval·Audit

Spring AI Model Gateway
  Provider Secret·Model Mapping·Chat/Embedding·Usage·Error 정규화

Python LangGraph Coding Runtime
  Coding Graph·Checkpoint·Interrupt·Resume·Node Routing

Spring Tool Gateway
  Tool Allowlist·Path·Hash·State·Approval·Idempotency 재검증

Isolated Tool Executor
  승인 범위 안의 Read·Patch·Test·Preview
```

Python은 Public API, Core DB DDL, Provider Key, Git Token, Docker Socket, 최종 승인 권한을 가지지 않는다.

## 2. 계약 원본

Backend Repository가 소유한다.

```text
contracts/public/openapi.yaml
contracts/coding-agent/job-event.schema.json
contracts/coding-agent/model-turn.openapi.yaml
contracts/coding-agent/tool-request.schema.json
contracts/coding-agent/error-code.schema.json
```

모든 Request·Event에 `schemaVersion`, `jobId`, `traceId`, `idempotencyKey`를 둔다. Java DTO와 Python Model은 생성 또는 Golden Contract Test로 동기화한다.

## 3. Coding 흐름

```text
Spring Job·Outbox Commit
→ Queue Start Event
→ LangGraph Node
→ Spring CodingModelGateway
→ Tool 후보 요청
→ Spring Tool Gateway 재검증
→ Isolated Execution
→ Result Event
→ 승인 지점 Interrupt
→ Spring Approval 확인
→ 동일 thread_id Resume
```

LLM Tool Call은 실행 명령이 아니라 후보 요청이다.

## 4. Tool Request 필수 Context

- Actor·Project·Role
- Job State·Expected State Version
- Repository·Allowed Path
- Candidate SHA·Context Digest·Policy Hash
- Tool Name·Argument Schema
- Approval ID·범위·만료
- Idempotency Key

검증 실패는 실행하지 않고 안정된 문자열 Error Code로 반환한다.

## 5. 멱등성

```text
jobId + graphStep + candidateSha + toolName + attemptScope
```

Interrupt·Retry 후 Node가 재실행돼도 동일 Side Effect는 한 번만 반영한다. 긴 Test·Build는 `202 + executionId`로 비동기화하며 DB Row Lock을 실행 시간 동안 유지하지 않는다.

## 6. Secret·Trace

- Provider Secret은 Spring 밖으로 전달하지 않는다.
- Python은 회전 가능한 Spring Service Credential과 Checkpoint DB Credential만 가진다.
- Prompt·Completion·Tool Argument·Result 원문은 기본 Trace에서 제외한다.
- 공통 Trace에는 `traceId`, `jobId`, Provider, Model, Token, Latency, State, Error Code만 기록한다.
- LangSmith는 선택형이며 미설정이 P0 Health를 실패시키지 않는다.

## 7. Stage 0 필수 Test

- Java와 Python의 동일 Golden Payload Read·Write
- Unknown Schema Version 거부
- Unknown Field 정책 검증
- Provider Key가 Python 환경에 없음
- 승인 없는 Tool Side Effect 0건
- Path Traversal·Repository Escape 거부
- Resume·Duplicate Event의 Side Effect 중복 0건
- Java·Python `traceId/jobId` 연결

이 Contract Test가 통과하기 전에는 Coding Agent Tool 구현을 확장하지 않는다.
