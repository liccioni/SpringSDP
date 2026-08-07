import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type ColDef,
  type GetRowIdParams,
  type ICellRendererParams,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import { tradingTheme } from '../theme/tradingTheme'
import type { PriceTick } from '../types/priceTick'
import type { Side, TradeRequest } from '../types/tradeRequest'

ModuleRegistry.registerModules([AllCommunityModule])

const DEFAULT_QUANTITY = 1_000_000

// The backend now only streams PRICE_TICK for symbols a connection has subscribed
// to. There's no symbol-discovery message yet, so this list must be kept in sync
// with MarketDataService's tradable symbols on the backend.
const KNOWN_SYMBOLS = ['EUR/USD', 'GBP/USD', 'USD/JPY']

function getRowId(params: GetRowIdParams<PriceTick>) {
  return params.data.symbol
}

interface PriceGridProps {
  token: string
}

function PriceGrid({ token }: PriceGridProps) {
  const [prices, setPrices] = useState<Record<string, PriceTick>>({})
  const [quantity, setQuantity] = useState<number>(DEFAULT_QUANTITY)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    const socket = connect(
      (envelope) => {
        if (envelope.type === 'PRICE_TICK') {
          const tick = envelope.payload as PriceTick
          setPrices((current) => ({ ...current, [tick.symbol]: tick }))
        }
      },
      token,
      () => {
        for (const symbol of KNOWN_SYMBOLS) {
          socket.send(JSON.stringify({ type: 'SUBSCRIBE', payload: { symbol } }))
        }
      },
    )
    socketRef.current = socket

    return () => socket.close()
  }, [token])

  const rowData = useMemo(() => Object.values(prices), [prices])

  function sendTrade(tick: PriceTick, side: Side, price: number) {
    const request: TradeRequest = { symbol: tick.symbol, side, price, quantity }
    socketRef.current?.send(JSON.stringify({ type: 'CREATE_TRADE', payload: request }))
  }

  function handleQuantityChanged(event: ChangeEvent<HTMLInputElement>) {
    const parsed = Number(event.target.value)
    if (!Number.isNaN(parsed)) {
      setQuantity(parsed)
    }
  }

  const columnDefs: ColDef<PriceTick>[] = [
    { field: 'symbol', headerName: 'Symbol', cellClass: 'cell-symbol', flex: 1.6 },
    { field: 'bid', headerName: 'Bid', type: 'rightAligned', enableCellChangeFlash: true, flex: 1 },
    { field: 'ask', headerName: 'Ask', type: 'rightAligned', enableCellChangeFlash: true, flex: 1 },
    {
      headerName: 'Sell',
      width: 68,
      resizable: false,
      cellRenderer: (params: ICellRendererParams<PriceTick>) =>
        params.data && (
          <button
            type="button"
            className="side-button side-button--sell"
            onClick={() => sendTrade(params.data as PriceTick, 'SELL', (params.data as PriceTick).bid)}
          >
            Sell
          </button>
        ),
    },
    {
      headerName: 'Buy',
      width: 68,
      resizable: false,
      cellRenderer: (params: ICellRendererParams<PriceTick>) =>
        params.data && (
          <button
            type="button"
            className="side-button side-button--buy"
            onClick={() => sendTrade(params.data as PriceTick, 'BUY', (params.data as PriceTick).ask)}
          >
            Buy
          </button>
        ),
    },
  ]

  return (
    <div className="panel__grid">
      <div className="trade-ticket">
        <label htmlFor="quantity" className="trade-ticket__label">
          Quantity
        </label>
        <input
          id="quantity"
          type="number"
          min={0}
          step={1}
          className="trade-ticket__quantity"
          value={quantity}
          onChange={handleQuantityChanged}
        />
      </div>
      <AgGridReact<PriceTick>
        theme={tradingTheme}
        rowData={rowData}
        columnDefs={columnDefs}
        getRowId={getRowId}
        domLayout="autoHeight"
        cellFlashDuration={150}
        cellFadeDuration={350}
      />
    </div>
  )
}

export default PriceGrid
