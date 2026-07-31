import assert from 'node:assert/strict'
import test from 'node:test'

import { renderMarkdownMarkup } from './markdown.js'

test('groups loose video evidence content into the preceding timestamp card', () => {
  const markdown = `## 视频证据

- [00:30] OCR：实验一、基本门电路

这段补充说明必须留在 00:30 的证据卡片中。

- [01:30] OCR：连接输入引脚
- [03:20] ASR：使用逻辑代数进行变换

A+B => A+B => A

1. 仿照老师的演示完成实验。
2. 独立重复步骤并记录操作方式。

## 主要步骤

1. 完成实验。
`

  const html = renderMarkdownMarkup(markdown)
  const cards = [...html.matchAll(/<div class="evidence-card">([\s\S]*?)<\/div>/g)]

  assert.equal(cards.length, 2)
  assert.match(cards[0][1], /#video-t=30/)
  assert.match(cards[0][1], /补充说明必须留在/)
  assert.match(cards[1][1], /#video-t=90/)
  assert.match(cards[1][1], /#video-t=200/)
  assert.match(cards[1][1], /A\+B =&gt; A\+B =&gt; A/)
  assert.match(cards[1][1], /独立重复步骤/)
  assert.ok(html.indexOf('主要步骤') > html.indexOf('</div></div>'))
})

test('does not add evidence cards when the section has no timestamp', () => {
  const html = renderMarkdownMarkup(`## 视频证据

暂时没有可定位的证据。

## 总结

内容保持普通 Markdown 格式。
`)

  assert.doesNotMatch(html, /evidence-card/)
  assert.match(html, /暂时没有可定位的证据/)
})
