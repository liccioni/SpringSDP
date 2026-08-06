import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type CellDoubleClickedEvent,
  type ColDef,
  type GetRowIdParams,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import { tradingTheme } from '../theme/tradingTheme'
import type { PriceTick } from '../types/priceTick'
import type { Side, TradeRequest } from '../types/tradeRequest'

ModuleRegistry.registerModules([AllCommunityModule])

// No explicit buy/sell ticket yet (MVP 0.2 issue #14), so a double-click
// deals the entered quantity at the clicked price: hitting the bid sells,
// lifting the ask buys.
const DEFAULT_QUANTITY = 1_000_000
const SIDE_BY_FIELD: Partial<Record<keyof PriceTick, Side>> = { bid: 'SELL', ask: 'BUY' }

const columnDefs: ColDef<PriceTick>[] = [
  { field: 'symbol', headerName: 'Symbol', cellClass: 'cell-symbol', flex: 1 },
  { field: 'bid', headerName: 'Bid', type: 'rightAligned', enableCellChangeFlash: true, flex: 1 },
  { field: 'ask', headerName: 'Ask', type: 'rightAligned', enableCellChangeFlash: true, flex: 1 },
]

function getRowId(params: GetRowIdParams<PriceTick>) {
  return params.data.symbol
}

function PriceGrid() {
  const [prices, setPrices] = useState<Record<string, PriceTick>>({})
  const [quantity, setQuantity] = useState<number>(DEFAULT_QUANTITY)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'PRICE_TICK') {
        const tick = envelope.payload as PriceTick
        setPrices((current) => ({ ...current, [tick.symbol]: tick }))
      }
    })
    socketRef.current = socket

    return () => socket.close()
  }, [])

  const rowData = useMemo(() => Object.values(prices), [prices])

  function handleCellDoubleClicked(event: CellDoubleClickedEvent<PriceTick>) {
    const field = event.colDef.field as keyof PriceTick | undefined
    const side = field && SIDE_BY_FIELD[field]
    if (!side || !event.data) {
      return
    }

    const request: TradeRequest = {
      symbol: event.data.symbol,
      side,
      price: event.data[field] as number,
      quantity,
    }
    socketRef.current?.send(JSON.stringify({ type: 'CREATE_TRADE', payload: request }))
  }

  function handleQuantityChanged(event: ChangeEvent<HTMLInputElement>) {
    const parsed = Number(event.target.value)
    if (!Number.isNaN(parsed)) {
      setQuantity(parsed)
    }
  }

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
        onCellDoubleClicked={handleCellDoubleClicked}
      />
    </div>
  )
}

export default PriceGrid
