# shellcheck shell=bash
#
# Developer shell environment for ShopSage. SOURCE it, do not execute it:
#
#   source scripts/dev-env.sh
#
# What it does (only for variables that are not already set):
#   1. JAVA_HOME -> a JDK 17 (the build requires JDK 17+ and targets Java 17; macOS ships no default JDK).
#   2. DOCKER_HOST when the active Docker context is not "default" (colima, OrbStack,
#      Docker Desktop, rootless Docker, Podman). Testcontainers 1.x ignores Docker
#      contexts, so without this `./mvnw verify` cannot find the daemon. Plus
#      TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock only when that endpoint
#      is a socket forwarded from a VM under $HOME (colima, Docker Desktop, OrbStack):
#      Ryuk bind-mounts the socket path as seen by the daemon, i.e. inside the VM. A
#      local daemon (Linux rootless Docker/Podman) sees the real path, so it stays unset.
#   3. Warns when `unzip` is missing: ./mvnw then downloads the .tar.gz distribution,
#      which fails the SHA-256 pinned for the .zip in .mvn/wrapper/maven-wrapper.properties.
#
# Because it runs inside the caller's shell it must never `exit`, never `set -e`,
# and must work in bash 3.2 (macOS /bin/bash) as well as zsh. Temporary variables
# use the _ss_ prefix and are unset at the end.

# --- 1. JAVA_HOME --------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  _ss_jdk=""
  if [ -x /usr/libexec/java_home ]; then
    _ss_jdk="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [ -z "$_ss_jdk" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
    _ss_jdk=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
  if [ -z "$_ss_jdk" ] && [ -d /usr/lib/jvm ]; then
    # find instead of a glob: an unmatched glob is a hard error in zsh.
    _ss_jdk="$(find /usr/lib/jvm -mindepth 1 -maxdepth 1 \( -name 'java-17*' -o -name 'temurin-17*' \) 2>/dev/null | sort | head -n 1)"
  fi
  if [ -n "$_ss_jdk" ] && [ -x "$_ss_jdk/bin/java" ]; then
    export JAVA_HOME="$_ss_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "dev-env: JAVA_HOME=$JAVA_HOME (prepended \$JAVA_HOME/bin to PATH)"
  else
    echo "dev-env: WARNING: JDK 17 not found; install one (e.g. 'brew install openjdk@17') or set JAVA_HOME" >&2
  fi
  unset _ss_jdk
fi

# --- 2. Docker endpoint for Testcontainers -------------------------------------
if [ -z "${DOCKER_HOST:-}" ] && command -v docker >/dev/null 2>&1; then
  _ss_ctx="$(docker context show 2>/dev/null || true)"
  if [ -n "$_ss_ctx" ] && [ "$_ss_ctx" != "default" ]; then
    _ss_host="$(docker context inspect -f '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
    if [ -n "$_ss_host" ]; then
      export DOCKER_HOST="$_ss_host"
      echo "dev-env: DOCKER_HOST=$DOCKER_HOST (from docker context '$_ss_ctx')"
      if [ -z "${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-}" ] && [ -n "${HOME:-}" ]; then
        # case, not [[ ]]: same glob semantics in bash 3.2 and zsh; the quoted part is literal.
        case "$_ss_host" in
          "unix://$HOME"/*)
            # The socket path *inside* the VM, which Ryuk bind-mounts.
            export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
            echo "dev-env: TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=$TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"
            ;;
        esac
      fi
    else
      echo "dev-env: WARNING: could not read the endpoint of docker context '$_ss_ctx'" >&2
    fi
  fi
  unset _ss_ctx _ss_host
fi

# --- 3. unzip for the Maven wrapper -------------------------------------------
command -v unzip >/dev/null 2>&1 \
  || echo "dev-env: WARNING: 'unzip' not found; ./mvnw will fail its SHA-256 check on first download (install unzip)" >&2
