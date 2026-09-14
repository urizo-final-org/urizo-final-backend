"""bge-m3 임베딩 서빙 API (S1).

W2(Spring Backend)가 색인·질의 양쪽에서 호출한다. 임베딩 로직은 직접 구현하지 않고
`rag.embed_bge`만 사용한다 — 그 모듈이 `build/vec.npy`(R@5 0.975 기준선)를 만든 호출을
값 변경 없이 담고 있으므로, 여기서 모델·normalize·batch_size를 바꾸면 기준선이 무효가 된다.

문서와 쿼리는 batch_size만 다르고 모델·normalize·pooling·프리픽스가 동일하다.
따라서 두 엔드포인트가 같은 함수를 쓰되 원본 스크립트의 batch_size만 각각 따른다.

실행:
    venv\\Scripts\\python.exe -m uvicorn serve.embed_api:app --host 0.0.0.0 --port 8900 --workers 1
"""

from __future__ import annotations

import logging
import sys
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from rag.embed_bge import (  # noqa: E402
    DIM,
    DOC_BATCH_SIZE,
    MODEL_NAME,
    QUERY_BATCH_SIZE,
    embed,
    get_model,
    is_loaded,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("embed_api")


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 요청마다 로드하지 않도록 startup에서 1회 로드한다.
    t0 = time.perf_counter()
    get_model()
    log.info("model loaded model=%s dim=%d elapsed_ms=%.1f",
             MODEL_NAME, DIM, (time.perf_counter() - t0) * 1000)
    yield


app = FastAPI(title="bge-m3 embedding service", lifespan=lifespan)


class QueryRequest(BaseModel):
    text: str = Field(min_length=1)


class BatchItem(BaseModel):
    id: str = Field(min_length=1)
    text: str = Field(min_length=1)


class BatchRequest(BaseModel):
    # 길이 제한을 두지 않는다. HTTP 분할은 호출자(W2) 책임이고,
    # 여기서는 encode batch_size로만 나눠 처리한다.
    items: list[BatchItem]


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "model_loaded": is_loaded(),
        "model": MODEL_NAME,
        "dim": DIM,
    }


@app.post("/embed/query")
def embed_query(request: QueryRequest) -> dict:
    t0 = time.perf_counter()
    vec = embed([request.text], batch_size=QUERY_BATCH_SIZE)
    elapsed_ms = (time.perf_counter() - t0) * 1000
    log.info("embed/query count=1 total_ms=%.1f ms_per_item=%.1f", elapsed_ms, elapsed_ms)
    # tolist()는 float32를 파이썬 float로 그대로 올린다. 반올림·자릿수 절단을 하지 않는다.
    return {
        "embedding": vec[0].tolist(),
        "dim": int(vec.shape[1]),
        "elapsed_ms": elapsed_ms,
    }


@app.post("/embed/batch")
def embed_batch(request: BatchRequest) -> dict:
    count = len(request.items)
    if count == 0:
        return {"embeddings": [], "dim": DIM, "count": 0,
                "elapsed_ms": 0.0, "ms_per_item": 0.0}

    t0 = time.perf_counter()
    vectors = embed([item.text for item in request.items], batch_size=DOC_BATCH_SIZE)
    elapsed_ms = (time.perf_counter() - t0) * 1000

    # 요청의 id를 그대로 되돌려준다. 호출자가 순서로 매칭하지 않도록 한다.
    embeddings = [
        {"id": item.id, "embedding": vector.tolist()}
        for item, vector in zip(request.items, vectors)
    ]
    log.info("embed/batch count=%d total_ms=%.1f ms_per_item=%.1f",
             count, elapsed_ms, elapsed_ms / count)
    return {
        "embeddings": embeddings,
        "dim": int(vectors.shape[1]),
        "count": count,
        "elapsed_ms": elapsed_ms,
        "ms_per_item": elapsed_ms / count,
    }
