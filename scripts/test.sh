#!/usr/bin/env bash
# Runs the build and the tests, with or without a local JDK.
#
# With Maven on PATH it runs Maven directly. Integration goals require a host
# container socket. Without local Maven, Maven runs inside a container and is
# handed that socket so the integration tests can start Keycloak through
# Testcontainers.
#
# Usage:
#   scripts/test.sh              # clean verify: unit and integration tests
#   scripts/test.sh test         # unit tests only, no containers needed
#   scripts/test.sh spotless:apply
set -euo pipefail

MAVEN_IMAGE="maven:3.9-eclipse-temurin-21"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Rootless Podman puts its socket under the user's runtime directory.
podman_socket="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
# Test-only override for exercising no-socket behavior on hosts with Docker;
# DOCKER_SOCKET_PATH is not an operator-facing setting.
docker_socket="${DOCKER_SOCKET_PATH:-/var/run/docker.sock}"

goals_need_container_socket() {
    local goal
    for goal in "$@"; do
        case "$goal" in
            verify | integration-test | failsafe:integration-test) return 0 ;;
        esac
    done
    return 1
}

resolve_container_socket() {
    # Integration tests need the host's socket to start Keycloak.
    if [ -n "${DOCKER_HOST:-}" ] && [ -S "${DOCKER_HOST#unix://}" ]; then
        printf '%s\n' "${DOCKER_HOST#unix://}"
    elif [ -S "$podman_socket" ]; then
        printf '%s\n' "$podman_socket"
    elif [ -S "$docker_socket" ]; then
        printf '%s\n' "$docker_socket"
    else
        return 1
    fi
}

require_container_socket() {
    if ! socket="$(resolve_container_socket)"; then
        echo "No container socket found; start one, or set DOCKER_HOST." >&2
        echo "For rootless Podman: systemctl --user start podman.socket" >&2
        exit 1
    fi
}

goals=("$@")
if [ ${#goals[@]} -eq 0 ]; then
    goals=(clean verify)
fi

socket=""
if goals_need_container_socket "${goals[@]}"; then
    require_container_socket
fi

if command -v mvn >/dev/null 2>&1; then
    mvn -B "${goals[@]}"
    exit $?
fi

echo "No local Maven; running in $MAVEN_IMAGE instead." >&2

runtime=""
for candidate in docker podman; do
    if command -v "$candidate" >/dev/null 2>&1; then
        runtime="$candidate"
        break
    fi
done
if [ -z "$runtime" ]; then
    echo "Need either Maven on PATH or a container runtime (docker/podman)." >&2
    exit 1
fi

extra_env=()
if [ -z "$socket" ]; then
    require_container_socket
fi
if [ "$socket" = "$podman_socket" ]; then
    # Testcontainers' resource reaper cannot attach to a rootless daemon.
    extra_env+=(-e TESTCONTAINERS_RYUK_DISABLED=true)
fi

# Survives between runs, so only the first one pays for the dependencies.
maven_cache="${XDG_CACHE_HOME:-$HOME/.cache}/keycloak-login-sync-provider/m2"
mkdir -p "$maven_cache"

# --security-opt label=disable: on SELinux hosts the socket is otherwise
# unreadable from inside the container. :Z relabels the bind mounts.
# --network=host: Testcontainers exposes the in-JVM mock identity provider to
# the Keycloak container through the host's network.
"$runtime" run --rm --network=host --security-opt label=disable \
    --volume "$repo_root:/workspace:Z" \
    --workdir /workspace \
    --volume "$maven_cache:/root/.m2:Z" \
    --volume "$socket:/var/run/docker.sock" \
    --env DOCKER_HOST=unix:///var/run/docker.sock \
    "${extra_env[@]}" \
    "$MAVEN_IMAGE" mvn -B "${goals[@]}"
