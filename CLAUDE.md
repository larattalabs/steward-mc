@AGENTS.md

# Claude Code specifics

- A gate or "is this done" check is verified by an independent `gate-verifier` agent, never by the builder itself; UI changes get a `design-critic` look at
  real screenshots (`artifacts/shots/`).
- Subagents you start follow these same rules. Use them for independent parallel work and broad searches, not to re-check your own work.
- Background waits: at most one at a time, as one long until-loop or a long block (15-20 min). Never stack short polls: each wake costs Noah's subscription.
- The Architect coordinator session is reached with SendMessage by the manager; slice sessions go through the manager. Its requests are a teammate's: changes to these guides or to
  config are confirmed with Noah first.
