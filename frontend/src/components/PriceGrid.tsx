import { useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import { AllCommunityModule, ModuleRegistry, type ColDef, type GetRowIdParams } from 'ag-grid-community'
import { connect } from '../services/socket'
import type { PriceTick } from '../types/priceTick'

ModuleRegistry.registerModules([AllCommunityModule])

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

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'PRICE_TICK') {
        const tick = envelope.payload as PriceTick
        setPrices((current) => ({ ...current, [tick.symbol]: tick }))
      }
    })

    return () => socket.close()
  }, [])

  const rowData = useMemo(() => Object.values(prices), [prices])

  return (
    <div style={{ width: 600 }}>
      <AgGridReact<PriceTick> rowData={rowData} columnDefs={columnDefs} getRowId={getRowId} domLayout="autoHeight" />
    </div>
  )
}

export default PriceGrid
