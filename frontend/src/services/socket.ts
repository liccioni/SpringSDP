import type { Envelope } from '../types/envelope'

const DEFAULT_WS_URL = 'ws://localhost:8080/ws'
const DEFAULT_LOGIN_URL = 'http://localhost:8080/oauth2/authorization/keycloak'
const DEFAULT_LOGOUT_URL = 'http://localhost:8080/logout'
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

// Identity now rides on the Spring Session cookie set by the Keycloak login
// redirect (see ADR 0020), not a token - the cookie is attached to the WS
// handshake automatically. If the connection closes without ever opening,
// there was no valid session, so redirect to start the login flow.
export function connect(onMessage: (envelope: Envelope) => void, onOpen?: () => void): WebSocket {
  const baseUrl = import.meta.env.VITE_WS_URL ?? DEFAULT_WS_URL
  const socket = new WebSocket(baseUrl)
  let opened = false

  socket.addEventListener('open', () => {
    opened = true
    onOpen?.()
  })

  socket.addEventListener('message', (event) => {
    const envelope = JSON.parse(event.data) as Envelope
    onMessage(envelope)
  })

  socket.addEventListener('close', () => {
    if (!opened) {
      window.location.href = import.meta.env.VITE_LOGIN_URL ?? DEFAULT_LOGIN_URL
    }
  })

  return socket
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

// Submits a real <form> POST (not fetch/XHR) so no CORS configuration is
// needed for the cross-origin request to the Gateway - see ADR 0023. The
// CSRF token rides along as a hidden form field named "_csrf" (Spring
// Security's default parameter name), read from the XSRF-TOKEN cookie
// CsrfCookieWebFilter (gateway) forces to be written on every request.
export function logout(): void {
  const logoutUrl = import.meta.env.VITE_LOGOUT_URL ?? DEFAULT_LOGOUT_URL
  const csrfToken = readCookie(CSRF_COOKIE_NAME) ?? ''

  const form = document.createElement('form')
  form.method = 'POST'
  form.action = logoutUrl

  const csrfInput = document.createElement('input')
  csrfInput.type = 'hidden'
  csrfInput.name = '_csrf'
  csrfInput.value = csrfToken
  form.appendChild(csrfInput)

  document.body.appendChild(form)
  form.submit()
}
