import MarkdownIt from 'markdown-it'

// 单例 markdown-it 实例：
// - html:false：禁止原始 HTML，转义尖括号，防 XSS（LLM 输出非用户直接输入，但仍需防御）
// - breaks:true：单 \n 渲染为 <br>，满足"换行符原格式输出"
// - linkify:true：自动识别链接
// - typographer:false：不替换中文引号等，避免干扰企业文档原文
const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: false
})

// 将 Markdown 文本渲染为 HTML 字符串；空文本返回空字符串。
// 渲染产物只在组件内存 DOM 中使用，不写入持久存储或日志。
export function renderMarkdown(text) {
  if (!text) return ''
  return md.render(String(text))
}
