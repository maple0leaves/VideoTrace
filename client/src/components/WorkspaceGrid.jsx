function formatTime(timeStr) {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function MediaCard({ item, onDelete, onDownloadAudio, onTranscribe, onOpenAgent }) {
  return (
    <div className="project-card">
      <button className="delete-btn" onClick={() => onDelete(item)} title="删除此项">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </button>

      <div className="card-meta">
        <div className="meta-icon">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="23 7 16 12 23 17 23 7"></polygon>
            <rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect>
          </svg>
        </div>
        <div className="meta-info">
          <div className="filename-mask" title={item.filename}>{item.filename}</div>
          <div className="meta-tags">
            <span className="time-tag">{formatTime(item.uploadTime)}</span>
            <span className={`status-indicator ${item.status.toLowerCase()}`}>
              {item.status === 'COMPLETED' ? 'READY' : 'PROCESSING'}
            </span>
          </div>
        </div>
      </div>

      <div className="action-dock">
        <button className="dock-item" onClick={() => onDownloadAudio(item)}>
          <span className="item-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 18V5l12-2v13"></path>
              <circle cx="6" cy="18" r="3"></circle>
              <circle cx="18" cy="16" r="3"></circle>
            </svg>
          </span>
          <span className="item-label">下载音频</span>
        </button>

        <button className="dock-item" disabled={item.status !== 'COMPLETED'} onClick={() => onTranscribe(item.id)}>
          <span className="item-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10 9 9 9 8 9"></polyline>
            </svg>
          </span>
          <span className="item-label">提取文字</span>
        </button>

        <button className="dock-item ai-core" disabled={item.status !== 'COMPLETED'} onClick={() => onOpenAgent(item)}>
          <span className="item-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect>
              <rect x="9" y="9" width="6" height="6"></rect>
              <line x1="9" y1="1" x2="9" y2="4"></line>
              <line x1="15" y1="1" x2="15" y2="4"></line>
              <line x1="9" y1="20" x2="9" y2="23"></line>
              <line x1="15" y1="20" x2="15" y2="23"></line>
              <line x1="20" y1="9" x2="23" y2="9"></line>
              <line x1="20" y1="14" x2="23" y2="14"></line>
              <line x1="1" y1="9" x2="4" y2="9"></line>
              <line x1="1" y1="14" x2="4" y2="14"></line>
            </svg>
          </span>
          <div className="label-group">
            <span className="item-label">Video Agent</span>
          </div>
          <div className="shimmer"></div>
        </button>
      </div>
    </div>
  )
}

export default function WorkspaceGrid({ list, onDelete, onDownloadAudio, onTranscribe, onOpenAgent }) {
  if (!list.length) return null
  return (
    <section className="workspace-section">
      <div className="section-header">
        <h3>工作台</h3>
        <div className="count-chip">{list.length} TASKS</div>
      </div>
      <div className="card-grid">
        {list.map(item => (
          <MediaCard
            key={item.id}
            item={item}
            onDelete={onDelete}
            onDownloadAudio={onDownloadAudio}
            onTranscribe={onTranscribe}
            onOpenAgent={onOpenAgent}
          />
        ))}
      </div>
    </section>
  )
}
