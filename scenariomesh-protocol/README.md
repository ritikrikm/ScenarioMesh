# scenariomesh-protocol

This module defines the coordinator ↔ worker wire contract.

## What it contains

The protocol models worker HELLO/registration, capabilities, commands, results, work-unit and lease identity, heartbeat/presence messages, control messages, protocol versions, and negotiation metadata.

Current compatibility uses a stable bootstrap handshake so a current runtime can negotiate with the preserved bridge-v8 baseline while newer peers negotiate the current session protocol.

## Session flow

```text
worker connects
   ↓
HELLO on bootstrap protocol
   ↓
coordinator validates worker + capabilities
   ↓
choose highest mutually supported session version
   ↓
lock session protocol
   ↓
RUN / HEARTBEAT / RESULT / DRAIN / STOP
```

## Safety rules

Protocol version is a session property after negotiation. A peer must not silently change versions mid-session. Lease/work-unit identity is authoritative for accepting terminal results. Presence is liveness information and must never create or renew a work lease.

Keep compatibility behavior centralized here and at the protocol boundary; do not scatter `if version == ...` assumptions across unrelated scheduling or adapter code.
