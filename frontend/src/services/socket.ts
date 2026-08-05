import type { Envelope } from '../types/envelope'

const DEFAULT_WS_URL = 'ws://localhost:8080/ws'

export function connect(onMessage: (envelope: Envelope) => void): WebSocket {
  const url = import.meta.env.VITE_WS_URL ?? DEFAULT_WS_URL
  const socket = new WebSocket(url)

  socket.addEventListener('message', (event) => {
    const envelope = JSON.parse(event.data) as Envelope
    onMessage(envelope)
  })

  return socket
}
