# bge-m3 임베딩 서버

RAG의 EMBED 단계와 검색 질의 임베딩을 담당한다. Backend가
`AXMS_EMBEDDING_BASE_URL`로 이 서버를 호출한다.

**현재 Compose에 포함되지 않는다.** 호스트에서 직접 띄우고 컨테이너가
`host.docker.internal`로 닿는다. Compose 편입은 모델 2.3GB 전달 방식이 정해진 뒤
별도로 검토한다.

RAG 전체 실행 순서는 [`../docs/RAG_LOCAL_SETUP.md`](../docs/RAG_LOCAL_SETUP.md)에 있다.
이 문서는 이 서버 하나만 다룬다.

## 바꾸면 안 되는 것

`rag/embed_bge.py`의 모델명·revision·`normalize_embeddings`·pooling·프리픽스·
`batch_size`는 **DB에 저장된 v8 문서 벡터 500건을 만든 값**이다. 하나라도 바꾸면
기존 벡터와 새 질의 벡터가 다른 공간에 놓여 검색 결과가 조용히 무너진다.

이 디렉터리의 두 파일은 실험 작업실에서 **바이트 단위로 동일하게** 옮겨 왔다.

## 필요 사양

| 항목 | 값 |
|---|---|
| Python | **3.11** (검증: 3.11.15) |
| 디스크 | 모델 약 2.3 GB + 가상환경 약 2.5 GB → **여유 5 GB 권장** |
| 메모리 | 모델 상주 약 2.5 GB. 최소 8 GB RAM |
| GPU | **불필요.** CPU 전용 구성이다 |
| 최초 기동 | 모델 로드 약 8초 (다운로드는 별도) |

## 1. 모델 받기

`rag/embed_bge.py`는 `SentenceTransformer("BAAI/bge-m3")`를 revision 없이 부른다.
즉 그때그때의 `main`을 따라간다. **캐시를 먼저 고정 revision으로 채워 두면** 코드를
고치지 않고도 재현성이 생긴다.

```bash
pip install "huggingface_hub[cli]"
hf download BAAI/bge-m3 --revision 5617a9f61b028005a4858fdac845db406aefb181
```

`5617a9f61b028005a4858fdac845db406aefb181`이 **현재 기준선 벡터를 만든 revision**이다.

- 받는 용량 약 2.3 GB (`pytorch_model.bin` 2.27 GB 등)
- 이 revision에는 `model.safetensors`가 없다. sentence-transformers가 safetensors를
  먼저 찾다가 404를 받고 `.bin`으로 넘어가는 것은 **정상 동작**이다
- 캐시 위치: Windows `C:\Users\<사용자>\.cache\huggingface`. `HF_HOME`으로 옮길 수 있다

받은 뒤 `HF_HUB_OFFLINE=1`을 걸면 `main`이 움직여도 캐시의 이 revision을 쓴다.

## 2. 의존성 설치

이 디렉터리에서 실행한다.

```bash
python -m venv venv
uv pip install --python venv/Scripts/python.exe torch==2.13.0+cpu --index-url https://download.pytorch.org/whl/cpu
uv pip install --python venv/Scripts/python.exe -r requirements.txt
```

torch를 먼저 CPU 인덱스에서 받는 이유는 PyPI 기본 인덱스에 `+cpu` 빌드가 없어서다.

## 3. 실행

**이 디렉터리(`embedding-server/`)에서** 실행한다. `serve/embed_api.py`가 상위 경로를
`sys.path`에 넣으므로 여기서 띄워야 `rag.embed_bge`를 찾는다.

```bash
venv/Scripts/python.exe -m uvicorn serve.embed_api:app --host 0.0.0.0 --port 8900 --workers 1
```

- **`--workers 1` 고정.** 워커마다 모델을 따로 올려 메모리가 배수로 늘어난다
- 모델은 요청마다가 아니라 startup에서 1회 로드한다
- 포트를 바꾸면 Backend의 `AXMS_EMBEDDING_BASE_URL`도 함께 바꿔야 한다

## 4. 확인

```bash
curl http://localhost:8900/health
```

정상 응답:

```json
{"status":"ok","model_loaded":true,"model":"BAAI/bge-m3","dim":1024}
```

`model_loaded`가 `false`면 아직 로드 중이다. 벡터까지 보려면:

```bash
curl -X POST http://localhost:8900/embed/query -H "Content-Type: application/json" -d "{\"text\":\"전주 한옥마을\"}"
```

`dim`이 1024이고 `embedding` 길이가 1024면 정상이다.

> **이 확인이 보증하지 않는 것**: 서버가 응답한다는 것까지만 확인된다. 출력 벡터가
> 기준선과 바이트 단위로 같은지는 검증하지 못한다 — 그 대조에는 실험 작업실의
> `build/vec.npy`(기준선 실물)가 필요한데, 실험 자료라 이 저장소에 포함하지 않았다.
> 실질적인 보증은 **고정 revision + `rag/embed_bge.py` 무수정**에서 나온다.

## 엔드포인트

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| GET | `/health` | — | `status` · `model_loaded` · `model` · `dim` |
| POST | `/embed/query` | `{"text": "..."}` | `embedding[1024]` · `dim` · `elapsed_ms` |
| POST | `/embed/batch` | `{"items":[{"id","text"}]}` | `embeddings[{id,embedding}]` · `dim` · `count` |

`/embed/batch`는 요청의 `id`를 그대로 돌려준다. **순서가 아니라 `id`로 매칭할 것.**

## 함정

| 증상 | 원인 · 조치 |
|---|---|
| 질의가 전부 `SERVICE_NOT_READY` | 서버가 죽어 있다. Backend가 원인을 감추므로 `/health`부터 본다 |
| 500건 한 번에 임베딩 → read timeout | Backend가 **48건씩** 나눠 부른다. 이 값을 늘리지 말 것 |
| `rag.embed_bge` ImportError | `embedding-server/`가 아닌 곳에서 기동했다 |
| 벡터가 미묘하게 다름 | `normalize_embeddings`·`batch_size`·모델명을 건드렸는지 확인. 기준선이 무효가 된다 |
