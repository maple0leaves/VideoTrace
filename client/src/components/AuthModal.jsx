import { useCallback, useEffect, useState } from 'react'
import { apiRequest, setAuthToken } from '../api'

export default function AuthModal({ onClose, onLoginSuccess }) {
  const [authMode, setAuthMode] = useState('login')
  const [authLoading, setAuthLoading] = useState(false)
  const [authMessage, setAuthMessage] = useState('')
  const [authError, setAuthError] = useState(false)
  const [form, setForm] = useState({ username: '', password: '', nickname: '' })
  const [captcha, setCaptcha] = useState(null)
  const [captchaCode, setCaptchaCode] = useState('')
  const [captchaLoading, setCaptchaLoading] = useState(false)

  const updateForm = (field, value) => setForm(prev => ({ ...prev, [field]: value }))

  const loadCaptcha = useCallback(async () => {
    setCaptchaLoading(true)
    try {
      const response = await apiRequest('/auth/captcha')
      if (!response.ok) throw new Error('captcha request failed')
      setCaptcha(await response.json())
      setCaptchaCode('')
    } catch {
      setCaptcha(null)
      setCaptchaCode('')
      setAuthMessage('验证码加载失败，请点击重试')
      setAuthError(true)
    } finally {
      setCaptchaLoading(false)
    }
  }, [])

  useEffect(() => {
    if (authMode === 'register') {
      void loadCaptcha()
    }
  }, [authMode, loadCaptcha])

  const switchMode = () => {
    const nextMode = authMode === 'login' ? 'register' : 'login'
    setAuthMode(nextMode)
    setAuthMessage('')
    setAuthError(false)
    if (nextMode === 'login') {
      setCaptcha(null)
      setCaptchaCode('')
    }
  }

  const handleAuth = async () => {
    if (!form.username || !form.password) {
      setAuthMessage('请输入完整的账号和密码')
      setAuthError(true)
      return
    }
    const registering = authMode === 'register'
    if (registering && (!captcha?.captcha_id || !captchaCode.trim())) {
      setAuthMessage('请输入图形验证码')
      setAuthError(true)
      return
    }

    setAuthLoading(true)
    setAuthMessage('')
    const endpoint = registering ? '/user/register' : '/user/login'
    const payload = registering
      ? {
          ...form,
          captcha_id: captcha.captcha_id,
          captcha_code: captchaCode.trim().toUpperCase()
        }
      : form
    let refreshCaptcha = false
    try {
      const res = await apiRequest(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      const data = await res.json()
      if (data.code === 200) {
        if (!registering) {
          setAuthToken(data.token)
          onLoginSuccess(data.userInfo)
          onClose()
        } else {
          setAuthMessage('注册成功，请直接登录')
          setAuthError(false)
          setTimeout(switchMode, 1000)
        }
      } else {
        setAuthMessage(data.msg || '操作失败')
        setAuthError(true)
        refreshCaptcha = registering
      }
    } catch {
      setAuthMessage('网络连接错误')
      setAuthError(true)
      refreshCaptcha = registering
    } finally {
      setAuthLoading(false)
      if (refreshCaptcha) {
        void loadCaptcha()
      }
    }
  }

  return (
    <div className="auth-backdrop">
      <div className="auth-panel">
        <div className="auth-header">
          <h2 className="auth-title">{authMode === 'login' ? '用户登录' : '新用户注册'}</h2>
          <button className="close-btn" onClick={onClose}>×</button>
        </div>
        <div className="auth-body">
          <div className="input-group">
            <label>USERNAME</label>
            <input type="text" placeholder="输入账号" value={form.username} onChange={(e) => updateForm('username', e.target.value)} />
          </div>
          <div className="input-group">
            <label>PASSWORD</label>
            <input type="password" placeholder="输入密码" value={form.password} onChange={(e) => updateForm('password', e.target.value)} />
          </div>
          {authMode === 'register' && (
            <>
              <div className="input-group">
                <label>NICKNAME (昵称)</label>
                <input type="text" placeholder="设置一个好听的名字" value={form.nickname} onChange={(e) => updateForm('nickname', e.target.value)} />
              </div>
              <div className="input-group">
                <label>CAPTCHA (图形验证码)</label>
                <div className="captcha-control">
                  <input
                    type="text"
                    placeholder="输入 4 位字符"
                    maxLength={4}
                    autoComplete="off"
                    spellCheck={false}
                    value={captchaCode}
                    onChange={(e) => setCaptchaCode(e.target.value.toUpperCase())}
                  />
                  <button
                    type="button"
                    className="captcha-image-button"
                    onClick={loadCaptcha}
                    disabled={captchaLoading}
                    aria-label="点击刷新图形验证码"
                  >
                    {captcha?.image && !captchaLoading
                      ? <img src={captcha.image} alt="图形验证码，点击刷新" />
                      : <span>{captchaLoading ? '加载中...' : '点击重试'}</span>}
                  </button>
                </div>
                <small className="captcha-hint">看不清？点击图片刷新</small>
              </div>
            </>
          )}
          <div className="auth-action">
            <button className="cyber-btn" onClick={handleAuth} disabled={authLoading || captchaLoading}>
              {authLoading ? '请求处理中...' : (authMode === 'login' ? '立即登录' : '提交注册')}
            </button>
          </div>
          <div className="auth-toggle">
            <span className="toggle-text">{authMode === 'login' ? '没有账号?' : '已有账号?'}</span>
            <button className="toggle-link" onClick={switchMode}>
              {authMode === 'login' ? '去注册' : '去登录'}
            </button>
          </div>
          {authMessage && (
            <p className={`auth-msg${authError ? ' error' : ''}`}>{authMessage}</p>
          )}
        </div>
      </div>
    </div>
  )
}
