# Module Map — SecureTravels CRM

Mermaid `flowchart` of the module layout across phases. Single jar, one
inbound route per module (see `ARCHITECTURE.md`).

```mermaid
flowchart TB
    subgraph phase1["PHASE 1 — built"]
        identity[identity / auth+user]
        leads[leads]
        trips[trips / batches / guides]
        bookings[bookings / travellers / seat_holds]
        payments[payments]
        operations[operations]
        customers[customer360]
        tasks[tasks + notifications]
        audit[audit]
        dashboard[dashboard / targets]
        webhook[webhook — HMAC intake + round-robin]
    end

    subgraph phase2["PHASE 2 — operations & compliance"]
        documents[documents]
        vendors[vendors]
        communications[communications — Interakt WhatsApp + timeline]
    end

    subgraph phase5["PHASE 5 — marketing"]
        marketing[marketing]
    end

    subgraph phase6["PHASE 6 — automation"]
        automation[automation / workflow engine]
    end

    subgraph phase9_10["PHASE 9/10/11 — finance & analytics"]
        finance[finance]
        reporting[reporting / BI + ML]
    end

    common[common — infra: security, audit,
            exceptions, util, types]

    webhook --> leadscoring[(leads + tasks + audit)]
    leads --> bookings
    trips --> bookings
    bookings --> payments
    bookings --> operations
    customers --> bookings
    customers --> leads
    tasks --> audit
    reports{{reports}} --> dashboard

    phase1 --> common
    documents --> leadscoring
    documents --> bookings
    vendors --> operations
    marketing --> leads
    communications --> tasks
    automation --> tasks
    automation --> leads
    finance --> payments
    reporting --> dashboard
    reporting --> payments
    reporting --> leads
    reporting --> operations

    classDef ph1 fill:#d1f0d1,stroke:#2e7d32
    classDef ph2 fill:#dbeafe,stroke:#1e40af
    classDef ph5 fill:#fef3c7,stroke:#b45309
    classDef ph6 fill:#fce7f3,stroke:#a21caf
    classDef ph9 fill:#ede9fe,stroke:#6d28d9
    classDef inf fill:#f3f4f6,stroke:#374151
    class identity,leads,trips,bookings,payments,operations,customers,tasks,audit,dashboard,webhook ph1
    class documents,vendors ph2
    class communications ph2
    class marketing ph5
    class automation ph6
    class finance,reporting ph9
    class common inf
```

Same map, as a phase × module table (the spec authorities are
`PRODUCT_REQUIREMENTS.md` and `ROADMAP.md`):

| Phase | Modules |
|---|---|
| 1 (built) | identity, leads, trips, bookings, payments, operations, customers, tasks, audit, dashboard/+targets, webhook |
| 2 | documents, vendors (+ Redis, RabbitMQ, automation rules full form) |
| 3 | mobile-ops, reporting suite, OpenSearch, Prometheus/Grafana |
| 4 (scale-gated) | Keycloak IAM, ABAC, Vault, multi-branch |
| 4 | communications (Interakt WhatsApp, unified timeline) |
| 5 | marketing (Meta/Google) |
| 6 | automation workflow engine (first Kafka *evaluation*) |
| 7 | sales depth (accounts/opportunities/forecast/commission) |
| 8 (reassess) | service/support |
| 9 | finance (invoicing/payouts/GST/P&L) |
| 10 | advanced BI dashboards |
| 11 | AI (ML scoring/forecast/chatbot) |
| 12 | hardening & scale |