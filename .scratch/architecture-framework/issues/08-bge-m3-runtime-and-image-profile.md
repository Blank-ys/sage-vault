# 确定 bge-m3 运行硬件与镜像基线

Type: grilling
Status: resolved
Blocked by: 02, 09

## Question

首个试点环境将为本地 `BAAI/bge-m3` 提供什么 CPU/GPU、内存/显存和离线模型分发条件，据此应固定哪个模型 revision、Torch CPU/CUDA wheel 来源、批大小、进程数、并发 semaphore、健康检查和镜像 smoke test 门槛？请形成可复现的 RAG 运行镜像 profile，同时保留未来更换硬件或推理 adapter 的 seam。

## Answer

### 首个试点 profile

- 前端、Java 和 Python 均在 Windows 宿主机原生开发环境运行；Ubuntu 24 VMware 虚拟机只运行 MySQL、MinIO、Milvus、Nacos 等中间件，不承担 `bge-m3` 推理。
- 已核实的目标 GPU 是 NVIDIA GeForce RTX 4060 Laptop GPU（8,188 MiB 显存），宿主机 CPU 是 Intel Core i9-13900HX（32 个逻辑处理器）。GPU profile 使用 Python 3.12、单进程、单 Uvicorn worker、单模型实例、FP16、batch `4` 和嵌入 semaphore `1`。CUDA 不可用时禁止静默降级，服务保持 not ready。
- `cpu-dev` 是必须显式选择的排障 profile，使用 FP32、单 worker、batch `1`、semaphore `1`，不承担试点性能验收。Linux CPU 镜像采用同样的单 worker/batch/semaphore，只验证离线安装、启动和中文嵌入可移植性，不承诺启动时间或吞吐。
- V1 以 Python 进程内的 FlagEmbedding adapter 加载 `BAAI/bge-m3`。Hugging Face Hub 只用于联网制品准备，不是运行时服务；Ollama 不进入首个 profile，未来只有出现真实需要时才作为第二个 adapter 接入既有嵌入 port。

### 离线模型与依赖制品

- 程序与模型分开发布。模型权重不进入 Git 或 Linux RAG 镜像；联网制品准备阶段按 Hugging Face 40 位 commit SHA 下载完整 snapshot，生成逐文件 SHA-256 清单，发布到 MinIO 不可变、版本化、只读路径。Windows 和 Linux 运行时使用相同目录结构与清单。
- 部署先从 MinIO 取得模型制品并校验到显式本地目录；运行时启用 Hugging Face 离线模式并只从该目录加载。模型 revision 缺失、使用可移动的 `main`/tag、清单不一致或任何运行时联网尝试都导致启动失败。
- Windows GPU profile 的 Torch wheel 只能来自 PyTorch 官方 `cu128` 索引；Linux CPU profile 只能来自 PyTorch 官方 CPU 索引。完整依赖图、来源和制品哈希由受版本控制的 uv lock/export 与离线 wheelhouse 固化，生产/CI 使用 frozen、offline 安装，禁止部署现场解析。
- 已证实 FlagEmbedding `1.4.x` 只有 `1.4.0`。模型的实际 40 位 SHA、Torch 最终精确版本以及 Windows CUDA/Linux CPU 两套解析与 smoke 证据按[在 docs 起草阶段固定 bge-m3 制品与依赖锁](10-finalize-bge-m3-artifact-and-lock-for-docs.md)延期处理；在该待办解决前，`2.7.1/cu128` 只能是调研候选，不能写成已验证基线。依据见[调研 bge-m3 revision 与 Torch 离线锁定基线](09-research-bge-m3-revision-and-torch-lock.md)。

### 调度与失败语义

- 问答查询和企业文档入库共享一个模型实例及一个 GPU 执行槽。查询优先队列上限 `5`，入库队列上限 `1` 个批次；队列满时通过既有 Java-Python 错误契约返回可重试的忙碌错误，不无限排队、不并行加载第二份模型。
- 五路并发回答仍是系统级验收目标，不等同于五路并行 GPU 推理。系统验收必须证明上述串行嵌入调度仍满足既有首字与总时延目标；若不满足，必须先重新测量硬件预算和 profile，不能在代码里隐式提高并发。
- CUDA OOM、设备丢失或模型推理异常立即撤销 readiness；只有显式重载并重新通过完整探针后才能恢复接流量。

### 健康检查与 smoke test

- liveness 只检查进程及事件循环。完整 readiness 探针只在启动或模型重载后执行并缓存结果；日常 readiness 请求只读取缓存状态和当前 device/model 标识，不占用 GPU 执行槽。
- 完整探针校验离线模型 revision 与 SHA-256 清单、CUDA 设备、FP16 模型加载，并嵌入固定中文文本；输出必须为 `1024` 维、全部数值有限，L2 归一化误差不超过 `1e-3`。
- Windows GPU smoke test 要求：冷启动至 ready 不超过 `120` 秒且不 OOM；连续 `10` 轮 batch `4` 成功且结果稳定；显存峰值不超过 `7.5 GiB`；测试结束后 readiness 仍通过。
- Linux CPU 镜像 smoke test 只要求模型制品和 wheelhouse 可离线校验、服务可启动，并用同一中文探针得到满足维度、有限值和归一化约束的嵌入。
- 模型 smoke test 不新增产品性能承诺。企业文档入库、五路并发回答、五秒首字和十五秒完成仍由 V1 系统验收在完整链路上衡量。

### Adapter seam

application 层只依赖项目自有的嵌入 port，输入是文本批次和用途（查询或企业文档入库），输出是项目自有的归一化稠密向量或分类后的资源错误。FlagEmbedding、Torch、CUDA、模型目录、精度、batch 和队列均封装在 `bge_m3` adapter 与 bootstrap profile 内，不进入 Java-Python wire contract。未来更换硬件、切换 Linux GPU 或增加 Ollama adapter 时，不改变入库、回答模块或跨进程 interface。
