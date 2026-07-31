import { marked } from 'marked'

const ALLOWED_TAGS = new Set([
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'P', 'BR', 'HR', 'BLOCKQUOTE',
  'UL', 'OL', 'LI', 'STRONG', 'EM', 'DEL', 'CODE', 'PRE', 'A',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'DIV'
])

export function renderMarkdown(markdown) {
  if (!markdown) return ''

  const template = document.createElement('template')
  template.innerHTML = renderMarkdownMarkup(markdown)
  template.content.querySelectorAll('*').forEach(sanitizeNode)
  return template.innerHTML
}

export function renderMarkdownMarkup(markdown) {
  if (!markdown) return ''

  let cleanText = markdown.replace(/<think>[\s\S]*?<\/think>/gi, '')
  if (cleanText.includes('</think>')) cleanText = cleanText.split('</think>').pop()
  if (!cleanText.trim()) cleanText = markdown
  cleanText = linkVideoTimestamps(cleanText)

  const tokens = marked.lexer(cleanText)
  return renderTokenStream(tokens)
}

function renderTokenStream(tokens) {
  const html = []
  const links = tokens.links || {}
  let index = 0

  while (index < tokens.length) {
    const token = tokens[index]
    if (!isVideoEvidenceHeading(token)) {
      html.push(parseTokens([token], links))
      index += 1
      continue
    }

    html.push(parseTokens([token], links))
    index += 1

    const sectionTokens = []
    while (index < tokens.length && !isTopLevelSectionHeading(tokens[index])) {
      sectionTokens.push(tokens[index])
      index += 1
    }
    html.push(renderEvidenceSection(sectionTokens, links))
  }

  return html.join('')
}

function renderEvidenceSection(tokens, links) {
  const contentBeforeFirstCard = []
  const cards = []
  let currentCard = null

  for (const token of tokens) {
    if (startsEvidenceCard(token)) {
      if (currentCard) cards.push(currentCard)
      currentCard = []
    }

    if (currentCard) currentCard.push(token)
    else contentBeforeFirstCard.push(token)
  }

  if (currentCard) cards.push(currentCard)
  if (!cards.length) return parseTokens(tokens, links)

  return [
    parseTokens(contentBeforeFirstCard, links),
    '<div class="evidence-card-list">',
    ...cards.map((card) => `<div class="evidence-card">${parseTokens(card, links)}</div>`),
    '</div>'
  ].join('')
}

function parseTokens(tokens, links) {
  const tokenStream = [...tokens]
  tokenStream.links = links
  return marked.parser(tokenStream)
}

function isVideoEvidenceHeading(token) {
  if (token.type !== 'heading' || token.depth !== 2) return false
  const heading = String(token.text || '').replace(/\s+/g, '')
  return heading.includes('视频证据') || /videoevidence/i.test(heading)
}

function isTopLevelSectionHeading(token) {
  return token.type === 'heading' && token.depth <= 2
}

function startsEvidenceCard(token) {
  return ['list', 'paragraph', 'blockquote'].includes(token.type)
    && tokenContainsTimestamp(token)
}

function tokenContainsTimestamp(value) {
  if (!value || typeof value !== 'object') return false
  if (value.type === 'link' && String(value.href || '').startsWith('#video-t=')) {
    return true
  }

  return ['tokens', 'items'].some((key) => (
    Array.isArray(value[key]) && value[key].some(tokenContainsTimestamp)
  ))
}

function sanitizeNode(node) {
  if (!ALLOWED_TAGS.has(node.tagName)) {
    node.replaceWith(document.createTextNode(node.textContent || ''))
    return
  }

  for (const attribute of [...node.attributes]) {
    const allowed = node.tagName === 'A'
      && (attribute.name === 'href' || attribute.name === 'title')
      || node.tagName === 'DIV'
      && attribute.name === 'class'
      && ['evidence-card-list', 'evidence-card'].includes(attribute.value)
    if (!allowed) node.removeAttribute(attribute.name)
  }
  if (node.tagName !== 'A') return

  const href = node.getAttribute('href') || ''
  if (!/^(https?:|mailto:|\/|#)/i.test(href)) node.removeAttribute('href')
  node.setAttribute('rel', 'noopener noreferrer')
  if (!href.startsWith('#video-t=')) node.setAttribute('target', '_blank')
}

function linkVideoTimestamps(markdown) {
  return markdown.replace(/\[((?:\d{1,2}:)?\d{1,2}:\d{2})\](?!\()/g, (match, timestamp) => {
    const parts = timestamp.split(':').map(Number)
    const seconds = parts.length === 3
      ? parts[0] * 3600 + parts[1] * 60 + parts[2]
      : parts[0] * 60 + parts[1]
    return `[${timestamp}](#video-t=${seconds})`
  })
}
