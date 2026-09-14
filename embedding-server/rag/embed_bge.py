"""bge-m3 임베딩 단일 진실공급원.

`eval/build_index.py`가 `build/vec.npy`(R@5 0.975 기준선의 실물)를 만들 때 사용한
호출을 값 변경 없이 그대로 옮긴 것이다. 모델명·normalize·batch_size·pooling·프리픽스를
바꾸면 기준선이 통째로 무효가 되므로, 임베딩이 필요한 모든 코드는 이 모듈만 사용한다.

원본 호출 (2026-08-29 기준):

    eval/build_index.py:34-42   문서   SentenceTransformer("BAAI/bge-m3")
                                       .encode(texts, batch_size=8,
                                               normalize_embeddings=True)
                                       -> np.asarray(..., dtype=np.float32)
    eval/run_search.py:29-34    쿼리   같은 모델, batch_size=32,
                                       normalize_embeddings=True

문서와 쿼리는 `batch_size`만 다르고 모델·normalize·pooling·프리픽스가 동일하다.
프리픽스/instruction은 양쪽 모두 사용하지 않는다. 따라서 한 함수로 묶고 batch_size만
호출자가 고른다.

`show_progress_bar`는 콘솔 출력 여부일 뿐 임베딩 값에 영향을 주지 않는다. 원본
스크립트는 True였으나 서버에서는 불필요하므로 기본값을 False로 두고 인자로 남긴다.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np
from sentence_transformers import SentenceTransformer

# eval/build_index.py:24
MODEL_NAME = "BAAI/bge-m3"

# eval/build_index.py:38 (문서) / eval/run_search.py:33 (쿼리)
DOC_BATCH_SIZE = 8
QUERY_BATCH_SIZE = 32

DIM = 1024

_model: SentenceTransformer | None = None


def get_model() -> SentenceTransformer:
    """모델 싱글턴. 최초 호출에서만 로드한다."""
    global _model
    if _model is None:
        _model = SentenceTransformer(MODEL_NAME)
    return _model


def is_loaded() -> bool:
    return _model is not None


def embed(
    texts: Sequence[str],
    batch_size: int = DOC_BATCH_SIZE,
    show_progress_bar: bool = False,
) -> np.ndarray:
    """텍스트를 L2 정규화된 float32 행렬로 임베딩한다. shape = (len(texts), 1024).

    build_index.py가 vec.npy에 저장한 것과 같은 형태 — encode 후 float32 캐스팅까지
    포함한다(build_index.py:42).
    """
    vec = get_model().encode(
        list(texts),
        batch_size=batch_size,
        normalize_embeddings=True,
        show_progress_bar=show_progress_bar,
    )
    return np.asarray(vec, dtype=np.float32)
