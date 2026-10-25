"""A small, deterministic BM25 search over SellerPilot demo store policies."""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass


@dataclass(frozen=True)
class PolicyChunk:
    """One citable passage from the explicitly fictional demo policy."""

    chunk_id: str
    title: str
    section: str
    text: str


POLICY_CHUNKS = (
    PolicyChunk(
        "demo-shipping-01",
        "SellerPilot 演示店铺政策",
        "发货时限",
        "订单付款确认后，店铺应在 2 个工作日内完成打包并交给承运方。遇到节假日或不可抗力时，客服应主动告知买家预计发货日期。",
    ),
    PolicyChunk(
        "demo-cancel-01",
        "SellerPilot 演示店铺政策",
        "未发货订单取消",
        "订单尚未交给承运方时，买家可以申请取消。若超过预计发货日期 48 小时仍未发货，客服确认订单状态后应为买家办理取消。",
    ),
    PolicyChunk(
        "demo-return-01",
        "SellerPilot 演示店铺政策",
        "退货申请",
        "买家签收后 14 个自然日内可以提交退货申请。商品须保持未使用且包装完整；有质量问题时，客服应先收集订单号和问题照片，再提供处理方案。",
    ),
    PolicyChunk(
        "demo-listing-01",
        "SellerPilot 演示店铺政策",
        "商品标题规范",
        "商品标题应准确描述商品本身，不得添加无法证明的认证、功效或排名声明。标题建议控制在 120 个字符以内，并避免重复堆砌关键词。",
    ),
)


def _terms(text: str) -> list[str]:
    """Turn Chinese text into overlapping bigrams and keep English words whole."""
    terms: list[str] = []
    for part in re.findall(r"[\u3400-\u9fff]+|[a-zA-Z0-9]+", text.lower()):
        if "\u3400" <= part[0] <= "\u9fff":
            if len(part) == 1:
                terms.append(part)
            else:
                terms.extend(part[i : i + 2] for i in range(len(part) - 1))
        else:
            terms.append(part)
    return terms


def retrieve_policy_chunks(question: str, *, top_k: int = 3) -> list[dict]:
    """Return ranked, citable policy passages; an empty list means no lexical match.

    BM25 ranks the fixed demo corpus without an external service or database. It
    provides retrieval evidence only; callers must still refuse unsupported claims.
    """
    if not isinstance(question, str) or not question.strip():
        raise ValueError("question 不能为空")
    if type(top_k) is not int or not 1 <= top_k <= 10:
        raise ValueError("top_k 必须是 1～10 的整数")

    query_terms = _terms(question)
    if not query_terms:
        return []

    documents = [_terms(chunk.text + " " + chunk.section) for chunk in POLICY_CHUNKS]
    document_frequency = Counter(
        term for document in documents for term in set(document)
    )
    average_length = sum(map(len, documents)) / len(documents)
    query_frequency = Counter(query_terms)
    ranked: list[tuple[float, PolicyChunk]] = []

    for chunk, document in zip(POLICY_CHUNKS, documents, strict=True):
        frequencies = Counter(document)
        score = 0.0
        for term, qf in query_frequency.items():
            tf = frequencies[term]
            if not tf:
                continue
            idf = math.log1p(
                (len(documents) - document_frequency[term] + 0.5)
                / (document_frequency[term] + 0.5)
            )
            length_norm = 1.2 * (0.25 + 0.75 * len(document) / average_length)
            score += idf * tf * 2.2 / (tf + length_norm) * (1 + math.log(qf))
        if score > 0:
            ranked.append((score, chunk))

    ranked.sort(key=lambda item: (-item[0], item[1].chunk_id))
    return [
        {
            "chunk_id": chunk.chunk_id,
            "title": chunk.title,
            "section": chunk.section,
            "text": chunk.text,
            "score": round(score, 4),
            "source": "SellerPilot 演示店铺政策（仅为项目自编示例，不代表真实平台规则）",
        }
        for score, chunk in ranked[:top_k]
    ]
