import Greeting from './components/Greeting'
import PriceGrid from './components/PriceGrid'
import TradeBlotter from './components/TradeBlotter'

function App() {
  return (
    <main className="shell">
      <header className="shell__header">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">
            SDP
          </span>
          <h1 className="brand__title">Single Dealer Platform</h1>
        </div>
        <Greeting />
      </header>
      <div className="shell__body">
        <section className="panel" aria-label="Rates">
          <h2 className="panel__title">Rates</h2>
          <PriceGrid />
          <p className="panel__hint">Double-click a bid to sell, an ask to buy.</p>
        </section>
        <section className="panel" aria-label="Blotter">
          <h2 className="panel__title">Blotter</h2>
          <TradeBlotter />
        </section>
      </div>
    </main>
  )
}

export default App
