"""같은 모델의 기존 계산과 캐시 사용 결과·시간을 비교한다. 외부 API와 DB는 호출하지 않는다."""
import argparse
from time import perf_counter

import numpy as np

import main


parser = argparse.ArgumentParser()
parser.add_argument("--pdf", help="실제 발표자료로 측정하려면 PDF 경로를 지정한다.")
args = parser.parse_args()
if args.pdf:
    from pathlib import Path
    texts = [chunk for page in main.extract_pdf(Path(args.pdf).read_bytes())
             for chunk in main.split_text(page["text"]) if chunk.strip()]
else:
    texts = [f"발표자료 {i}: " + "Spring과 FastAPI에서 자료를 분석하고 질문의 근거를 검색합니다. " * (i + 1)
             for i in range(12)] * 3
assert texts, "텍스트가 있는 PDF가 필요합니다."


def uncached(text):
    ids = main.tokenize_text(f"passage: {text}")
    hidden = main.model.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids),
                                   "token_type_ids": np.zeros_like(ids)})[0]
    vector = hidden[0].mean(axis=0)
    return vector / max(float(np.linalg.norm(vector)), 1e-12)


started = perf_counter()
expected = np.array([uncached(text) for text in texts])
baseline = perf_counter() - started
main._embed_token_ids.cache_clear()
started = perf_counter()
first = np.array([vector.to_list() for vector in main.create_document_embedding(texts)])
first_time = perf_counter() - started
started = perf_counter()
repeat = np.array([vector.to_list() for vector in main.create_document_embedding(texts)])
repeat_time = perf_counter() - started
np.testing.assert_array_equal(first, expected)
np.testing.assert_array_equal(repeat, expected)
print(f"chunks={len(texts)} uncached={baseline:.3f}s first={first_time:.3f}s repeat={repeat_time:.3f}s")
print(f"vectors unchanged; cache={main._embed_token_ids.cache_info()}")
