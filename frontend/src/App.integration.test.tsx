import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import App from './App'

const WS_URL = 'ws://localhost:8080/ws'

describe('App', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'Hello from the SDP backend!' }))
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ token: 'test-token' }),
      }),
    )
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('renders the backend greeting once logged in and the WebSocket message arrives', async () => {
    render(<App />)

    await userEvent.type(screen.getByLabelText('Username'), 'trader1')
    await userEvent.type(screen.getByLabelText('Password'), 'trader1pass')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByText('Hello from the SDP backend!')).toBeInTheDocument()
  })
})
