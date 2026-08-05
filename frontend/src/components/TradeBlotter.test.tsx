import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import TradeBlotter from './TradeBlotter'

const WS_URL = 'ws://localhost:8080/ws'

describe('TradeBlotter', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('displays a trade received over the WebSocket', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'TRADE_CREATED',
          payload: {
            id: 'trade-1',
            symbol: 'EUR/USD',
            side: 'BUY',
            price: 1.0852,
            quantity: 1000000,
            timestamp: '2026-01-01T00:00:00Z',
          },
        }),
      )
    })

    render(<TradeBlotter />)

    expect(await screen.findByText('EUR/USD')).toBeInTheDocument()
    expect(await screen.findByText('BUY')).toBeInTheDocument()
    expect(await screen.findByText('1.0852')).toBeInTheDocument()
    expect(await screen.findByText('1000000')).toBeInTheDocument()
  })

  it('prepends new trades so the most recent appears first', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'TRADE_CREATED',
          payload: {
            id: 'trade-2',
            symbol: 'GBP/USD',
            side: 'SELL',
            price: 1.265,
            quantity: 500000,
            timestamp: '2026-01-01T00:00:00Z',
          },
        }),
      )
      socket.send(
        JSON.stringify({
          type: 'TRADE_CREATED',
          payload: {
            id: 'trade-3',
            symbol: 'USD/JPY',
            side: 'BUY',
            price: 149.5,
            quantity: 250000,
            timestamp: '2026-01-01T00:00:01Z',
          },
        }),
      )
    })

    const { container } = render(<TradeBlotter />)

    await screen.findByText('USD/JPY')
    const symbolCells = Array.from(container.querySelectorAll('[col-id="symbol"]:not([role="presentation"])'))
    const symbolOrder = symbolCells.map((cell) => cell.textContent?.trim())

    expect(symbolOrder).toEqual(['Symbol', 'USD/JPY', 'GBP/USD'])
  })

  it('ignores non-TRADE_CREATED envelopes', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'PRICE_TICK', payload: { symbol: 'NZD/USD', bid: 0.61, ask: 0.6102 } }))
    })

    render(<TradeBlotter />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText('NZD/USD')).not.toBeInTheDocument()
  })
})
