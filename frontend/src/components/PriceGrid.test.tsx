import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import PriceGrid from './PriceGrid'

const WS_URL = 'ws://localhost:8080/ws'

describe('PriceGrid', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('displays a price tick received over the WebSocket', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'EUR/USD', bid: 1.085, ask: 1.0852, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
    })

    render(<PriceGrid />)

    expect(await screen.findByText('EUR/USD')).toBeInTheDocument()
    expect(await screen.findByText('1.085')).toBeInTheDocument()
    expect(await screen.findByText('1.0852')).toBeInTheDocument()
  })

  it('updates the row in place when a new tick for the same symbol arrives', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'GBP/USD', bid: 1.265, ask: 1.2652, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'GBP/USD', bid: 1.266, ask: 1.2662, timestamp: '2026-01-01T00:00:01Z' },
        }),
      )
    })

    render(<PriceGrid />)

    expect(await screen.findByText('1.266')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('1.265')).not.toBeInTheDocument())
  })

  it('ignores non-PRICE_TICK envelopes', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'hi' }))
    })

    render(<PriceGrid />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('row', { name: /hi/i })).not.toBeInTheDocument()
  })
})
