export default function Navbar({ currentUser, uploading, onOpenAuth, onLogout }) {
  return (
    <header className="navbar">
      <div className="nav-content">
        <div className="brand">
          <span className="brand-vido">Vido</span>
          <span className="brand-trace">Trace</span>
          <span className="beta-badge">PRO</span>
        </div>
        <div className="nav-controls">
          {!currentUser ? (
            <button className="auth-btn" onClick={onOpenAuth}>
              <span className="btn-icon">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                  <circle cx="12" cy="7" r="4"></circle>
                </svg>
              </span>
              登录 / 注册
            </button>
          ) : (
            <div className="user-profile">
              <span className="user-name">:: {currentUser.nickname} ::</span>
              <button className="logout-btn" onClick={onLogout} title="退出登录">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
                  <polyline points="16 17 21 12 16 7"></polyline>
                  <line x1="21" y1="12" x2="9" y2="12"></line>
                </svg>
              </button>
            </div>
          )}
          <div className={`status-pill${uploading ? ' is-active' : ''}`}>
            <div className="status-dot"></div>
            <span className="status-text">{uploading ? '数据传输中...' : '系统就绪'}</span>
          </div>
        </div>
      </div>
    </header>
  )
}
