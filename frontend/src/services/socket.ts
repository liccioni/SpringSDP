import type { Envelope } from '../types/envelope'

const DEFAULT_WS_URL = 'ws://localhost:8080/ws'
const DEFAULT_LOGIN_URL = 'http://localhost:8080/oauth2/authorization/keycloak'
const DEFAULT_LOGOUT_URL = 'http://localhost:8080/logout'
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

// Bounded retry-with-backoff before concluding a handshake failure means
// "not authenticated" and redirecting to Keycloak login (issue #128): a
// concurrent WebSocket handshake against this app's own session can lose a
// benign, transient race (Spring Session's id-rotation on re-authentication)
// and close without ever opening - retrying lets that resolve itself
// instead of escalating straight into a login redirect.
const MAX_CONNECT_ATTEMPTS = 3
const RETRY_DELAYS_MS = [300, 900]

export type EnvelopeHandler = (envelope: Envelope) => void

// One shared connection for the whole app (issue #128) - every component
// used to open its own, which turned a rare backend race into a near-
// certainty on every page load. Identity rides on the Spring Session cookie
// set by the Keycloak login redirect (see ADR 0020), attached to the WS
// handshake automatically.
let socket: WebSocket | null = null
let connectAttempt = 0
let retryTimer: ReturnType<typeof setTimeout> | null = null
// Bumped on every openConnection() call and on resetForTests(); a close
// handler only acts on its own connection's outcome if this still matches -
// otherwise it belongs to an attempt this module has already moved past.
let connectionGeneration = 0
const sendQueue: string[] = []
const handlersByType = new Map<string, Set<EnvelopeHandler>>()

function dispatch(envelope: Envelope): void {
  handlersByType.get(envelope.type)?.forEach((handler) => handler(envelope))
}

function openConnection(): void {
  const generation = ++connectionGeneration
  const baseUrl = import.meta.env.VITE_WS_URL ?? DEFAULT_WS_URL
  const ws = new WebSocket(baseUrl)
  let opened = false
  socket = ws

  ws.addEventListener('open', () => {
    opened = true
    connectAttempt = 0
    while (sendQueue.length) {
      ws.send(sendQueue.shift()!)
    }
  })

  ws.addEventListener('message', (event) => {
    dispatch(JSON.parse(event.data) as Envelope)
  })

  ws.addEventListener('close', () => {
    if (socket === ws) {
      socket = null
    }
    if (generation !== connectionGeneration || opened) {
      return
    }
    connectAttempt += 1
    if (connectAttempt < MAX_CONNECT_ATTEMPTS) {
      retryTimer = setTimeout(() => {
        retryTimer = null
        openConnection()
      }, RETRY_DELAYS_MS[connectAttempt - 1])
    } else {
      window.location.href = import.meta.env.VITE_LOGIN_URL ?? DEFAULT_LOGIN_URL
    }
  })
}

function ensureConnected(): void {
  if (!socket && retryTimer === null) {
    openConnection()
  }
}

// Registers handler for one envelope type on the app's single shared
// connection. Returns an unsubscribe function. Establishes the shared
// connection on first call if it doesn't exist yet.
export function subscribe(type: string, handler: EnvelopeHandler): () => void {
  ensureConnected()
  let handlers = handlersByType.get(type)
  if (!handlers) {
    handlers = new Set()
    handlersByType.set(type, handlers)
  }
  handlers.add(handler)
  return () => handlers.delete(handler)
}

// Sends a message on the shared connection, queueing it (in order) if the
// connection hasn't opened yet.
export function send(message: { type: string; payload: unknown; correlationId?: string }): void {
  ensureConnected()
  const text = JSON.stringify(message)
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(text)
  } else {
    sendQueue.push(text)
  }
}

// Test-only: tears down the shared connection (and cancels any pending
// retry) so the next subscribe()/send() call in the same test file opens a
// fresh connection against that test's own mock server. Never called from
// production code - the shared connection otherwise lives for the app's
// whole lifetime.
export function resetForTests(): void {
  connectionGeneration += 1
  if (retryTimer !== null) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  socket?.close()
  socket = null
  sendQueue.length = 0
  connectAttempt = 0
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
