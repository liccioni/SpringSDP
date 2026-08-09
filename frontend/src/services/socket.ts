import type { Envelope } from '../types/envelope'

const DEFAULT_WS_URL = 'ws://localhost:8080/ws'
const DEFAULT_LOGIN_URL = 'http://localhost:8080/oauth2/authorization/keycloak'

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
