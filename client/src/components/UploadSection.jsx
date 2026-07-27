import { useState, useRef } from 'react'

export default function UploadSection({ uploading, onFileChange, onUrlUpload, message, messageIsError }) {
  const [isDragOver, setIsDragOver] = useState(false)
  const [videoUrl, setVideoUrl] = useState('')
  const fileInputRef = useRef(null)

  const handleDragOver = (e) => { e.preventDefault(); setIsDragOver(true) }
  const handleDragLeave = (e) => { e.preventDefault(); setIsDragOver(false) }
  const handleDrop = (e) => {
    e.preventDefault()
    setIsDragOver(false)
    const file = e.dataTransfer.files?.[0]
    if (file) onFileChange(file)
  }
  const handleFileInputChange = (e) => {
    const file = e.target.files?.[0]
    if (file) onFileChange(file)
    e.target.value = ''
  }
  const handleUrlSubmit = () => {
    if (videoUrl.trim()) onUrlUpload(videoUrl.trim(), () => setVideoUrl(''))
  }

  return (
    <section className="hero-section">
      <h1 className="slogan-main">TRACE YOUR VIDEO</h1>
      <p className="slogan-sub">视频溯源 · 智能解析 · 深度理解</p>

      <div className="upload-wrapper">
        <input
          ref={fileInputRef}
          type="file"
          id="file-input"
          onChange={handleFileInputChange}
          accept="video/*"
          hidden
        />
        <div
          className={`upload-magnet${uploading ? ' processing' : ''}${isDragOver ? ' is-dragover' : ''}`}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          {!uploading ? (
            <div className="split-container">
              <label htmlFor="file-input" className="skew-pane pane-local">
                <div className="pane-content">
                  <div className="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                      <polyline points="17 8 12 3 7 8"></polyline>
                      <line x1="12" y1="3" x2="12" y2="15"></line>
                    </svg>
                  </div>
                  <span className="magnet-title">LOCAL FILE</span>
                  <span className="magnet-desc">{isDragOver ? 'DROP TO UPLOAD' : 'CLICK OR DRAG & DROP'}</span>
                </div>
              </label>

              <div className="split-divider"></div>

              <div className="skew-pane pane-url">
                <div className="pane-content">
                  <div className="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10"></circle>
                      <line x1="2" y1="12" x2="22" y2="12"></line>
                      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                    </svg>
                  </div>
                  <span className="magnet-title">WEB LINK</span>
                  <span className="magnet-desc">BILIBILI · YOUTUBE · DOUYIN</span>
                  <div className="url-input-box" onClick={(e) => e.stopPropagation()}>
                    <input
                      type="text"
                      placeholder="Paste video URL..."
                      value={videoUrl}
                      onChange={(e) => setVideoUrl(e.target.value)}
                      onKeyUp={(e) => e.key === 'Enter' && handleUrlSubmit()}
                    />
                    <button className="url-go-btn" onClick={handleUrlSubmit}>
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="9 18 15 12 9 6"></polyline>
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="magnet-content busy">
              <div className="quantum-loader"></div>
              <span className="busy-text">正在建立通道并解析资源...</span>
            </div>
          )}
        </div>
      </div>

      {message && (
        <div className={`notification-bar${messageIsError ? ' error' : ''}`}>
          {message}
        </div>
      )}
    </section>
  )
}
