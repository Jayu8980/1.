# AI Trading System — Architecture

Hybrid design required by SEBI's retail-algo / static-IP rules: **all orders leave from one
registered static IP (Mumbai VM)**. The Android app is a controller and viewer; it never holds
broker credentials or the TOTP secret.

```mermaid
flowchart LR
  subgraph Phone["Android app (Kotlin/Compose, MVVM, Hilt, Room)"]
    UI[Screens + widget] --> VM[ViewModels]
    VM --> Repo[Repositories]
    Repo --> Room[(Room cache<br/>offline view)]
    Repo -->|HTTPS REST + WSS<br/>JWT + TLS pinning| GW
    Bio[BiometricPrompt<br/>for money actions] --> Repo
    KS[Android Keystore] --> Repo
    FCM[FCM receiver] --> UI
  end

  subgraph VM_Mumbai["Mumbai VM — static IP registered in SmartAPI"]
    GW[FastAPI API<br/>JWT, audit] --> BUS{{Async event bus}}
    BUS --> DATA[Data agent] --> REG[Regime agent] --> SIG[Signal agents]
    SIG --> META[Meta-model<br/>LightGBM + calibration]
    LLM[LLM analyst<br/>sentiment features only] -.-> META
    META --> PORT[Portfolio / sizing]
    PORT --> RISK[Risk engine<br/>ABSOLUTE VETO]
    RISK --> EXEC[Execution gateway]
    EXEC --> IPG[Static-IP guard]
    IPG --> RL[Rate limiter ≤8 OPS]
    RL --> SA[Broker adapters<br/>Angel One · Zerodha · Upstox · Dhan · Fyers]
    EXEC --> PAPER[Paper broker]
    MON[Monitor / drift] --> BUS
    AUD[(Hash-chained<br/>audit log)]
    EXEC --> AUD
    SESS[Session mgr<br/>daily logout + TOTP login] --> SA
  end

  SA -->|REST| ANGEL[(Active broker API)]
  MON -->|push| FCMS[Firebase Cloud Messaging] --> FCM
```

## Order path (fail closed at every step)
`kill switch → mode → risk veto → (LIVE) static-IP check → rate limiter → broker → audit`

LIVE requires **three** locks: server env `ENGINE_ALLOW_LIVE=true`, typed confirmation phrase,
and (from the app) a biometric-gated request. The kill switch cancels working orders, drops
to PAPER and blocks re-arming until released.

## Repo layout
```
/engine     Python 3.11 FastAPI engine (Phase 1 done: broker layer + paper broker)
  app/core        enums, models, audit log, rate limiter, static-IP guard
  app/broker      Broker interface, PaperBroker, cost model, smartapi/{client,session,broker}
  app/execution   ExecutionGateway, risk veto contract
  tests/          pytest, SmartAPI fully mocked with respx
/android    Kotlin/Compose app, signed APK in android/dist/
/infra      VM bootstrap, Caddy, systemd, firewall (Phase 6)
/docs/trading   this document, BROKERS.md (multi-broker setup)
```

## Phases
| # | Scope | Status |
|---|-------|--------|
| 1 | Broker layer, paper broker, costs, compliance primitives | **done, 39 tests** |
| 2 | Data pipeline (corp actions, holidays, survivorship-free universe) + event-driven backtester | next |
| 3 | Regime, strategies, meta-labeling, sizing, full risk engine, validation suite | |
| 4 | API (REST/WSS, JWT) + Android core screens | **app done** (demo mode); engine API pending |
| 5 | FCM alerts, widget, AI analyst | widget + local alerts done; FCM pending |
| 6 | Deployment, TLS pinning, hardening | |
