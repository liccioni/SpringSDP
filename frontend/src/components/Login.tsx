import { useState, type FormEvent } from 'react'

const DEFAULT_LOGIN_URL = 'http://localhost:8080/login'

interface LoginProps {
  onLogin: (token: string) => void
}

function Login({ onLogin }: LoginProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    try {
      const url = import.meta.env.VITE_LOGIN_URL ?? DEFAULT_LOGIN_URL
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })

      if (!response.ok) {
        setError('Invalid username or password')
        return
      }

      const body = (await response.json()) as { token: string }
      onLogin(body.token)
    } catch {
      setError('Could not reach the backend')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-shell">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">
            SDP
          </span>
          <h1 className="brand__title">Single Dealer Platform</h1>
        </div>

        <label htmlFor="username" className="login-card__label">
          Username
        </label>
        <input
          id="username"
          className="login-card__input"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          autoComplete="username"
          required
        />

        <label htmlFor="password" className="login-card__label">
          Password
        </label>
        <input
          id="password"
          type="password"
          className="login-card__input"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          required
        />

        {error && (
          <p role="alert" className="login-card__error">
            {error}
          </p>
        )}

        <button type="submit" className="login-card__submit" disabled={submitting}>
          {submitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
    </main>
  )
}

export default Login
