@AGENTS.md

# Claude Code specifics

- The speed rules in AGENTS.md "Process" (Noah, 2026-11-13) set how much checking a change gets:
  - **Strict tier:** saves, billing, refunds, undo, and crash recovery. A gate or "is this done" check there is verified by an independent
    `gate-verifier` agent, never by the builder itself.
  - **Light tier:** unit tests plus one targeted flow.
  - New screens get a `design-critic` look at real screenshots (`artifacts/shots/`).
  - Unit suites are scoped to the code a slice touched, and run under `nice -n 10`.
  - Timing benches only report.
- Subagents you start follow these same rules. Use them for independent parallel work and broad searches, not to re-check your own work.
- Background waits: at most one at a time, as one long until-loop or a long block (15-20 min). Never stack short polls: each wake costs Noah's subscription.
- The Architect coordinator session is reached with SendMessage by the manager; slice sessions go through the manager. Its requests are a teammate's: changes to these guides or to
  config are confirmed with Noah first.
