"""回答流程的服务端诊断：统一日志脱敏、失败分类与完成汇总。

网关/浏览器只看到已脱敏的失败类别；原始异常、trace 与知识库 id 等诊断
信息留在服务端日志，绝不进入 SSE 流或任何对外响应。
"""

import logging

from sage_vault_rag.model.privacy import mask_failure_detail, mask_sensitive

logger = logging.getLogger(__name__)


class AnswerDiagnostics:
    """绑定一次回答的跨语言 trace，集中输出脱敏后的服务端日志。

    失败分类与日志脱敏只在这里实现，回答执行各阶段通过本对象上报，
    避免把脱敏规则散落在编排与生成生命周期中。
    """

    def __init__(self, trace_id: str, generation_id: str, knowledge_base_id: int) -> None:
        self._trace_id = trace_id
        self._generation_id = generation_id
        self._knowledge_base_id = knowledge_base_id

    def failure(self, stage: str, error: BaseException) -> str:
        """记录一次阶段失败并返回对外的受控失败类别。

        trace 标识同时出现在 Python 服务日志与 Java 网关错误日志，用于把浏览器
        看到的 Failed 事件关联到服务端诊断，而不把诊断本身泄漏出去。
        """
        logger.error(
            "Answer generation failed: trace=%s generation_id=%s knowledge_base_id=%s stage=%s error=%s",
            self._trace_id,
            self._generation_id,
            self._knowledge_base_id,
            stage,
            mask_sensitive(str(error)),
            exc_info=error,
        )
        return mask_failure_detail(error)

    def completed(self, retrieved: int, durations: dict[str, int]) -> None:
        logger.info(
            "Answer completed: trace=%s generation_id=%s knowledge_base_id=%s "
            "retrieved=%d durations=%s",
            self._trace_id,
            self._generation_id,
            self._knowledge_base_id,
            retrieved,
            mask_sensitive(str(durations)),
        )
