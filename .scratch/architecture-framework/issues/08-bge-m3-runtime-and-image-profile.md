# 确定 bge-m3 运行硬件与镜像基线

Type: grilling
Status: open
Blocked by: 02

## Question

首个试点环境将为本地 `BAAI/bge-m3` 提供什么 CPU/GPU、内存/显存和离线模型分发条件，据此应固定哪个模型 revision、Torch CPU/CUDA wheel 来源、批大小、进程数、并发 semaphore、健康检查和镜像 smoke test 门槛？请形成可复现的 RAG 运行镜像 profile，同时保留未来更换硬件或推理 adapter 的 seam。

## Answer

