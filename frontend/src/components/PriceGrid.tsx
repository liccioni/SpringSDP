import { useEffect, useMemo, useRef, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type CellDoubleClickedEvent,
  type ColDef,
  type GetRowIdParams,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import type { PriceTick } from '../types/priceTick'
import type { Side, TradeRequest } from '../types/tradeRequest'

ModuleRegistry.registerModules([AllCommunityModule])

// No quantity entry or buy/sell ticket yet (MVP 0.2, issues #13/#14), so a
// double-click deals a fixed quantity at the clicked price: hitting the bid
// sells, lifting the ask buys.
const DEFAULT_QUANTITY = 1_000_000
const SIDE_BY_FIELD: Partial<Record<keyof PriceTick, Side>> = { bid: 'SELL', ask: 'BUY' }

const columnDefs: ColDef<PriceTick>[] = [
  { field: 'symbol', headerName: 'Symbol' },
  { field: 'bid', headerName: 'Bid' },
  { field: 'ask', headerName: 'Ask' },
]

function getRowId(params: GetRowIdParams<PriceTick>) {
  return params.data.symbol
}

function PriceGrid() {
  const [prices, setPrices] = useState<Record<string, PriceTick>>({})
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
      quantity: DEFAULT_QUANTITY,
    }
    socketRef.current?.send(JSON.stringify({ type: 'CREATE_TRADE', payload: request }))
  }

  return (
    <div style={{ width: 600 }}>
      <AgGridReact<PriceTick>
        rowData={rowData}
        columnDefs={columnDefs}
        getRowId={getRowId}
        domLayout="autoHeight"
        onCellDoubleClicked={handleCellDoubleClicked}
      />
    </div>
  )
}

export default PriceGrid
