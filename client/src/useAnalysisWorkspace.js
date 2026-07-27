import { useState, useCallback, useMemo, useRef } from 'react'
import { apiRequest } from './api'
import { DEMO_EVALUATION, DEMO_ITEM, DEMO_PLAN, DEMO_RESULT, DEMO_TRACE } from './demoData'
import { renderMarkdown } from './markdown'

const DEFAULT_GOAL = '理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议'
export const GOAL_PRESETS = ['生成学习笔记', '提炼会议结论', '梳理操作步骤']
const STAGE_LABELS = {
  VIDEO_CONTEXT: '解析语音与画面',
  RETRIEVAL: '检索相关证据',
  PLANNER: '拆解分析任务',
  EXECUTOR: '生成结构化结果',
  CRITIC: '核验结论与证据'
}

function createSidebarState() {
  return {
    visible: false,
    type: 'ai',
    mode: 'compose',
    title: '',
    content: '',
    error: '',
    loading: false,
    cancelLoading: false,
    mediaId: null,
    goal: DEFAULT_GOAL,
    playbackUrl: '',
    playbackLoading: false,
    playbackError: '',
    followUp: '',
    followUpLoading: false,
    evidenceQuery: '',
    evidenceLoading: false,
    evidenceResults: [],
    evidenceError: '',
    plan: null,
    trace: null,
    evaluation: null,
    feedback: null,
    feedbackLoading: false,
    editingPlan: false,
    planDraft: [],
    rerunLoading: false
  }
}

export function useAnalysisWorkspace({ demoMode, taskStreams, showMessage, refreshMediaList, findMediaItem }) {
  const [sidebar, setSidebar] = useState(createSidebarState)
  const evidenceRequestVersion = useRef(0)

  const traceStages = useMemo(
    () => Object.entries(sidebar.trace?.stageDurationMs || {})
      .map(([stage, duration]) => [STAGE_LABELS[stage] || stage, formatDuration(duration)]),
    [sidebar.trace]
  )

  const renderedMarkdown = useMemo(
    () => renderMarkdown(sidebar.content),
    [sidebar.content]
  )

  const updateSidebar = useCallback((patch) => {
    setSidebar(prev => ({ ...prev, ...(typeof patch === 'function' ? patch(prev) : patch) }))
  }, [])

  const openSidebar = useCallback((type, title) => {
    updateSidebar({ visible: true, type, title, loading: true, content: '' })
  }, [updateSidebar])

  const closeSidebar = useCallback(() => {
    if (sidebar.type === 'ai' && sidebar.mediaId) {
      saveGoalDraft(sidebar.mediaId, sidebar.goal)
    }
    evidenceRequestVersion.current += 1
    updateSidebar({ visible: false })
  }, [sidebar.type, sidebar.mediaId, sidebar.goal, updateSidebar])

  const loadPlayback = useCallback(async (id) => {
    updateSidebar({ playbackLoading: true, playbackError: '' })
    try {
      const response = await apiRequest(`/media/playback?id=${id}`)
      const url = await response.text()
      if (!response.ok) throw new Error(url || '视频加载失败')
      setSidebar(prev => prev.mediaId === id
        ? { ...prev, playbackUrl: url, playbackError: '' }
        : prev)
    } catch (error) {
      console.warn('Video preview unavailable', error)
      setSidebar(prev => prev.mediaId === id
        ? {
            ...prev,
            playbackUrl: '',
            playbackError: error.message || '原视频暂时无法加载'
          }
        : prev)
    } finally {
      setSidebar(prev => prev.mediaId === id
        ? { ...prev, playbackLoading: false }
        : prev)
    }
  }, [updateSidebar])

  const refreshAgentMetaFull = useCallback(async (id, goal, includeEvaluation) => {
    const params = new URLSearchParams({ id: String(id), goal })
    try {
      const requests = [
        apiRequest(`/analysis/agent-plan?${params}`).then(async r => ({ ok: r.ok, text: r.ok ? await r.text() : '' })),
        apiRequest(`/analysis/agent-trace?${params}`).then(async r => ({ ok: r.ok, text: r.ok ? await r.text() : '' }))
      ]
      if (includeEvaluation) {
        requests.push(
          apiRequest(`/analysis/agent-evaluation?${params}`).then(async r => ({ ok: r.ok, text: r.ok ? await r.text() : '' }))
        )
      }
      const responses = await Promise.all(requests)

      setSidebar(prev => {
        if (prev.mediaId !== id || prev.goal !== goal) return prev
        const next = { ...prev }
        if (responses[0].ok && responses[0].text && !prev.editingPlan) next.plan = JSON.parse(responses[0].text)
        if (responses[1].ok && responses[1].text) next.trace = JSON.parse(responses[1].text)
        if (includeEvaluation && responses[2]?.ok && responses[2].text) next.evaluation = JSON.parse(responses[2].text)
        return next
      })
    } catch (error) {
      console.warn('Agent metadata unavailable', error)
    }
  }, [])

  const startTaskStream = useCallback((id, type, goal = '') => {
    const scope = type === 'ai' ? goal : ''
    const isCurrentTask = (s) => s.mediaId === id && s.type === type && (type !== 'ai' || s.goal === goal)

    const finish = async (result, outcome = 'completed') => {
      setSidebar(prev => {
        if (!prev.visible || !isCurrentTask(prev)) return prev
        if (outcome === 'cancelled' && type === 'ai') {
          return {
            ...prev,
            content: '',
            error: '',
            loading: false,
            cancelLoading: false,
            mode: 'compose'
          }
        }
        if (outcome === 'failed' && type === 'ai') {
          return { ...prev, content: '', error: result, loading: false, mode: 'compose' }
        }
        return { ...prev, content: result, error: '', loading: false }
      })
      if (type === 'ai' && outcome === 'completed') {
        await refreshAgentMetaFull(id, goal, true)
      }
      if (outcome === 'cancelled') {
        showMessage('分析任务已取消')
      } else {
        showMessage(
          outcome === 'failed' ? '任务执行失败，请稍后重试' : '任务完成',
          outcome === 'failed'
        )
      }
      taskStreams.stop(id, type, scope)
    }

    const params = new URLSearchParams({ id: String(id) })
    if (type === 'ai') params.set('goal', goal)
    const path = type === 'ai'
      ? `/analysis/analysis-events?${params}`
      : `/analysis/transcription-events?${params}`

    taskStreams.start(id, type, scope, path, async (status) => {
      setSidebar(prev => {
        if (!isCurrentTask(prev)) return prev
        if (status.message && (status.state === 'PROCESSING' || status.state === 'QUEUED')) {
          return { ...prev, content: status.message }
        }
        return prev
      })
      if (type === 'ai' && status.stage) {
        await refreshAgentMetaFull(id, goal, false)
      }
      if (status.state === 'COMPLETED') {
        await refreshMediaList()
        await finish(status.result || (type === 'ai' ? '分析完成' : ''))
      } else if (status.state === 'FAILED') {
        await finish(status.message || '任务执行失败', 'failed')
      } else if (status.state === 'CANCELLED') {
        await finish(status.message || '分析任务已取消', 'cancelled')
      }
    }, (error) => {
      console.warn('task event stream reconnecting', error)
    })
  }, [refreshAgentMetaFull, showMessage, taskStreams, refreshMediaList])

  const transcribe = useCallback(async (id) => {
    const item = findMediaItem(id)
    if (demoMode) {
      openSidebar('text', 'ASR 转写结果')
      updateSidebar({ content: item?.transcriptText || DEMO_ITEM.transcriptText, loading: false, mediaId: id })
      return
    }
    if (taskStreams.has(id, 'text')) {
      openSidebar('text', '全量文字提取')
      updateSidebar({ mediaId: id, content: '📝 文字提取正在后台进行中...' })
      return
    }
    openSidebar('text', '全量文字提取')
    updateSidebar({ mediaId: id, content: '📝 提取任务已提交，正在识别语音流...' })
    try {
      const current = await apiRequest(`/analysis/transcription-status?id=${id}`)
      if (!current.ok) throw new Error(await current.text())
      const currentStatus = await current.json()
      if (currentStatus.state === 'COMPLETED') {
        updateSidebar({ content: currentStatus.result || '', loading: false })
        return
      }
      if (currentStatus.state === 'QUEUED' || currentStatus.state === 'PROCESSING') {
        startTaskStream(id, 'text')
        return
      }
      const response = await apiRequest(`/analysis/transcribe?id=${id}`, { method: 'POST' })
      if (!response.ok) throw new Error(await response.text())
      startTaskStream(id, 'text')
    } catch (error) {
      updateSidebar({ content: `Error: ${error.message || error}`, loading: false })
    }
  }, [demoMode, findMediaItem, openSidebar, updateSidebar, taskStreams, startTaskStream])

  const analyze = useCallback(async (id, goal) => {
    if (taskStreams.has(id, 'ai', goal)) {
      updateSidebar({ mode: 'result', loading: true })
      return
    }
    updateSidebar({ loading: true, mode: 'result', content: '' })
    try {
      const params = new URLSearchParams({ id: String(id), goal })
      const response = await apiRequest(`/analysis/ai?${params}`, { method: 'POST' })
      const message = await response.text()
      if (response.status === 409) {
        startTaskStream(id, 'ai', goal)
        refreshAgentMetaFull(id, goal, false)
        return
      }
      if (!response.ok) {
        showMessage(message, true)
        updateSidebar({ loading: false, mode: 'compose', error: message })
        return
      }
      startTaskStream(id, 'ai', goal)
      refreshAgentMetaFull(id, goal, false)
    } catch (error) {
      updateSidebar({
        mode: 'compose',
        error: error.message || String(error),
        loading: false
      })
    }
  }, [taskStreams, updateSidebar, showMessage, startTaskStream, refreshAgentMetaFull])

  const cancelAnalysis = useCallback(async () => {
    const id = sidebar.mediaId
    const goal = sidebar.goal.trim()
    if (!id || !goal || sidebar.cancelLoading) return
    if (demoMode) {
      taskStreams.stop(id, 'ai', goal)
      updateSidebar({
        mode: 'compose',
        content: '',
        error: '',
        loading: false,
        cancelLoading: false
      })
      showMessage('分析任务已取消')
      return
    }
    updateSidebar({ cancelLoading: true })
    try {
      const params = new URLSearchParams({ id: String(id), goal })
      const response = await apiRequest(`/analysis/cancel?${params}`, { method: 'POST' })
      const message = await response.text()
      if (!response.ok) throw new Error(message || '取消分析失败')
      taskStreams.stop(id, 'ai', goal)
      updateSidebar({
        mode: 'compose',
        content: '',
        error: '',
        loading: false
      })
      showMessage(message || '取消请求已提交')
    } catch (error) {
      showMessage(error.message || '取消分析失败', true)
    } finally {
      updateSidebar({ cancelLoading: false })
    }
  }, [
    sidebar.mediaId,
    sidebar.goal,
    sidebar.cancelLoading,
    demoMode,
    taskStreams,
    updateSidebar,
    showMessage
  ])

  const openAgent = useCallback(async (item) => {
    evidenceRequestVersion.current += 1
    const goal = loadGoalDraft(item.id)
    setSidebar({
      ...createSidebarState(),
      visible: true,
      title: `Video Agent · ${item.filename}`,
      mediaId: item.id,
      goal
    })
    if (demoMode) return

    loadPlayback(item.id)
    try {
      const params = new URLSearchParams({ id: String(item.id), goal })
      const response = await apiRequest(`/analysis/analysis-status?${params}`)
      if (!response.ok) {
        const detail = await response.text()
        throw new Error(detail || '历史分析状态加载失败')
      }
      const status = await response.json()
      setSidebar(prev => {
        if (prev.mediaId !== item.id || prev.goal !== goal) return prev
        if (status.state === 'COMPLETED') {
          return {
            ...prev,
            mode: 'result',
            content: status.result || '',
            loading: false
          }
        }
        if (status.state === 'QUEUED' || status.state === 'PROCESSING') {
          return {
            ...prev,
            mode: 'result',
            loading: true,
            content: status.message || '正在恢复分析任务...'
          }
        }
        if (status.state === 'FAILED' || status.state === 'CANCELLED') {
          return {
            ...prev,
            mode: 'compose',
            loading: false,
            error: ''
          }
        }
        return prev
      })
      if (status.state === 'COMPLETED') {
        await refreshAgentMetaFull(item.id, goal, true)
      } else if (status.state === 'QUEUED' || status.state === 'PROCESSING') {
        startTaskStream(item.id, 'ai', goal)
        await refreshAgentMetaFull(item.id, goal, false)
      }
    } catch (error) {
      console.warn('Previous analysis unavailable', error)
      setSidebar(prev => (
        prev.mediaId === item.id && prev.goal === goal
          ? {
              ...prev,
              error: error.message || '历史分析状态加载失败，可以重新提交'
            }
          : prev
      ))
    }
  }, [demoMode, loadPlayback, refreshAgentMetaFull, startTaskStream])

  const showDemoResult = useCallback(() => {
    updateSidebar({
      mode: 'result',
      loading: false,
      content: DEMO_RESULT,
      plan: DEMO_PLAN,
      trace: DEMO_TRACE,
      evaluation: DEMO_EVALUATION
    })
  }, [updateSidebar])

  const submitAgent = useCallback(() => {
    const goal = sidebar.goal.trim()
    if (!goal) return
    saveGoalDraft(sidebar.mediaId, goal)
    updateSidebar({ error: '' })
    if (demoMode) {
      updateSidebar({ mode: 'result', loading: true, plan: DEMO_PLAN, trace: DEMO_TRACE })
      setTimeout(showDemoResult, 450)
      return
    }
    analyze(sidebar.mediaId, goal)
  }, [sidebar.goal, sidebar.mediaId, demoMode, updateSidebar, showDemoResult, analyze])

  const startNewAnalysis = useCallback(() => {
    updateSidebar({ mode: 'compose', loading: false, error: '' })
  }, [updateSidebar])

  const startPlanEdit = useCallback(() => {
    updateSidebar(prev => ({ editingPlan: true, planDraft: [...(prev.plan?.tasks || [])] }))
  }, [updateSidebar])

  const cancelPlanEdit = useCallback(() => {
    updateSidebar({ editingPlan: false, planDraft: [] })
  }, [updateSidebar])

  const addPlanTask = useCallback(() => {
    updateSidebar(prev => {
      if (prev.planDraft.length >= 8) return prev
      return { planDraft: [...prev.planDraft, ''] }
    })
  }, [updateSidebar])

  const removePlanTask = useCallback((index) => {
    updateSidebar(prev => {
      if (prev.planDraft.length <= 1) return prev
      const planDraft = [...prev.planDraft]
      planDraft.splice(index, 1)
      return { planDraft }
    })
  }, [updateSidebar])

  const setPlanDraftItem = useCallback((index, value) => {
    updateSidebar(prev => {
      const planDraft = [...prev.planDraft]
      planDraft[index] = value
      return { planDraft }
    })
  }, [updateSidebar])

  const rerunWithPlan = useCallback(async () => {
    const tasks = sidebar.planDraft.map(t => t.trim()).filter(Boolean)
    if (!tasks.length || tasks.length > 5) {
      showMessage('计划需保留 1 至 5 个有效任务', true)
      return
    }
    if (demoMode) {
      updateSidebar({ plan: { ...DEMO_PLAN, tasks }, editingPlan: false, planDraft: [], loading: true })
      setTimeout(showDemoResult, 450)
      return
    }
    updateSidebar({ rerunLoading: true })
    try {
      const response = await apiRequest('/analysis/agent-revise', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mediaId: sidebar.mediaId,
          goal: sidebar.goal,
          correctedTasks: tasks,
          comment: '用户调整 Planner 任务后重新执行'
        })
      })
      const msg = await response.text()
      if (!response.ok) throw new Error(msg || '重新提交失败')
      updateSidebar(prev => ({
        plan: { ...prev.plan, tasks },
        editingPlan: false,
        planDraft: [],
        content: '',
        loading: true
      }))
      startTaskStream(sidebar.mediaId, 'ai', sidebar.goal)
    } catch (error) {
      showMessage(error.message || '重新提交失败', true)
    } finally {
      updateSidebar({ rerunLoading: false })
    }
  }, [sidebar.planDraft, sidebar.mediaId, sidebar.goal, demoMode, updateSidebar, showDemoResult, showMessage, startTaskStream])

  const submitFollowUp = useCallback(async () => {
    const question = sidebar.followUp.trim()
    if (!question) {
      showMessage('请先输入追问内容')
      return
    }
    if (demoMode) {
      updateSidebar(prev => ({
        content: prev.content + `\n\n## 追问\n${question}\n\n根据 08:42 的讲解，迭代写法使用显式栈保存待访问节点，时间复杂度仍为 O(n)，额外空间复杂度为 O(h)。`,
        followUp: ''
      }))
      return
    }
    updateSidebar({ followUpLoading: true })
    try {
      const params = new URLSearchParams({
        id: String(sidebar.mediaId),
        question,
        goal: sidebar.goal
      })
      const response = await apiRequest(`/analysis/follow-up?${params}`, { method: 'POST' })
      const answer = await response.text()
      if (!response.ok) throw new Error(answer || '追问失败')
      updateSidebar(prev => ({
        content: prev.content + `\n\n## 追问\n${question}\n\n${answer}`,
        followUp: ''
      }))
    } catch (error) {
      showMessage(`❌ ${error.message}`, true)
    } finally {
      updateSidebar({ followUpLoading: false })
    }
  }, [sidebar.followUp, sidebar.mediaId, sidebar.goal, demoMode, updateSidebar, showMessage])

  const searchEvidence = useCallback(async () => {
    const query = sidebar.evidenceQuery.trim()
    if (!query) {
      showMessage('请先输入要定位的视频内容')
      return
    }
    if (sidebar.evidenceLoading) return
    const requestVersion = ++evidenceRequestVersion.current
    const mediaId = sidebar.mediaId
    updateSidebar({
      evidenceLoading: true,
      evidenceError: '',
      evidenceResults: []
    })
    try {
      if (demoMode) {
        const results = [{
          startMs: 522000,
          endMs: 582000,
          source: 'ASR+OCR',
          snippet: '迭代遍历使用显式栈保存待访问节点，画面展示了前序遍历顺序。',
          transcript: '迭代遍历使用显式栈保存待访问节点。',
          ocrTexts: ['前序遍历：根节点、左子树、右子树']
        }]
        if (requestVersion === evidenceRequestVersion.current) {
          updateSidebar({ evidenceResults: results })
        }
        return
      }

      const params = new URLSearchParams({ id: String(mediaId), query })
      const response = await apiRequest(`/analysis/evidence-search?${params}`)
      if (!response.ok) {
        const detail = await response.text()
        throw new Error(detail || '视频证据检索失败')
      }
      const results = await response.json()
      if (requestVersion !== evidenceRequestVersion.current) return
      setSidebar(prev => {
        if (prev.mediaId !== mediaId) return prev
        const evidenceResults = Array.isArray(results) ? results : []
        return {
          ...prev,
          evidenceResults,
          evidenceError: evidenceResults.length ? '' : '没有找到匹配的视频证据'
        }
      })
    } catch (error) {
      if (requestVersion !== evidenceRequestVersion.current) return
      setSidebar(prev => prev.mediaId === mediaId
        ? {
            ...prev,
            evidenceResults: [],
            evidenceError: error.message || '视频证据检索失败'
          }
        : prev)
    } finally {
      if (requestVersion === evidenceRequestVersion.current) {
        setSidebar(prev => prev.mediaId === mediaId
          ? { ...prev, evidenceLoading: false }
          : prev)
      }
    }
  }, [
    sidebar.evidenceQuery,
    sidebar.evidenceLoading,
    sidebar.mediaId,
    demoMode,
    updateSidebar,
    showMessage
  ])

  const sendFeedback = useCallback(async (rating) => {
    if (sidebar.feedbackLoading || sidebar.feedback === rating) return
    if (demoMode) {
      updateSidebar({ feedback: rating })
      showMessage('演示反馈已记录')
      return
    }
    updateSidebar({ feedbackLoading: true })
    try {
      const response = await apiRequest('/analysis/agent-feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mediaId: sidebar.mediaId, goal: sidebar.goal, rating })
      })
      if (!response.ok) throw new Error(await response.text())
      updateSidebar({ feedback: rating })
      showMessage('反馈已记录')
    } catch (error) {
      showMessage(`❌ ${error.message}`, true)
    } finally {
      updateSidebar({ feedbackLoading: false })
    }
  }, [
    sidebar.mediaId,
    sidebar.goal,
    sidebar.feedback,
    sidebar.feedbackLoading,
    demoMode,
    updateSidebar,
    showMessage
  ])

  const retryPlayback = useCallback(() => {
    if (sidebar.mediaId && !sidebar.playbackLoading) {
      loadPlayback(sidebar.mediaId)
    }
  }, [sidebar.mediaId, sidebar.playbackLoading, loadPlayback])

  const handlePlaybackError = useCallback(() => {
    if (!sidebar.playbackUrl) return
    updateSidebar({
      playbackUrl: '',
      playbackError: '视频无法播放：可能是播放地址不可用，或视频编码不受当前浏览器支持。请重新加载后再试。'
    })
  }, [sidebar.playbackUrl, updateSidebar])

  const resetWorkspace = useCallback(() => {
    evidenceRequestVersion.current += 1
    setSidebar(createSidebarState())
  }, [])

  const discardMediaWorkspace = useCallback((mediaId) => {
    taskStreams.stopMedia(mediaId)
    try {
      localStorage.removeItem(goalDraftKey(mediaId))
    } catch {
      // Storage being unavailable should not block media deletion.
    }
    setSidebar(prev => {
      if (prev.mediaId !== mediaId) return prev
      evidenceRequestVersion.current += 1
      return createSidebarState()
    })
  }, [taskStreams])

  const formatPercent = (value) => `${Math.round((Number(value) || 0) * 100)}%`

  return {
    sidebar,
    updateSidebar,
    traceStages,
    renderedMarkdown,
    transcribe,
    closeSidebar,
    openAgent,
    submitAgent,
    cancelAnalysis,
    startNewAnalysis,
    showDemoResult,
    startPlanEdit,
    cancelPlanEdit,
    addPlanTask,
    removePlanTask,
    setPlanDraftItem,
    rerunWithPlan,
    submitFollowUp,
    searchEvidence,
    sendFeedback,
    retryPlayback,
    handlePlaybackError,
    resetWorkspace,
    discardMediaWorkspace,
    formatPercent
  }
}

function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${Math.round(milliseconds)} 毫秒`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} 秒`
}

function goalDraftKey(mediaId) {
  return `videotrace:goal:${mediaId}`
}

function loadGoalDraft(mediaId) {
  try {
    return localStorage.getItem(goalDraftKey(mediaId)) || DEFAULT_GOAL
  } catch {
    return DEFAULT_GOAL
  }
}

function saveGoalDraft(mediaId, goal) {
  if (!mediaId || !goal?.trim()) return
  try {
    localStorage.setItem(goalDraftKey(mediaId), goal.trim())
  } catch {
    // Private browsing can disable storage; the current session still works.
  }
}
