import asyncio
from pathlib import Path

import torch
from FlagEmbedding import BGEM3FlagModel


class BgeM3Embedder:
    """本地 bge-m3 嵌入适配器，支持 GPU/CPU profile 与并发控制。"""

    def __init__(
        self,
        model_path: str,
        profile: str = "cpu-dev",
        batch_size: int = 1,
        max_length: int = 8192,
        max_concurrent_requests: int = 1,
        max_queue_size: int = 0,
    ) -> None:
        self._model_path = Path(model_path)
        self._profile = profile
        self._batch_size = batch_size
        self._max_length = max_length
        self._semaphore = asyncio.Semaphore(max_concurrent_requests)
        self._max_queue_size = max_queue_size
        self._queued = 0
        self._model: BGEM3FlagModel | None = None
        self._device: str | None = None
        self._lock = asyncio.Lock()

    async def ready(self) -> bool:
        async with self._lock:
            return self._model is not None

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if self._max_queue_size > 0 and self._queued >= self._max_queue_size:
            raise RuntimeError("嵌入队列已满，请稍后重试")
        self._queued += 1
        try:
            async with self._semaphore:
                loop = asyncio.get_running_loop()
                return await loop.run_in_executor(None, self._encode_in_executor, texts)
        finally:
            self._queued -= 1

    def _encode_in_executor(self, texts: list[str]) -> list[list[float]]:
        model = self._load_model()
        return self._encode(model, texts)

    def _load_model(self) -> BGEM3FlagModel:
        if self._model is not None:
            return self._model
        if not self._model_path.exists():
            raise FileNotFoundError(f"模型目录不存在: {self._model_path}")
        use_fp16 = self._profile == "gpu"
        device = "cuda" if self._profile == "gpu" else "cpu"
        self._device = device
        self._model = BGEM3FlagModel(
            str(self._model_path),
            use_fp16=use_fp16,
            device=device,
        )
        return self._model

    def _encode(self, model: BGEM3FlagModel, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        result = model.encode(
            texts,
            batch_size=self._batch_size,
            max_length=self._max_length,
        )
        vectors = result["dense_vecs"]
        if isinstance(vectors, torch.Tensor):
            vectors = vectors.detach().cpu().numpy()
        return [vector.tolist() for vector in vectors]
