# 确定 Java-Python 契约所有权与运行时 seam

Type: grilling
Status: open
Blocked by: 01, 02

## Question

在 Java 作为业务权威、Python 负责 RAG 链路的既定职责下，异步入库与清理、回调、流式问答、取消、幂等、错误和可观测性契约应由哪一侧拥有并存放，如何版本化和验证？请确定 Nacos 发现、HTTP/SSE 适配器、契约模型、生成或手写策略、测试替身及禁止跨越的依赖方向，使双方能独立实现而不复制业务状态机。

## Answer

