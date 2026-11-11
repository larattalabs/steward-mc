#!/bin/zsh
# Dev client for Steward's end-to-end check (tools/e2e.mjs): its own ports (8590/8591, so a dev client on 8490/8491 can keep running), its own flat
# creative world "Steward E2E" (the dev world is left alone), and no Claude: credentials are removed from the environment and the run refuses to start while
# the claude-login opt-in (run/architect/sidecar-data/secrets.json) exists. With E2E_STUB=1 it runs Architect's helper in sim mode (`e2e.mjs stub`: v0.12.2,
# ARCHITECT_SIDECAR_BACKEND=sim, notional measured costs, scripted job answers; never Claude).
#   tools/run-e2e-client.sh            (then: node tools/e2e.mjs free)
set -e
R=${0:A:h:h}
ARCH=${ARCHITECT_CHECKOUT:-${R:h}/architect-mc}
if [[ -f $R/mod/run/architect/sidecar-data/secrets.json ]]; then
	echo "refusing: $R/mod/run/architect/sidecar-data/secrets.json (the claude-login opt-in) exists; the e2e check never spends. Remove it first." >&2
	exit 3
fi
unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL CLAUDE_CODE_OAUTH_TOKEN CLAUDE_CODE_OAUTH_SCOPES CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX
export STEWARD_PORT=${STEWARD_PORT:-8590}
export STEWARD_DEV_PORT=${STEWARD_DEV_PORT:-8591}
export ARCHITECT_AUTOWORLD_NAME=${ARCHITECT_AUTOWORLD_NAME:-Steward E2E}
export ARCHITECT_AUTOWORLD_MODE=${ARCHITECT_AUTOWORLD_MODE:-creative}
export ARCHITECT_AUTOWORLD_PRESET=${ARCHITECT_AUTOWORLD_PRESET:-flat}
export ARCHITECT_AUTOWORLD_CHEATS=${ARCHITECT_AUTOWORLD_CHEATS:-true}
if [[ ${E2E_STUB:-0} == 1 ]]; then
	# Architect's own helper in sim mode (v0.12.2): bibles, massing-first groups, details and scripted job answers, never Claude; notional costs as measured
	export ARCHITECT_SIDECAR_BACKEND=sim
	export ARCHITECT_SIM_COSTS=${ARCHITECT_SIM_COSTS:-measured}
	unset ARCHITECT_SIDECAR_DIR ARCHITECT_KIT_DIR
fi
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}
# The node that runs e2e.mjs goes first on the client's PATH, so Architect's launcher finds a real node binary before mise's shim: the shim's
# 5 s probe failed once under load (the 2026-11-10 nightly's stub client: "mise/shims/node is not node 22+ (did not run)", no helper, 30 failures).
NODE_PATH_DIR=${E2E_NODE_DIR:+$E2E_NODE_DIR:}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$R/.gradle-home}
cd $R/mod
exec env -i HOME="$HOME" USER="$USER" LOGNAME="$USER" TMPDIR="$TMPDIR" SHELL=/bin/zsh PATH="$NODE_PATH_DIR/opt/homebrew/bin:/usr/bin:/bin" JAVA_HOME="$JAVA_HOME" \
	GRADLE_USER_HOME="$GRADLE_USER_HOME" STEWARD_PORT="$STEWARD_PORT" STEWARD_DEV_PORT="$STEWARD_DEV_PORT" ARCHITECT_AUTOWORLD_NAME="$ARCHITECT_AUTOWORLD_NAME" \
	ARCHITECT_AUTOWORLD_MODE="$ARCHITECT_AUTOWORLD_MODE" ARCHITECT_AUTOWORLD_PRESET="$ARCHITECT_AUTOWORLD_PRESET" ARCHITECT_AUTOWORLD_CHEATS="$ARCHITECT_AUTOWORLD_CHEATS" \
	${ARCHITECT_SIDECAR_DIR:+ARCHITECT_SIDECAR_DIR=$ARCHITECT_SIDECAR_DIR} ${ARCHITECT_SIDECAR_BACKEND:+ARCHITECT_SIDECAR_BACKEND=$ARCHITECT_SIDECAR_BACKEND} \
	${ARCHITECT_SIM_COSTS:+ARCHITECT_SIM_COSTS=$ARCHITECT_SIM_COSTS} ${ARCHITECT_JAR:+ARCHITECT_JAR=$ARCHITECT_JAR} ${LAB_UI_DIR:+LAB_UI_DIR=$LAB_UI_DIR} \
	./gradlew --offline :runClient
