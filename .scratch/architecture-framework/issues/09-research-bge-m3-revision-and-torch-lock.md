# 调研 bge-m3 revision 与 Torch 离线锁定基线

Type: research
Status: resolved

## Question

基于 Hugging Face、FlagEmbedding、PyTorch 与 uv 的一手资料，首个 Windows CUDA 试点 profile 应固定哪个 `BAAI/bge-m3` 40 位 commit SHA、FlagEmbedding 精确版本、PyTorch `cu128` 精确版本及官方 wheel 来源？Python 3.12 下如何生成可同时支持 Windows CUDA 与 Linux CPU 的可复核 lock/离线 wheel 清单，并用哪些命令证明解析结果，而不把未经验证的版本写入架构决策？

## Answer

### 结论

- **模型 revision 不在本工单中猜测或预填。** 本次环境无法连接 `huggingface.co:443`，因此没有取得可复核的 `BAAI/bge-m3` 当前提交。首次制品准备必须从 Hugging Face 官方模型 API 的 `sha` 字段取得 40 位 commit SHA（`GET https://huggingface.co/api/models/BAAI/bge-m3`），再以该 SHA 执行 `snapshot_download(..., revision=<sha>)`；模型清单、MinIO 制品元数据和运行配置必须记录同一个 SHA。只接受 40 位十六进制值，禁止 `main`、tag 或模型名充当 revision。未取得或不一致时不得发布，readiness 失败。[Hugging Face Hub API](https://huggingface.co/docs/hub/api#get-apimodelsmodel-id-or-apimodelsmodel-idrevisionrevision)；[下载文件](https://huggingface.co/docs/huggingface_hub/guides/download)
- **FlagEmbedding 固定为 `1.4.0`，不是 `1.4.1`。** 2026-07-24 实查 PyPI 官方 JSON API：最新版本及唯一 `1.4.x` release 都是 `1.4.0`；`1.4.1` 返回 Not Found。其元数据要求 `torch>=1.6.0`、`transformers>=4.44.2,<6.0.0`，但没有声明 `Requires-Python`，所以“Python 3.12 + 精确 Torch”不能只凭元数据宣称兼容，仍须由解析和 smoke test 证明。[FlagEmbedding 1.4.0 PyPI JSON](https://pypi.org/pypi/FlagEmbedding/1.4.0/json)；[PyPI 项目](https://pypi.org/project/FlagEmbedding/1.4.0/)
- **Torch 候选基线为 `2.7.1`，但在实施验证通过前不是已验证架构事实。** PyTorch 官方 `cu128` simple index 已实查存在 Windows CPython 3.12 x86-64 wheel `torch-2.7.1+cu128-cp312-cp312-win_amd64.whl`，索引公布 SHA-256 `2bb8c05d48ba815b316879a18195d53a6472a03e297d971e916753f8e1053d30`。Linux profile 应解析同一 `2.7.1` 的官方 CPU wheel；本次未完成该 wheel 的索引/哈希实查，因此不得预填它的文件名或哈希。[PyTorch cu128 torch index](https://download.pytorch.org/whl/cu128/torch/)；[PyTorch previous versions（2.7.1/cuda 12.8）](https://pytorch.org/get-started/previous-versions/)

### uv 表达与锁定

一个 `uv.lock` 可以用平台 marker 将同名 `torch` 指向两个显式官方索引，应用依赖仍写 `torch==2.7.1`：

```toml
[project]
requires-python = ">=3.12,<3.13"
dependencies = [
  "FlagEmbedding==1.4.0",
  "torch==2.7.1",
]

[tool.uv.sources]
torch = [
  { index = "pytorch-cu128", marker = "sys_platform == 'win32'" },
  { index = "pytorch-cpu", marker = "sys_platform == 'linux'" },
]

[[tool.uv.index]]
name = "pytorch-cu128"
url = "https://download.pytorch.org/whl/cu128"
explicit = true

[[tool.uv.index]]
name = "pytorch-cpu"
url = "https://download.pytorch.org/whl/cpu"
explicit = true
```

`explicit = true` 防止其他依赖意外从 PyTorch 索引解析。uv 官方 PyTorch 指南明确展示了专用索引、`tool.uv.sources` 和按平台 marker 选择索引的做法；锁文件会记录解析来源和制品哈希。[uv：Using uv with PyTorch](https://docs.astral.sh/uv/guides/integration/pytorch/)；[uv：Package indexes](https://docs.astral.sh/uv/concepts/indexes/)；[uv lockfile](https://docs.astral.sh/uv/concepts/projects/layout/#the-lockfile)

首次实施在联网、干净目录中运行并保存输出：

```powershell
uv lock --python 3.12 --refresh
uv lock --check
uv export --locked --format requirements.txt --no-dev -o requirements.lock.txt
uv tree --locked
uv sync --locked --no-dev
uv run python -c "import torch, FlagEmbedding; print(torch.__version__, torch.version.cuda, torch.cuda.is_available())"
```

Linux CPU 环境对同一份 `pyproject.toml`/`uv.lock` 重复 `uv sync --locked --no-dev`，并断言 `torch.__version__` 为 `2.7.1`、`torch.version.cuda is None`。若 uv 不能为两个目标生成一个无歧义 lock，则退回两个受版本控制的 lock/export（`windows-cu128` 与 `linux-cpu`），不能手改 lock 或让部署现场重新解析。

### 离线 wheelhouse 与哈希门禁

`uv 0.11.29` 本机实查没有 `uv pip download` 子命令。因此应先用 `uv export --locked` 导出带哈希的 requirements，再在**各目标平台的 Python 3.12 环境**用 pip 只下载 wheel；不要在 Windows 假装下载 Linux 制品：

```powershell
uv export --locked --format requirements.txt --no-dev -o requirements.lock.txt
python -m pip download --require-hashes --only-binary=:all: --dest wheelhouse -r requirements.lock.txt
python -m pip install --no-index --find-links wheelhouse --require-hashes -r requirements.lock.txt
uv sync --locked --offline --no-dev
```

将 `requirements.lock.txt`、`uv.lock`、全部 wheel 的 SHA-256 清单、Python/uv 版本和平台标识一起发布到 MinIO 的不可变版本目录。离线安装前重新计算 SHA-256；任何缺包、sdist、哈希不符或网络访问都必须失败。`uv export` 默认保留哈希，`--locked` 保证命令不会改写 lock，`--offline` 禁止网络。[uv export reference](https://docs.astral.sh/uv/reference/cli/#uv-export)；[uv sync reference](https://docs.astral.sh/uv/reference/cli/#uv-sync)

### 发布前必须补齐的证据

以下均是门禁，不是本调研已经证明的事实：

1. 用 Hugging Face 官方 API 取得并记录实际 40 位 SHA，再下载快照、生成逐文件 SHA-256，并验证 `local_files_only=True`/`HF_HUB_OFFLINE=1` 可加载。
2. `uv lock` 必须在 Windows x86-64/Python 3.12 和 Linux x86-64/Python 3.12 两端成功；导出的每个制品都必须来自 PyPI 或上述 PyTorch 官方索引并带可复核哈希。
3. Windows 目标机必须证明 `torch==2.7.1+cu128`、CUDA 可用、FlagEmbedding 1.4.0 能以 FP16 加载固定模型 revision，并通过工单“确定 bge-m3 运行硬件与镜像基线”规定的显存、批量、readiness 与稳定性 smoke test。
4. Linux CPU 镜像必须证明同版本 Torch/FlagEmbedding 可离线安装和完成固定中文探针；失败时重新选择并锁定经过验证的 Torch 版本，而不是放宽为浮动范围。
