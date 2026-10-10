#!/bin/zsh
# Dev client for Steward's end-to-end check (tools/e2e.mjs): its own ports (8590/8591, so a dev client on 8490/8491 can keep running), its own flat
# creative world "Steward E2E" (the dev world is left alone), and no Claude: credentials are removed from the environment and the run refuses to start while
# the claude-login opt-in (run/architect/sidecar-data/secrets.json) exists. With E2E_STUB=1 it uses Architect's stub sidecar (needed by `e2e.mjs stub`; the stub
# answers card jobs, bibles and design groups once Architect 6c slice 0 ships, ask C4).
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
	export ARCHITECT_SIDECAR_DIR=${ARCHITECT_SIDECAR_DIR:-$ARCH/mod/src/test/resources/stub-sidecar}
	export STUB_COPY_FROM=${STUB_COPY_FROM:-$ARCH/kit/examples/cabin}
fi
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$R/.gradle-home}
cd $R/mod
exec env -i HOME="$HOME" USER="$USER" LOGNAME="$USER" TMPDIR="$TMPDIR" SHELL=/bin/zsh PATH=/opt/homebrew/bin:/usr/bin:/bin JAVA_HOME="$JAVA_HOME" \
	GRADLE_USER_HOME="$GRADLE_USER_HOME" STEWARD_PORT="$STEWARD_PORT" STEWARD_DEV_PORT="$STEWARD_DEV_PORT" ARCHITECT_AUTOWORLD_NAME="$ARCHITECT_AUTOWORLD_NAME" \
	ARCHITECT_AUTOWORLD_MODE="$ARCHITECT_AUTOWORLD_MODE" ARCHITECT_AUTOWORLD_PRESET="$ARCHITECT_AUTOWORLD_PRESET" ARCHITECT_AUTOWORLD_CHEATS="$ARCHITECT_AUTOWORLD_CHEATS" \
	${ARCHITECT_SIDECAR_DIR:+ARCHITECT_SIDECAR_DIR=$ARCHITECT_SIDECAR_DIR} ${STUB_COPY_FROM:+STUB_COPY_FROM=$STUB_COPY_FROM} \
	./gradlew --offline :runClient
