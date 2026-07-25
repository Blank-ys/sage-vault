# 在 docs 起草阶段固定 bge-m3 制品与依赖锁

Type: task
Status: open
Blocked by: 08, 09

## Question

起草最终 `docs/architecture.md` 与 `docs/code-framework.md` 时，在可访问 Hugging Face、PyTorch 官方索引且能运行 Windows CUDA/Linux CPU 目标环境的条件下：取得并记录 `BAAI/bge-m3` 实际 40 位 commit SHA；生成模型逐文件 SHA-256 清单；用 Python 3.12 和固定 uv 版本验证 FlagEmbedding `1.4.0` 与 Torch 候选版本；为 Windows `cu128` 和 Linux CPU 生成并核验 lock/export、离线 wheelhouse 与哈希；执行工单“确定 bge-m3 运行硬件与镜像基线”规定的两套 smoke test。只有全部通过后，才把实际 revision、Torch 精确版本、wheel 文件与哈希写入最终 docs；失败则重新选择候选版本并完整复测，不放宽为浮动版本。

## Answer

