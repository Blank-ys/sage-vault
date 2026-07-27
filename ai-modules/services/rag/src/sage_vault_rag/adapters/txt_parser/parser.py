from charset_normalizer import detect


class TxtParser:
    """TXT 解析器，自动探测编码并返回 UTF-8 文本。"""

    async def parse(self, content: bytes, filename: str) -> str:
        if not content:
            return ""
        detected = detect(content)
        encoding = detected.get("encoding") or "utf-8"
        confidence = detected.get("confidence") or 0.0
        decode_error: Exception | None = None
        try:
            text = content.decode(encoding, errors="strict")
        except UnicodeDecodeError as exception:
            decode_error = exception
            text = content.decode("utf-8", errors="replace")
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        if confidence < 0.5 and "\ufffd" in normalized:
            message = f"无法可靠解析 TXT 文件编码: {filename}"
            if decode_error is not None:
                raise ValueError(message) from decode_error
            raise ValueError(message)
        return normalized
