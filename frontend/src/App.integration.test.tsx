import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import App from './App'

const WS_URL = 'ws://localhost:8080/ws'

describe('App', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'Hello, trader1!' }))
    })
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('renders the backend greeting once the WebSocket message arrives, with no login step', async () => {
    render(<App />)

    expect(await screen.findByText('Hello, trader1!')).toBeInTheDocument()
  })
})
