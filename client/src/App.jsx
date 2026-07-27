import { useState, useEffect, useCallback } from 'react'
import { apiRequest, clearAuthToken, hasAuthToken, setAuthToken } from './api'
import { uploadVideoInChunks } from './chunkUpload'
import { DEMO_ITEM } from './demoData'
import { createTaskStreams } from './taskEvents'
import { useAnalysisWorkspace } from './useAnalysisWorkspace'
import Navbar from './components/Navbar'
import UploadSection from './components/UploadSection'
import WorkspaceGrid from './components/WorkspaceGrid'
import AnalysisSidebar from './components/AnalysisSidebar'
import AuthModal from './components/AuthModal'

const DEMO_MODE = new URLSearchParams(window.location.search).has('demo')
const taskStreams = createTaskStreams()

export default function App() {
  const [message, setMessage] = useState('')
  const [messageIsError, setMessageIsError] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [list, setList] = useState([])
  const [currentUser, setCurrentUser] = useState(null)
  const [showAuthModal, setShowAuthModal] = useState(false)

  const showMsg = useCallback((msg, isError = false) => {
    setMessage(msg)
    setMessageIsError(isError)
    setTimeout(() => {
      setMessage(prev => prev === msg ? '' : prev)
      if (!isError) setMessageIsError(false)
    }, 4000)
  }, [])

  const fetchList = useCallback(async () => {
    if (DEMO_MODE) return
    try {
      const res = await apiRequest(`/media/list?_t=${Date.now()}`)
      if (!res.ok) throw new Error('加载视频列表失败')
      setList(await res.json())
    } catch {
      // silent
    }
  }, [])

  const {
    sidebar, updateSidebar, traceStages, renderedMarkdown,
    transcribe, closeSidebar, openAgent, submitAgent, cancelAnalysis, showDemoResult,
    startNewAnalysis,
    startPlanEdit, cancelPlanEdit, addPlanTask, removePlanTask, setPlanDraftItem,
    rerunWithPlan, submitFollowUp, searchEvidence, sendFeedback,
    retryPlayback, handlePlaybackError, resetWorkspace, discardMediaWorkspace,
    formatPercent
  } = useAnalysisWorkspace({
    demoMode: DEMO_MODE,
    taskStreams,
    showMessage: showMsg,
    refreshMediaList: fetchList,
    findMediaItem: (id) => list.find(item => item.id === id)
  })

  const handleFileUpload = useCallback(async (file) => {
    if (!currentUser) {
      showMsg('⚠️ 权限受限：请先登录系统', true)
      setShowAuthModal(true)
      return
    }
    if (!file.type.startsWith('video/')) {
      showMsg('⚠️ 仅支持上传视频文件', true)
      return
    }
    if (DEMO_MODE) { showMsg('演示模式：已模拟完成分片上传'); return }
    setUploading(true)
    try {
      const uploadedMedia = await uploadVideoInChunks(file, (progress) => {
        setMessageIsError(false)
        setMessage(progress.phase === 'merging'
          ? '分片上传完成，正在合并文件...'
          : `正在上传分片 ${progress.completedChunks}/${progress.totalChunks}...`)
      })
      showMsg('✅ 本地上传完成')
      await fetchList()
      openAgent(uploadedMedia)
    } catch (error) {
      showMsg('❌ 上传失败: ' + error.message, true)
    } finally {
      setUploading(false)
    }
  }, [currentUser, showMsg, fetchList, openAgent])

  const handleUrlUpload = useCallback(async (url, onSuccess) => {
    if (DEMO_MODE) { onSuccess?.(); showMsg('演示模式：已模拟完成链接解析'); return }
    if (!currentUser) {
      showMsg('⚠️ 权限受限：请先登录系统', true)
      setShowAuthModal(true)
      return
    }
    let parsed
    try { parsed = new URL(url) } catch { parsed = null }
    if (!parsed || !['http:', 'https:'].includes(parsed.protocol)) {
      showMsg('⚠️ 请输入合法的 http/https 链接', true)
      return
    }
    setUploading(true)
    setMessageIsError(false)
    setMessage('正在解析链接并极速下载 (低码率模式)...')
    const formData = new FormData()
    formData.append('url', url)
    try {
      const res = await apiRequest('/media/upload-url', { method: 'POST', body: formData })
      if (!res.ok) throw new Error(await res.text())
      const uploadedMedia = await res.json()
      showMsg('✅ 链接资源已入库')
      onSuccess?.()
      await fetchList()
      openAgent(uploadedMedia)
    } catch (error) {
      let errMsg = error.message
      if (errMsg.includes('Unsupported URL')) errMsg = '不支持该平台链接'
      showMsg('❌ 解析失败: ' + errMsg, true)
    } finally {
      setUploading(false)
    }
  }, [currentUser, showMsg, fetchList, openAgent])

  const deleteItem = useCallback(async (item) => {
    if (DEMO_MODE) {
      setList(prev => prev.filter(i => i.id !== item.id))
      discardMediaWorkspace(item.id)
      showMsg('演示任务已移除')
      return
    }
    if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return
    try {
      const res = await apiRequest(`/media/delete?id=${item.id}`, { method: 'DELETE' })
      const text = await res.text()
      if (text === '删除成功') {
        showMsg('文件已销毁')
        setList(prev => prev.filter(i => i.id !== item.id))
        discardMediaWorkspace(item.id)
      } else {
        showMsg('❌ ' + text, true)
      }
    } catch {
      showMsg('❌ 删除请求失败', true)
    }
  }, [showMsg, discardMediaWorkspace])

  const downloadAudio = useCallback(async (item) => {
    if (DEMO_MODE) { showMsg(`演示模式：${item.filename} 音频已准备`); return }
    const fileName = (item.filename || 'audio').replace(/\.[^/.]+$/, '') + '.mp3'
    try {
      showMsg('正在转码并下载...')
      const res = await apiRequest(`/analysis/download?id=${item.id}`)
      if (!res.ok) throw new Error('Fail')
      const blob = await res.blob()
      const dlUrl = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = dlUrl
      link.download = fileName
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(dlUrl)
      showMsg('✅ 下载完成')
    } catch {
      alert('下载失败')
    }
  }, [showMsg])

  const handleLoginSuccess = useCallback((userInfo) => {
    setCurrentUser(userInfo)
    localStorage.setItem('user', JSON.stringify(userInfo))
    showMsg(`欢迎回来，${userInfo.nickname}`)
    fetchList()
  }, [showMsg, fetchList])

  const logout = useCallback(() => {
    if (hasAuthToken()) apiRequest('/user/logout', { method: 'POST' }).catch(() => {})
    taskStreams.stopAll()
    setCurrentUser(null)
    localStorage.removeItem('user')
    clearAuthToken()
    setList([])
    resetWorkspace()
    showMsg('已退出系统')
  }, [showMsg, resetWorkspace])

  useEffect(() => {
    const handleAuthExpired = () => {
      taskStreams.stopAll()
      setCurrentUser(null)
      setList([])
      resetWorkspace()
      localStorage.removeItem('user')
      showMsg('登录状态已失效，请重新登录', true)
      setShowAuthModal(true)
    }
    window.addEventListener('auth-expired', handleAuthExpired)

    if (DEMO_MODE) {
      setCurrentUser({ id: 1, nickname: 'Agent Demo' })
      setList([DEMO_ITEM])
      openAgent(DEMO_ITEM)
      showDemoResult()
    } else {
      const saved = localStorage.getItem('user')
      if (saved && hasAuthToken()) {
        try { setCurrentUser(JSON.parse(saved)) } catch {}
      }
      fetchList()
    }
    return () => {
      window.removeEventListener('auth-expired', handleAuthExpired)
      taskStreams.stopAll()
    }
  }, [])

  return (
    <div className="app-stage">
      <div className="ambient-noise"></div>
      <div className="ambient-glow"></div>

      <Navbar
        currentUser={currentUser}
        uploading={uploading}
        onOpenAuth={() => setShowAuthModal(true)}
        onLogout={logout}
      />

      <main className="main-container">
        <UploadSection
          uploading={uploading}
          onFileChange={handleFileUpload}
          onUrlUpload={handleUrlUpload}
          message={message}
          messageIsError={messageIsError}
        />

        <WorkspaceGrid
          list={list}
          onDelete={deleteItem}
          onDownloadAudio={downloadAudio}
          onTranscribe={transcribe}
          onOpenAgent={openAgent}
        />
      </main>

      <AnalysisSidebar
        sidebar={sidebar}
        updateSidebar={updateSidebar}
        traceStages={traceStages}
        renderedMarkdown={renderedMarkdown}
        onClose={closeSidebar}
        onSubmitAgent={submitAgent}
        onCancelAnalysis={cancelAnalysis}
        onStartNewAnalysis={startNewAnalysis}
        onStartPlanEdit={startPlanEdit}
        onCancelPlanEdit={cancelPlanEdit}
        onAddPlanTask={addPlanTask}
        onRemovePlanTask={removePlanTask}
        onSetPlanDraftItem={setPlanDraftItem}
        onRerunWithPlan={rerunWithPlan}
        onSubmitFollowUp={submitFollowUp}
        onSearchEvidence={searchEvidence}
        onSendFeedback={sendFeedback}
        onRetryPlayback={retryPlayback}
        onPlaybackError={handlePlaybackError}
        showMessage={showMsg}
        formatPercent={formatPercent}
      />

      {showAuthModal && (
        <AuthModal
          onClose={() => setShowAuthModal(false)}
          onLoginSuccess={handleLoginSuccess}
        />
      )}
    </div>
  )
}
