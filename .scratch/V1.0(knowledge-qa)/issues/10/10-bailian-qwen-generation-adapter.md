# 10 — 接入百炼 qwen-plus 生成适配器

**What to build:** 试点部署能够通过配置使用阿里云百炼 `qwen-plus` 生成真实流式回答，同时保持已经验证的拒答、取消、失败保存和隐私行为；自动化环境继续使用确定性假模型。前端回答展示支持原格式与 Markdown 渲染，提问输入支持 Enter 发送、Shift+Enter 换行。

**Blocked by:** 02 — 上传并问答一篇 TXT 企业文档; 07 — 完善会话、历史与流式中断.

**Status:** ready-for-agent

- [x] 生成能力通过可替换适配器提供，模型名称、API 凭据和相关参数由部署配置注入，不写死服务地址或秘密。
- [x] 百炼流被转换为既有 SSE 语义；正常完成、拒答、客户端停止、上游错误和中途断流都保持既定问答状态及残缺内容保存行为。
- [x] 发送给百炼的上下文仅包含当前知识库检索到的合格片段，提示词要求简洁中文、结论优先且不得使用模型通用知识兜底。
- [x] 上传页面持续展示数据出网提示；系统不声称进行了脱敏、敏感性校验、审批或合规判断。
- [x] 所有自动化测试注入假模型且不访问百炼；真实百炼通道仅通过部署后的人工问答冒烟确认。
- [x] 大模型回复内容按原始格式（换行符、制表符等）与 Markdown 渲染展示；历史问答与流式回答渲染一致，渲染仅在浏览器内存中进行，不写入持久存储或日志。
- [x] 提问输入框支持 Enter 发送、Shift+Enter 换行；中文输入法组合期间 Enter 不触发发送。

## Comments

- 实现代码已通过 ruff、mypy、pytest（unit + contract）全部验证。
- 第 5 项"真实百炼通道仅通过部署后的人工问答冒烟确认"——冒烟本身尚未执行，属于部署后动作，非本编码任务范围。需要部署后人工用 `qwen-plus` 运行一次问答确认端到端流式回答正常。
- 第 14 项（Markdown 渲染）已落地：`WorkspacePage.vue` 历史与流式共用 `renderMarkdown`（`composables/useMarkdown.js`），`markdown-it` 配置 `html:false`（防 XSS）、`breaks:true`（换行原格式）；回答内容仅存于 `ref` 内存，无持久化/日志。依赖 `markdown-it@14.1.0` 已加入 `package.json`。
- 第 15 项（输入框快捷键）已落地：`WorkspacePage.vue` 的 `onQuestionKeydown` 处理 Enter 发送、Shift+Enter 换行，并基于 `event.isComposing || event.keyCode === 229` 在中文输入法组合期间不触发发送。

## 2026-08-04 故障修复 — 百炼 MaaS 私有实例调用方式切换（方案 B）

**现象：** 页面调用知识库问答时 RAG 服务报错，日志显示阿里云返回 `InvalidParameter / url error, please check url`，上游 `RuntimeError: 百炼流式生成失败`，回答生成失败（HTTP 200 但 `stage=generate` 失败）。模型为 MaaS 私有部署的 `qwen3.7-flash-2026-07-15`。

**根因：** 原适配器使用 DashScope 原生 SDK（`dashscope.Generation.call`）把 `.env` 中的 `SAGE_VAULT_RAG_BAILIAN_BASE_URL`（`https://<instance>.maas.aliyuncs.com/api/v1`，OpenAI 兼容路径）当作 `base_http_api_url` 透传。该地址是 OpenAI 风格 `/api/v1` 根，DashScope SDK 拼出错误内部路径，导致网关解析到错误 URL 返回 `url error`。

**修复（方案 B — 改用 OpenAI 兼容客户端）：**
- `adapters/dashscope/generator.py`：
  - 底层调用从 `dashscope.Generation.call` 切换为 `openai.OpenAI().chat.completions.create`，凭据与类型仍只在本适配器内出现。
  - `base_url` 直接作为 openai 客户端的 `base_url`（即 `.env` 现有 MaaS 值，无需改配置即可联调）。
  - `_open_stream` 参数改为 OpenAI 风格：`stream` / `timeout` / `max_tokens` / `temperature`，去掉 `result_format` / `incremental_output` / `request_timeout`。
  - `_extract_delta` 适配 OpenAI chunk 结构（`choices[0].delta.content`），保留对旧 `output.choices[0].message.content` 假对象的兼容分支；`_extract_delta` 与 `_build_messages` 归为类内方法。
  - 移除调试用 `print`。
- `pyproject.toml` / `uv.lock`：新增依赖 `openai>=1.40,<2`（已 `uv sync` 安装，`uv.lock` 已锁定 1.109.1）。`dashscope` 依赖保留不移除（其余模块仍声明）。
- 单元测试：假对象改为 OpenAI chunk 结构（`choices[0].delta.content`），断言同步更新。

**验证：**
- `tests/unit/adapters/dashscope/test_generator.py`：8 passed。
- `ruff check` 通过。

**部署注意：**
- `.env` 的 `SAGE_VAULT_RAG_BAILIAN_BASE_URL` 已是正确的 MaaS OpenAI 兼容根地址，无需修改；重启 RAG 服务即可联调。
- 若联调仍报 `url error`，按该 MaaS 实例实际 chat completions 路径调整 `.env` 的 base_url（去掉或补 `/api/v1`）。
- 第 5 项冒烟（真实百炼通道端到端问答）仍属部署后人工动作，本次修复后应执行一次确认 `stage=generate` 不再失败。

