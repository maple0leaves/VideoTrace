import { useRef } from 'react'
import { GOAL_PRESETS } from '../useAnalysisWorkspace'

export default function AnalysisSidebar({
  sidebar,
  updateSidebar,
  traceStages,
  renderedMarkdown,
  onClose,
  onSubmitAgent,
  onCancelAnalysis,
  onStartNewAnalysis,
  onStartPlanEdit,
  onCancelPlanEdit,
  onAddPlanTask,
  onRemovePlanTask,
  onSetPlanDraftItem,
  onRerunWithPlan,
  onSubmitFollowUp,
  onSearchEvidence,
  onSendFeedback,
  onRetryPlayback,
  onPlaybackError,
  showMessage,
  formatPercent
}) {
  const videoRef = useRef(null)
  if (!sidebar.visible) return null

  const isAiCompose = sidebar.type === 'ai' && sidebar.mode === 'compose'
  const isLoading = sidebar.loading

  const seekVideo = (seconds) => {
    if (!Number.isFinite(seconds)) return
    const player = videoRef.current
    if (!player) {
      showMessage('原视频尚未加载完成', true)
      return
    }
    if (player.readyState === 0) {
      player.addEventListener('loadedmetadata', () => seekVideo(seconds), { once: true })
      return
    }
    const maxTime = Number.isFinite(player.duration)
      ? Math.max(0, player.duration - 0.1)
      : Number.MAX_SAFE_INTEGER
    player.currentTime = Math.min(Math.max(0, seconds), maxTime)
    player.play().catch(() => {})
    player.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }

  const handleMarkdownClick = (event) => {
    const link = event.target.closest('a[href^="#video-t="]')
    if (!link) return
    event.preventDefault()
    seekVideo(Number(link.getAttribute('href').split('=')[1]))
  }

  return (
    <>
      <div className="sidebar-backdrop" onClick={onClose}></div>
      <div
        className={`sidebar-panel${sidebar.visible ? ' is-open' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={sidebar.title}
      >
        <div className="sidebar-header">
          <div className="sidebar-title" title={sidebar.title}>
            <span className="icon">
              {sidebar.type === 'ai' ? (
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path>
                  <path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path>
                  <path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path>
                </svg>
              ) : (
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                  <polyline points="14 2 14 8 20 8"></polyline>
                  <line x1="16" y1="13" x2="8" y2="13"></line>
                  <line x1="16" y1="17" x2="8" y2="17"></line>
                  <polyline points="10 9 9 9 8 9"></polyline>
                </svg>
              )}
            </span>
            <span className="sidebar-title-text">{sidebar.title}</span>
          </div>
          <button className="close-btn" onClick={onClose} aria-label="关闭分析面板">×</button>
        </div>

        <div className="sidebar-body">
          {sidebar.type === 'ai' && (
            <div className="video-evidence">
              {sidebar.playbackUrl ? (
                <video
                  ref={videoRef}
                  src={sidebar.playbackUrl}
                  controls
                  preload="metadata"
                  onError={onPlaybackError}
                />
              ) : sidebar.playbackLoading ? (
                <div className="video-evidence-loading">正在载入原视频...</div>
              ) : sidebar.playbackError ? (
                <div className="video-evidence-error" role="alert">
                  <span>{sidebar.playbackError}</span>
                  <button type="button" onClick={onRetryPlayback}>重新加载</button>
                </div>
              ) : null}
              {sidebar.playbackUrl && <p>点击分析结果中的时间戳，可跳转到对应画面</p>}
            </div>
          )}

          {isAiCompose && (
            <div className="agent-composer">
              <p className="agent-caption">告诉 Agent 你希望从视频中得到什么产物</p>
              {sidebar.error && <p className="inline-error" role="alert">{sidebar.error}</p>}
              <textarea
                value={sidebar.goal}
                maxLength={500}
                placeholder="例如：梳理核心观点，给出带时间戳的证据和可执行建议"
                onChange={(e) => updateSidebar({ goal: e.target.value })}
              />
              <div className="goal-presets">
                {GOAL_PRESETS.map(preset => (
                  <button key={preset} onClick={() => updateSidebar({ goal: preset })}>{preset}</button>
                ))}
              </div>
              <button className="agent-run-btn" disabled={!sidebar.goal.trim()} onClick={onSubmitAgent}>
                开始分析
              </button>
            </div>
          )}

          {isLoading && (
            <div className="agent-running">
              <div className="loading-state">
                <div className="quantum-loader small"></div>
                <p>Agent 正在分析视频证据...</p>
              </div>
              {sidebar.type === 'ai' && (
                <button
                  type="button"
                  className="agent-cancel-btn"
                  disabled={sidebar.cancelLoading}
                  onClick={onCancelAnalysis}
                >
                  {sidebar.cancelLoading ? '正在取消...' : '取消分析'}
                </button>
              )}
              {sidebar.plan?.tasks?.length > 0 && (
                <div className="agent-meta-block">
                  <span className="meta-label">任务计划</span>
                  <ol>{sidebar.plan.tasks.map(task => <li key={task}>{task}</li>)}</ol>
                </div>
              )}
              {traceStages.length > 0 && (
                <div className="agent-meta-block">
                  <span className="meta-label">已完成阶段</span>
                  <div className="stage-list">
                    {traceStages.map(([k, v]) => <span key={k}>{k} · {v}</span>)}
                  </div>
                </div>
              )}
            </div>
          )}

          {!isAiCompose && !isLoading && (
            <>
              {sidebar.type === 'ai' ? (
                <>
                  <div className="result-actions">
                    <button type="button" onClick={onStartNewAnalysis}>更换分析目标</button>
                  </div>

                  <div className="evidence-search">
                    <div className="evidence-search-form">
                      <input
                        value={sidebar.evidenceQuery}
                        maxLength={500}
                        aria-label="视频证据检索"
                        placeholder="定位 PPT、字幕、代码或某段讲解"
                        onChange={(event) => updateSidebar({ evidenceQuery: event.target.value })}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter') onSearchEvidence()
                        }}
                      />
                      <button
                        type="button"
                        disabled={sidebar.evidenceLoading}
                        onClick={onSearchEvidence}
                      >
                        {sidebar.evidenceLoading ? '检索中' : '定位证据'}
                      </button>
                    </div>
                    {sidebar.evidenceError && (
                      <p className="evidence-search-error" aria-live="polite">
                        {sidebar.evidenceError}
                      </p>
                    )}
                    {sidebar.evidenceResults.length > 0 && (
                      <div className="evidence-search-results" aria-live="polite">
                        {sidebar.evidenceResults.map((hit) => (
                          <button
                            key={`${hit.startMs}-${hit.endMs}-${hit.source || ''}`}
                            type="button"
                            onClick={() => seekVideo(Number(hit.startMs) / 1000)}
                          >
                            <strong>{formatEvidenceTime(hit.startMs)}</strong>
                            <small>{hit.source || '视频证据'}</small>
                            <span>{hit.snippet || '该时间段暂无可展示文本'}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  <div
                    className="markdown-content"
                    onClick={handleMarkdownClick}
                    dangerouslySetInnerHTML={{ __html: renderedMarkdown }}
                  />

                  {(sidebar.plan?.tasks?.length > 0 || traceStages.length > 0) && (
                    <div className="agent-inspector">
                      {sidebar.plan?.tasks?.length > 0 && (
                        <div className="agent-meta-block">
                          <span className="meta-label">Planner 任务</span>
                          {sidebar.editingPlan ? (
                            <div className="plan-editor">
                              {sidebar.planDraft.map((task, i) => (
                                <div key={i} className="plan-editor-row">
                                  <input
                                    value={task}
                                    maxLength={500}
                                    aria-label={`任务 ${i + 1}`}
                                    onChange={(e) => onSetPlanDraftItem(i, e.target.value)}
                                  />
                                  <button type="button" title="删除任务" onClick={() => onRemovePlanTask(i)}>×</button>
                                </div>
                              ))}
                              {sidebar.planDraft.length < 8 && (
                                <button type="button" onClick={onAddPlanTask}>添加任务</button>
                              )}
                              <div className="plan-editor-actions">
                                <button type="button" onClick={onCancelPlanEdit}>取消</button>
                                <button type="button" disabled={sidebar.rerunLoading} onClick={onRerunWithPlan}>
                                  {sidebar.rerunLoading ? '提交中' : '按新计划重跑'}
                                </button>
                              </div>
                            </div>
                          ) : (
                            <>
                              <ol>{sidebar.plan.tasks.map(task => <li key={task}>{task}</li>)}</ol>
                              <button type="button" className="plan-edit-trigger" onClick={onStartPlanEdit}>
                                调整计划
                              </button>
                            </>
                          )}
                        </div>
                      )}

                      {traceStages.length > 0 && (
                        <div className="agent-meta-block">
                          <span className="meta-label">执行轨迹</span>
                          <div className="stage-list">
                            {traceStages.map(([k, v]) => <span key={k}>{k} · {v}</span>)}
                          </div>
                        </div>
                      )}

                      {sidebar.evaluation && Object.keys(sidebar.evaluation).length > 0 && (
                        <div className="quality-row">
                          <span>结构完整 {sidebar.evaluation.structuredValid ? '通过' : '待完善'}</span>
                          <span>证据支持 {formatPercent(sidebar.evaluation.evidenceSupportRate)}</span>
                          <span>Critic {sidebar.evaluation.criticPassed ? '通过' : '达到轮次上限'}</span>
                        </div>
                      )}
                    </div>
                  )}

                  <div className="follow-up-box">
                    <textarea
                      value={sidebar.followUp}
                      maxLength={500}
                      placeholder="基于视频继续追问..."
                      onChange={(e) => updateSidebar({ followUp: e.target.value })}
                    />
                    <button
                      disabled={sidebar.followUpLoading}
                      onClick={onSubmitFollowUp}
                    >
                      {sidebar.followUpLoading ? '分析中' : '追问'}
                    </button>
                  </div>

                  <div className="feedback-row">
                    <span>这个结果有帮助吗？</span>
                    <button
                      className={sidebar.feedback === 1 ? 'active' : ''}
                      disabled={sidebar.feedbackLoading}
                      onClick={() => onSendFeedback(1)}
                      title="有帮助"
                    >
                      赞
                    </button>
                    <button
                      className={sidebar.feedback === -1 ? 'active' : ''}
                      disabled={sidebar.feedbackLoading}
                      onClick={() => onSendFeedback(-1)}
                      title="需改进"
                    >
                      踩
                    </button>
                  </div>
                </>
              ) : (
                <div className="text-content"><pre>{sidebar.content}</pre></div>
              )}
            </>
          )}
        </div>
      </div>
    </>
  )
}

function formatEvidenceTime(timestampMs) {
  const seconds = Math.max(0, Math.floor(Number(timestampMs) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const time = `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
  return hours ? `${String(hours).padStart(2, '0')}:${time}` : time
}
