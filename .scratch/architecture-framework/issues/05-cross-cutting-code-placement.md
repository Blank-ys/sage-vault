# 确定配置、迁移、测试与运维代码的横切落点

Type: grilling
Status: open
Blocked by: 01, 02, 03, 04, 08

## Question

在已确定的 Java、Python、前端和跨进程 seam 之上，数据库迁移与种子数据、Nacos/环境配置、Docker 编排、契约测试、Milvus 集成测试、质量评测、日志脱敏验证和部署冒烟清单分别应归哪个模块所有？请形成“代码随所有者就近放置”与“允许进入仓库级目录”的明确准入规则，避免横切资产散落或形成无主的共享目录。

## Answer
