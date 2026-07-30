"""PDF 测试夹具生成工具。

使用 pypdf 的低级 API 生成测试用 PDF 字节内容，避免引入额外的测试依赖。
所有 PDF 使用 Type0 字体（STSong-Light + UniGB-UCS2-H CMap），
该字体同时支持 ASCII 与中文字符，便于验证解析器对中文文本的提取能力。
"""

import io

from pypdf import PdfReader, PdfWriter
from pypdf.generic import (
    ArrayObject,
    ContentStream,
    DictionaryObject,
    NameObject,
    NumberObject,
    TextStringObject,
)


def _add_text_page(writer: PdfWriter, text: str) -> None:
    """向 writer 添加一页带文本内容的 PDF 页面，使用 CJK 字体。"""
    page = writer.add_blank_page(width=612, height=792)
    hex_bytes = text.encode("utf-16-be").hex()
    content = f"BT /F1 12 Tf 72 700 Td <{hex_bytes}> Tj ET".encode()
    stream = ContentStream(None, writer)
    stream.set_data(content)
    page[NameObject("/Contents")] = writer._add_object(stream)

    descendant_font = DictionaryObject()
    descendant_font[NameObject("/Type")] = NameObject("/Font")
    descendant_font[NameObject("/Subtype")] = NameObject("/CIDFontType2")
    descendant_font[NameObject("/BaseFont")] = NameObject("/STSong-Light")
    cid_system = DictionaryObject()
    cid_system[NameObject("/Registry")] = TextStringObject("Adobe")
    cid_system[NameObject("/Ordering")] = TextStringObject("GB1")
    cid_system[NameObject("/Supplement")] = NumberObject(4)
    descendant_font[NameObject("/CIDSystemInfo")] = cid_system
    descendant_ref = writer._add_object(descendant_font)

    font = DictionaryObject()
    font[NameObject("/Type")] = NameObject("/Font")
    font[NameObject("/Subtype")] = NameObject("/Type0")
    font[NameObject("/BaseFont")] = NameObject("/STSong-Light")
    font[NameObject("/Encoding")] = NameObject("/UniGB-UCS2-H")
    font[NameObject("/DescendantFonts")] = ArrayObject([descendant_ref])
    font_ref = writer._add_object(font)

    resources = DictionaryObject()
    resources[NameObject("/Font")] = DictionaryObject({NameObject("/F1"): font_ref})
    page[NameObject("/Resources")] = resources


def make_text_pdf(text: str, page_count: int = 1) -> bytes:
    """生成带文本层的 PDF，每页包含指定文本。"""
    writer = PdfWriter()
    for _ in range(page_count):
        _add_text_page(writer, text)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def make_multi_page_pdf(texts: list[str]) -> bytes:
    """生成多页 PDF，每页文本可不同。"""
    writer = PdfWriter()
    for text in texts:
        _add_text_page(writer, text)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def make_encrypted_pdf(text: str, password: str = "secret") -> bytes:
    """生成加密 PDF，空密码无法解密。"""
    pdf_bytes = make_text_pdf(text)
    reader = PdfReader(io.BytesIO(pdf_bytes))
    writer = PdfWriter(clone_from=reader)
    writer.encrypt(password)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def make_blank_pdf() -> bytes:
    """生成无文本的空白 PDF（模拟扫描版 PDF 无文本层）。"""
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()
