import { useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import {
  AllCommunityModule,
  ModuleRegistry,
  type ColDef,
  type GetRowIdParams,
  type ICellRendererParams,
  type RowClassRules,
} from 'ag-grid-community'
import { connect } from '../services/socket'
import { tradingTheme } from '../theme/tradingTheme'
import type { Trade } from '../types/trade'

ModuleRegistry.registerModules([AllCommunityModule])

function SideBadge({ value }: ICellRendererParams<Trade, Trade['side']>) {
  return <span className={`side-badge side-badge--${value?.toLowerCase()}`}>{value}</span>
}

function formatTimeOfDay(timestamp: string): string {
  return timestamp.split('T')[1]?.slice(0, 8) ?? timestamp
}

// Reconciles trades already in state with an incoming batch (a live
// TRADE_CREATED, or the TRADE_HISTORY snapshot on connect), deduplicating by
// id and sorting newest-first. A trade can legitimately arrive in both: history
// is queried asynchronously, so a trade created right around connect time can
// reach the live stream before or after the history snapshot that may already
// include it.
function mergeTrades(current: Trade[], incoming: Trade[]): Trade[] {
  const byId = new Map(current.map((trade) => [trade.id, trade]))
  for (const trade of incoming) {
    byId.set(trade.id, trade)
  }
  return [...byId.values()].sort((a, b) => b.timestamp.localeCompare(a.timestamp))
}

const columnDefs: ColDef<Trade>[] = [
  { field: 'timestamp', headerName: 'Time', valueFormatter: (params) => formatTimeOfDay(params.value), flex: 0.8 },
  { field: 'symbol', headerName: 'Symbol', cellClass: 'cell-symbol', flex: 1 },
  { field: 'side', headerName: 'Side', cellRenderer: SideBadge, flex: 0.7 },
  { field: 'price', headerName: 'Price', type: 'rightAligned', flex: 1 },
  { field: 'quantity', headerName: 'Quantity', type: 'rightAligned', flex: 1.1 },
]

function getRowId(params: GetRowIdParams<Trade>) {
  return params.data.id
}

interface TradeBlotterProps {
  token: string
}

function TradeBlotter({ token }: TradeBlotterProps) {
  const [trades, setTrades] = useState<Trade[]>([])

  useEffect(() => {
    const socket = connect(
      (envelope) => {
        if (envelope.type === 'TRADE_CREATED') {
          const trade = envelope.payload as Trade
          setTrades((current) => mergeTrades(current, [trade]))
        } else if (envelope.type === 'TRADE_HISTORY') {
          const history = envelope.payload as Trade[]
          setTrades((current) => mergeTrades(current, history))
        }
      },
      token,
      () => {
        socket.send(JSON.stringify({ type: 'GET_TRADE_HISTORY' }))
      },
    )

    return () => socket.close()
  }, [token])

  const rowClassRules = useMemo<RowClassRules<Trade>>(
    () => ({
      'trade-row--new': (params) => params.data?.id === trades[0]?.id,
    }),
    [trades],
  )

  return (
    <div className="panel__grid">
      <AgGridReact<Trade>
        theme={tradingTheme}
        rowData={trades}
        columnDefs={columnDefs}
        getRowId={getRowId}
        domLayout="autoHeight"
        rowClassRules={rowClassRules}
      />
    </div>
  )
}

export default TradeBlotter
