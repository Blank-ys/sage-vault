# 通用企业文档问答 V1

Status: ready-for-agent

## Problem Statement

企业内部存在分散在 PDF、DOCX、Markdown 和纯文本文件中的知识。普通用户需要在不了解文件位置和准确措辞的情况下，用中文快速获得基于企业文档的回答；知识管理员则需要一种简单方式，将多类企业文档组织成不同主题的知识库并持续维护。

当前仓库虽然已有 RuoYi 的认证、用户、角色、文件服务和管理界面底座，但缺少完整的文档解析、嵌入、检索和生成链路。先前文档过早聚焦规章制度、权限过滤和条款级引用，尚未证明最基本的通用 RAG 闭环能够稳定工作，也没有形成清晰的用户体验、文档生命周期、质量门槛和内部试点范围。

V1 需要先提供一个可由小范围真实团队使用的内部试点版：知识管理员能够维护多个知识库及其中的企业文档，普通用户能够选择一个知识库进行中文单轮语义问答，并在证据不足时得到明确拒答。试点必须能够量化回答质量、拒答质量、入库速度和流式回答性能，为后续引用、权限、敏感性治理和检索优化提供可信基础。

## Solution

在现有 RuoYi Java/Vue 系统中增加知识库管理、企业文档管理、问答工作台、会话历史和反馈处理能力，并新增 Python AI 服务完成文档解析、通用切块、本地嵌入、知识库内向量检索和流式回答生成。

知识管理员创建知识库，将带可提取文本层的 PDF、DOCX、MD 或 TXT 文件批量上传到指定知识库。文件异步处理，只有整篇解析、切块、嵌入和向量发布全部成功后才参与检索。原文件保存在 MinIO，业务数据保存在 MySQL，全部片段向量保存在同一个 Milvus Collection，并通过 `knowledgeBaseId` 强制限定检索边界。

普通用户可以看到全部可用知识库的名称和描述，在新建会话时选择其中一个知识库。会话用于组织问答记录，但每个问题独立检索，不继承历史上下文。系统使用本地 `bge-m3` 生成向量，只做稠密向量检索，再通过阿里云百炼 `qwen-plus` 生成简洁中文回答。回答经 Java 以 SSE 转发给浏览器；用户可以停止生成，系统失败或主动停止时保留已经输出的残缺回答并标记状态。检索证据不足时必须拒答，不得使用模型自身知识补充答案。

普通用户默认永久保留自己的会话和问答记录，也可以主动删除。只有普通用户主动提交某条问答的反馈后，知识管理员才能查看该条问题和回答正文。技术日志保留定位故障所需的标识、阶段、耗时、检索元数据和错误，但不复制问题、片段、提示词或生成正文。

## User Stories

1. As a 普通用户, I want to use my existing RuoYi account to access Knowledge QA, so that I do not need a separate account.
2. As a 普通用户, I want to see every available knowledge base's name and description, so that I can choose the most relevant search boundary.
3. As a 普通用户, I want unavailable or deleting knowledge bases to be excluded from selection, so that I do not start work in a boundary that cannot answer.
4. As a 普通用户, I want to create a new conversation by selecting exactly one knowledge base, so that every question has an unambiguous retrieval boundary.
5. As a 普通用户, I want an empty knowledge base to remain selectable, so that I can enter its workspace before documents become available.
6. As a 普通用户, I want a clear “该知识库暂无可用文档” response when I ask an empty knowledge base, so that I understand why it cannot answer.
7. As a 普通用户, I want the first question to become the default conversation title, so that new conversations are recognizable without extra work.
8. As a 普通用户, I want to rename a conversation, so that I can organize my history in language meaningful to me.
9. As a 普通用户, I want to ask multiple questions inside one conversation, so that related independent records can be kept together.
10. As a 普通用户, I want each question to be processed independently of earlier messages, so that V1 behavior is predictable and does not infer unstated context.
11. As a 普通用户, I want every question in a conversation to search only its bound knowledge base, so that unrelated knowledge bases do not contaminate the answer.
12. As a 普通用户, I want the system to accept natural Chinese questions and semantic paraphrases, so that I do not need to know the document's exact wording.
13. As a 普通用户, I want the answer to start streaming while it is generated, so that I receive useful feedback without waiting for the whole answer.
14. As a 普通用户, I want streaming states to distinguish start, content, completion, refusal and error, so that the interface always represents what is happening.
15. As a 普通用户, I want answers to state the conclusion first and add only necessary explanation, so that they remain easy to scan.
16. As a 普通用户, I want answers to use only retrieved enterprise document content, so that model general knowledge is not mistaken for company knowledge.
17. As a 普通用户, I want a clear refusal when relevant evidence is insufficient, so that the system does not invent an answer.
18. As a 普通用户, I want to stop an answer that is generating, so that I do not have to wait for irrelevant or overly long output.
19. As a 普通用户, I want already streamed text to remain visible after I stop generation, so that useful partial content is not lost.
20. As a 普通用户, I want a stopped answer to be marked “已停止”, so that it is not confused with a complete answer or system failure.
21. As a 普通用户, I want partial text to be saved when generation fails, so that I can inspect what was produced before the failure.
22. As a 普通用户, I want a failed partial answer to be marked “未完成”, so that I do not rely on it as a complete answer.
23. As a 普通用户, I want only one answer to generate for me at a time, so that accidental duplicate requests do not consume the limited trial capacity.
24. As a 普通用户, I want a clear prompt to wait or stop the active answer before asking again, so that the concurrency rule is understandable.
25. As a 普通用户, I want complete, stopped and incomplete QA records in my history, so that I can revisit the actual outcome of previous requests.
26. As a 普通用户, I want my history retained by default, so that I do not lose useful enterprise knowledge conversations during the V1 trial.
27. As a 普通用户, I want to delete my own QA records or conversations, so that I control the content retained in my history.
28. As a 普通用户, I want deleting a conversation to delete all its QA records and feedback content, so that no hidden copy of my content remains in the business data.
29. As a 普通用户, I want a deleted knowledge base's old conversation history to remain readable and marked “知识库已删除”, so that past answers are retained without implying the source remains usable.
30. As a 普通用户, I want a conversation bound to a deleted knowledge base to reject new questions, so that it cannot silently search a different boundary.
31. As a 普通用户, I want to report an answer as incorrect, missing an answer, incomplete or other, so that a knowledge administrator can investigate quality problems.
32. As a 普通用户, I want to add an optional explanation to feedback, so that I can describe what I expected.
33. As a 普通用户, I want feedback submission to clearly authorize sharing that QA record with knowledge administrators, so that the privacy consequence is explicit.
34. As a 普通用户, I want knowledge administrators to be unable to read QA content I have not shared, so that ordinary use does not become employee monitoring.
35. As a 普通用户, I want every usable knowledge base to be available to every logged-in user in V1, so that the trial does not depend on document permission configuration.
36. As a 普通用户, I want the system not to expose document lists, previews or downloads, so that V1 remains focused on question answering.
37. As a 知识管理员, I want to use the ordinary question-answering capability as well as administration features, so that I can verify the knowledge I manage.
38. As a 知识管理员, I want to create a knowledge base with a unique name and description, so that enterprise documents can be grouped by topic.
39. As a 知识管理员, I want knowledge base names to be unique without regard to letter case, so that visually duplicate boundaries cannot be created.
40. As a 知识管理员, I want to rename a knowledge base or edit its description without breaking existing conversations, so that its presentation can evolve safely.
41. As a 知识管理员, I want all knowledge administrators to manage all knowledge bases, so that V1 does not require owner and collaborator administration.
42. As a 知识管理员, I want to enter a knowledge base's document management page and view its document list and original files, so that document operations and troubleshooting remain scoped to the selected knowledge base.
43. As a 知识管理员, I want to upload text-bearing PDF, DOCX, MD and TXT documents, so that common enterprise document types can enter the RAG pipeline.
44. As a 知识管理员, I want unsupported, encrypted, corrupt, empty, scanned or otherwise non-extractable files to fail with an understandable reason, so that I know how to correct the source.
45. As a 知识管理员, I want to select multiple files in one upload request, so that a knowledge base can be initialized efficiently.
46. As a 知识管理员, I want the entire upload request rejected before persistence when any selected filename conflicts, so that a batch has a clear all-or-nothing validation result.
47. As a 知识管理员, I want the rejection to list every conflicting filename, so that I can fix the batch in one attempt.
48. As a 知识管理员, I want duplicate filenames within the selected batch to count as conflicts, so that concurrent processing cannot create ambiguous documents.
49. As a 知识管理员, I want filename uniqueness to be case-insensitive within one knowledge base, so that equivalent names cannot be uploaded twice.
50. As a 知识管理员, I want different knowledge bases to permit the same filename, so that common names such as `产品手册.pdf` do not create global conflicts.
51. As a 知识管理员, I want processing, available and failed documents to reserve their filename, so that retry and recovery preserve document identity.
52. As a 知识管理员, I want a filename released only after deletion cleanup succeeds, so that a replacement upload cannot overlap stale source or vectors.
53. As a 知识管理员, I want files that pass batch validation to process independently, so that one later parsing failure does not invalidate other files.
54. As a 知识管理员, I want upload to return promptly and processing to continue asynchronously, so that large documents do not block the administration page.
55. As a 知识管理员, I want to see each document as 处理中, 可用, 处理失败 or 删除中, so that its participation in question answering is explicit.
56. As a 知识管理员, I want a document to become available only after its entire ingestion succeeds, so that users never search partially published content.
57. As a 知识管理员, I want failed ingestion artifacts cleaned up, so that retries cannot mix old and new chunks.
58. As a 知识管理员, I want to retry a failed document using the same document record and filename, so that recovery does not create duplicates.
59. As a 知识管理员, I want retry to process the whole document again, so that V1 does not depend on unreliable step-level checkpoints.
60. As a 知识管理员, I want to delete a document and have it immediately excluded from new retrieval, so that removed knowledge stops influencing answers before physical cleanup completes.
61. As a 知识管理员, I want document deletion to clean the MinIO object, parsed chunks and Milvus vectors, so that no inaccessible orphaned data remains.
62. As a 知识管理员, I want historical QA records to survive document deletion, so that past results and submitted investigations retain their original context.
63. As a 知识管理员, I want to delete a knowledge base with a second confirmation, so that destructive cascading cleanup is intentional.
64. As a 知识管理员, I want knowledge base deletion to cascade asynchronously through all documents and storage, so that I do not have to delete documents one by one.
65. As a 知识管理员, I want a deleting knowledge base to reject uploads, new conversations and questions immediately, so that no new work races with cleanup.
66. As a 知识管理员, I want failed knowledge base cleanup to produce a 删除失败 state and reason, so that I can retry without restoring the boundary to use.
67. As a 知识管理员, I want an upload-time notice that questions and retrieved chunks will be sent to Alibaba Cloud Bailian, so that I can make the document sensitivity judgment myself.
68. As a 知识管理员, I want the notice not to claim automated compliance, classification or approval, so that V1 does not create a false security guarantee.
69. As a 知识管理员, I want to see only feedback records that users deliberately submitted, so that troubleshooting respects the agreed privacy boundary.
70. As a 知识管理员, I want feedback to include its shared question, complete or partial answer, request identifier and retrieval diagnostics, so that I can investigate the reported behavior.
71. As a 知识管理员, I want to move feedback between 待处理 and 已处理 and store an internal note, so that the small V1 feedback queue can be managed.
72. As a 知识管理员, I want my knowledge base, document and feedback actions audited with actor, object, time and result, so that administrative changes remain traceable.
73. As a 系统管理员, I want to grant the knowledge administrator role through existing RuoYi role and menu permissions, so that access control reuses the current platform.
74. As a 系统管理员, I want every logged-in user to receive ordinary question-answering access without a second account model, so that onboarding remains simple.
75. As a 系统管理员, I want anonymous users rejected, so that enterprise question answering remains inside the authenticated system.
76. As a 运维人员, I want model names, credentials and service settings supplied by deployment configuration, so that secrets and provider choices are not hard-coded.
77. As a 运维人员, I want Java and Python to discover each other through Nacos, so that internal service addresses do not need to be fixed in application code.
78. As a 运维人员, I want request and task identifiers to correlate Java, Python, storage and model activity, so that distributed failures can be traced.
79. As a 运维人员, I want technical logs to record stages, identifiers, scores, timing, model request identifiers, SSE progress, retries and errors, so that failures can be diagnosed.
80. As a 普通用户, I want technical logs not to copy my question or answer content, so that business content is not dispersed through logging systems.
81. As a 知识管理员, I want technical logs not to copy enterprise chunks or full prompts, so that document content is not duplicated outside managed storage.
82. As a 试点负责人, I want objective quality and performance acceptance criteria, so that V1 success is judged by evidence rather than impressions.
83. As a 试点负责人, I want the system to support up to 1,000 documents, 20 GB total source files, 50 ordinary users and 5 concurrent answers, so that the intended trial team can use it.
84. As a 试点负责人, I want 95% of documents up to 50 MB to become available within five minutes, so that ingestion is practical for ongoing administration.
85. As a 试点负责人, I want 95% of answers to start streaming within five seconds and complete within fifteen seconds when Bailian is healthy, so that interaction feels responsive.
86. As a 试点负责人, I want an annotated Chinese evaluation set covering answerable, cross-chunk, unanswerable and paraphrased questions, so that retrieval and refusal can be tuned reproducibly.
87. As a 试点负责人, I want at least 80% of answerable questions to have a correct conclusion, so that the trial has a meaningful answer-quality floor.
88. As a 试点负责人, I want at least 90% of unanswerable questions to be refused, so that hallucination risk is bounded.
89. As a 试点负责人, I want answers to introduce no key fact absent from retrieved content, so that generated text remains grounded in enterprise documents.

## Implementation Decisions

- V1 is an internal trial, not a demonstration-only prototype and not a production-grade high-availability service.
- The existing RuoYi Vue frontend remains the only browser application. It adds the question-answering workspace, conversation history, knowledge base management, per-knowledge-base document management and feedback processing views while retaining existing login, user and role management.
- The RuoYi Java platform is authoritative for authentication, roles, knowledge bases, enterprise document records, asynchronous task records, conversations, QA records, feedback, business API responses and audit data.
- A new Python AI service owns document parsing, chunking, embedding, vector retrieval, refusal evaluation and answer generation.
- Browser traffic goes only to Java. Java and Python use Nacos-discovered internal HTTP communication rather than fixed peer addresses.
- The Java-Python contract must support asynchronous document ingestion, document cleanup, knowledge base cascading cleanup, result callbacks, idempotency, retries, error reporting, streaming answers and cancellation. Exact payload schemas are to be fixed alongside the domain state machines without changing the external behavior in this specification.
- Question answering uses SSE. Java forwards the Python stream to the browser and preserves at least `started`, `delta`, `completed`, `refused` and `error` semantics.
- A user may have only one generating QA record at a time. A second request is rejected with an actionable wait-or-stop response. The trial supports up to five users generating concurrently.
- Cancellation propagates from the browser through Java to Python, which makes a best effort to cancel the Bailian generation. Already emitted text is persisted as an `已停止` QA record.
- A stream that fails after emitting content persists that content as an `未完成` QA record. Incomplete or stopped output is never counted as a successful answer.
- A conversation belongs to one ordinary user and is permanently bound to one knowledge base. Its title defaults to the first question and remains editable.
- A conversation can contain multiple QA records, but previous QA records are not supplied to retrieval, question rewriting or the model. V1 is single-turn retrieval presented inside a conversation container.
- All available knowledge bases are visible and selectable by all logged-in users. Ordinary users do not receive document lists, source previews or download capabilities.
- Knowledge base names are globally unique using case-insensitive comparison. A knowledge base has a description and the externally meaningful states `可用`, `删除中` and `删除失败`.
- Renaming a knowledge base does not alter existing conversation bindings because relationships use stable identifiers rather than names.
- Knowledge base deletion is cascading and asynchronous. Entering deletion immediately prevents uploads, new conversations and new questions. Cleanup removes all owned documents, MinIO objects, parsed data and Milvus vectors before final removal.
- Failed cascading cleanup leaves the knowledge base unavailable in `删除失败` and exposes a reason and retry action; it does not restore the knowledge base to use.
- Every enterprise document belongs to exactly one knowledge base and has a globally unique stable identifier.
- Original filenames are case-insensitively unique within a knowledge base. Processing, available and failed records reserve the name. Different knowledge bases may contain the same filename. A name is released only when deletion cleanup has completed.
- V1 accepts text-bearing PDF, DOCX, MD and TXT files up to 50 MB each. Encrypted, corrupt, empty, scanned-only or otherwise non-extractable files fail with an understandable reason. V1 does not store passwords, run OCR or provide online source editing.
- Multi-file upload first validates the entire batch. Any conflict with an existing reserved filename or another selected filename rejects the whole request, reports all conflicts and creates no document records or source objects.
- After upload validation succeeds, each file receives its own asynchronous ingestion outcome. One file's later processing failure does not roll back other files from the accepted batch.
- Document states exposed to administrators are `处理中`, `可用`, `处理失败` and `删除中`. A retry reuses the same document record and reruns the full pipeline.
- Ingestion uses an all-or-nothing publication rule. Parsing, chunking, embedding and vector publication must all succeed before the document becomes searchable. Failure removes artifacts written by the attempt and leaves the entire document unavailable.
- Deleting a document excludes it from new retrieval immediately and then asynchronously removes its source object, parsed chunks and vectors. After cleanup succeeds, its active business record disappears from the document list and its filename becomes reusable. Existing conversations, QA records and feedback remain.
- Source files are stored in MinIO. Java stores business records and opaque object keys in MySQL. Python reads sources using internal storage credentials.
- Parsing prefers headings and natural paragraph boundaries. Oversized content is split using configurable length and overlap parameters. Processing retains stable document and chunk identifiers, original filename, chunk order and PDF page number when available.
- Source metadata needed for future citations is retained in V1, but neither the API nor the ordinary-user interface displays citations.
- All vectors use one Milvus Collection. Each chunk carries `knowledgeBaseId`, `documentId`, `chunkId` and source metadata. Every retrieval applies the current conversation's `knowledgeBaseId` as a mandatory scalar filter.
- V1 uses local `bge-m3` embeddings and dense vector retrieval only. Chunk size, overlap, recall count and refusal threshold are configurable and tuned against the accepted evaluation set.
- V1 does not use keyword retrieval or a reranking model. Retrieval remains behind a replaceable service boundary so later improvements do not redefine the user-facing contract.
- Generation uses Alibaba Cloud Bailian with `qwen-plus` as the default model. Model identity and credentials come from deployment configuration, and generation is hidden behind a replaceable adapter.
- The answer prompt requires concise Chinese, conclusion first, grounding only in retrieved chunks and refusal when evidence is insufficient. General model knowledge cannot be used as fallback.
- An available knowledge base without available documents returns a specific empty-knowledge-base refusal. Documents still processing, failed or deleting are never eligible for retrieval.
- The upload interface displays that user questions and retrieved chunks will be sent to Bailian. V1 performs no automated sensitivity classification, masking, approval or upload blocking; the knowledge administrator makes the upload decision.
- Existing RuoYi users, roles, menus and button permissions are reused. Every logged-in user has ordinary question-answering access; the knowledge administrator role controls knowledge base, document and feedback administration. Anonymous access is not allowed.
- All knowledge administrators can manage every knowledge base, document and submitted feedback. V1 has no knowledge base owner or collaborator model.
- QA records persist the question, complete or partial answer, status and timestamps indefinitely by default. Users can delete their own QA records and conversations.
- Deleting a conversation deletes its QA records and associated feedback content. A content-free audit event may remain. Deleting a source document or knowledge base does not retroactively delete historical QA records.
- Feedback categories are `答案错误`, `未找到答案`, `回答不完整` and `其他`, with an optional user explanation. Feedback state is `待处理` or `已处理` and may include an internal administrator note.
- Feedback submission is the authorization boundary for administrators to view that QA record's question and answer. Unsubmitted QA content is unavailable to administrators, although aggregate statistics and content-free technical metadata remain available.
- Technical logs include correlation identifiers, user and service instance identifiers, processing stage, document and chunk identifiers, retrieval scores, timing, model request identifiers, SSE event progress, retry counts and error stacks. They exclude question text, chunk text, full prompts and generated text.
- Knowledge base, document and feedback administration records actor, target, time and result through the existing operation-audit capability.
- V1 formally supports Chinese documents and Chinese questions. English handling is best effort and is not part of acceptance.
- Trial capacity targets are 1,000 documents, 50 MB per file, 20 GB total source files, 50 ordinary users and five concurrent answer streams. These are acceptance targets rather than values hard-coded into domain logic.
- Performance targets are: 95% of files up to 50 MB become available within five minutes; 95% of healthy-provider questions begin output within five seconds and complete within fifteen seconds.
- Product quality targets are: at least 80% correct conclusions on answerable evaluation questions, at least 90% correct refusals on unanswerable questions and no key facts introduced beyond retrieved content.

## Testing Decisions

- Tests assert externally observable behavior rather than private classes, parsing helper calls, SQL shape, prompt wording or internal step ordering.
- The primary seam is a system-level acceptance suite against the RuoYi external HTTP and SSE APIs. It runs Java and Python with real MySQL, MinIO and Milvus, while injecting a deterministic fake generation model.
- The primary seam covers authentication and role boundaries, knowledge base lifecycle, case-insensitive uniqueness, batch upload validation, asynchronous ingestion states, whole-document publication, retry, immediate retrieval exclusion, document cleanup and knowledge base cascading cleanup.
- The same seam covers conversation creation and renaming, binding to one knowledge base, independent single-turn questions, the one-active-answer rule, SSE event semantics, completion, refusal, mid-stream failure, user cancellation, history persistence and deletion.
- The same seam covers feedback consent, administrator visibility only after submission, feedback processing, preservation of historical QA records after source deletion and removal of feedback content after user deletion.
- A small parser integration suite supplements the primary seam because file-format behavior is difficult to diagnose solely through asynchronous system tests. It supplies representative text-bearing PDF, DOCX, MD and TXT fixtures plus encrypted, corrupt, empty and scanned-only failures, and asserts extracted text and retained source metadata rather than parser-library internals.
- A small real-Milvus integration suite supplements the primary seam to protect the mandatory knowledge base boundary. Given semantically identical or higher-scoring chunks in two knowledge bases, retrieval must return only chunks whose `knowledgeBaseId` matches the selected conversation. Deleted, failed, processing and deleting documents must remain ineligible.
- The generation adapter is replaced with a fake in all automated tests. The fake produces deterministic complete, refused, slow, failed and cancellable streams so tests can assert status transitions and partial persistence without network access, credentials, cost or model nondeterminism.
- Automated tests do not call Alibaba Cloud Bailian and do not add a dedicated API connectivity test. Real provider connectivity is checked by a manual question-answering smoke test after deployment.
- A manually curated Chinese evaluation set contains answerable questions, questions requiring evidence across chunks, unanswerable questions and semantic paraphrases. Evaluation reports conclusion correctness, refusal correctness, unsupported key facts, first-output latency and total latency.
- Quality acceptance requires at least 80% correct conclusions for answerable questions, at least 90% refusal for unanswerable questions and zero unsupported key facts in accepted answers.
- Performance acceptance measures 95th-percentile document ingestion for files up to 50 MB, first SSE output, total answer time and five-stream concurrency under the agreed trial data volume. Bailian outages are excluded from the answer-latency target but must still yield correct error and incomplete-state behavior.
- Privacy tests assert that ordinary users access only their own conversations and QA records, knowledge administrators cannot read unsubmitted QA content, feedback submission exposes only the selected record, and technical-log capture contains none of the prohibited content fields.
- Cleanup tests verify observable absence from retrieval first, then eventual removal from MinIO and Milvus, idempotent retry after interruption and prevention of filename reuse until cleanup completes.
- The current repository contains no prior automated-test structure for the RuoYi backend or Vue frontend. The new suite therefore establishes the first project-specific testing precedent and should remain concentrated at the external system seam rather than creating broad implementation-coupled unit suites.

## Out of Scope

- Document-level, knowledge-base-level, department-level, role-level or user-level retrieval permissions. All logged-in users may select all available knowledge bases in V1.
- Citation display, source cards, source snippets, original-document preview, download or page navigation. V1 retains source metadata only for future work.
- Multi-turn context, pronoun resolution, conversation summarization, question rewriting, retrieval based on previous messages or cross-knowledge-base questions.
- OCR, image understanding, spreadsheet ingestion, scanned-only documents, webpage crawling, third-party knowledge connectors, archive upload and folder upload.
- Document replacement, version history, publishing workflow, approval, taxonomy, tags and classification beyond membership in one knowledge base.
- Content-hash duplicate detection. V1 duplicate validation uses the original filename within a knowledge base.
- Keyword/BM25 retrieval, hybrid retrieval, reranking, multiple embedding models, multiple vector stores and administrator-facing model selection.
- Citation-specific clause parsing or regulation-specific structure recognition. V1 uses general heading, paragraph and length-aware chunking.
- Automated data sensitivity detection, data masking, mandatory confirmation checkbox, security level fields, upload approval, private model deployment and production supplier compliance decisions.
- Knowledge base owners, collaborators, per-knowledge-base administrators and fine-grained management permissions.
- Administrator access to all user questions, employee monitoring, administrator replies to users, in-product feedback notifications, automated answer reruns and external ticket workflow.
- Streaming resume after reconnect, checkpoint resume for document processing and partial-document publication.
- Real Bailian calls in automated tests or a dedicated provider connectivity test.
- Anonymous access, a separate account system and a public knowledge portal.
- Independent landing pages, dashboards, model configuration screens, source editing, answer export and advanced presentation modes.
- High availability, disaster recovery, multi-region deployment, large-scale concurrency and formal production compliance certification.
- Automatic 180-day QA retention and seven-day expiry notification. V1 retains QA records indefinitely unless the user deletes them.
- Formal support or acceptance targets for English documents, English questions or cross-language retrieval.

## Further Notes

- The V1 destination is a small real-team trial that proves the general RAG loop before adding security and citation complexity.
- The initial implementation should preserve the existing Java/Python architectural split and the Bailian egress decision. Domain states and the Java-Python payload contract should be finalized before parallel work begins on asynchronous processing and streaming behavior.
- UI prototyping remains useful for validating the five new product areas: question-answering workspace, conversation history, knowledge base management, per-knowledge-base document management and feedback processing. A prototype may refine layout without changing the behavior in this specification.
- Retrieval and refusal parameters are configuration, not product promises. They should be tuned with the evaluation set, and failure patterns may justify a later decision to add hybrid retrieval or reranking.
- The future citation feature should be able to reuse the source metadata retained during V1 ingestion, avoiding a mandatory full re-ingestion solely to recover document identity, chunk order or PDF page numbers.
- The upload warning is informational. It explicitly does not mean the system has classified a document as safe to send to Bailian.
- Existing obsolete planning artifacts for regulation-only question answering, permission-aware retrieval, clause-level citation and DeepSeek generation are not V1 requirements.
