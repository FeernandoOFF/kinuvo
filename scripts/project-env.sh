# Explicit launcher profiles override both pins for this process tree only.
if [ -n "${KINUVO_RUN_HOME:-}${KINUVO_RUN_PORT:-}" ]; then
    case "${KINUVO_RUN_HOME:-}" in
        /*) ;;
        *) echo "KINUVO_RUN_HOME must be an absolute path" >&2; exit 1 ;;
    esac
    case "${KINUVO_RUN_PORT:-}" in
        ''|*[!0-9]*) echo "KINUVO_RUN_PORT must be a TCP port" >&2; exit 1 ;;
    esac
    if [ "$KINUVO_RUN_PORT" -lt 1 ] || [ "$KINUVO_RUN_PORT" -gt 65535 ]; then
        echo "KINUVO_RUN_PORT must be 1..65535" >&2; exit 1
    fi
    export KINUVO_HOME="$KINUVO_RUN_HOME" KINUVO_EMBEDDED_PORT="$KINUVO_RUN_PORT"
else
# Sourced by repository entry points; the pin is local to each checkout.
if [ -f "$kinuvo_project_root/.kinuvo-home" ]; then
    kinuvo_expected_home=$(cat "$kinuvo_project_root/.kinuvo-home")
    case "$kinuvo_expected_home" in
        /*) ;;
        *) echo "Invalid .kinuvo-home: expected an absolute path" >&2; exit 1 ;;
    esac
    if [ -n "${KINUVO_HOME:-}" ] && [ "$KINUVO_HOME" != "$kinuvo_expected_home" ]; then
        echo "KINUVO_HOME conflicts with this checkout: expected $kinuvo_expected_home, got $KINUVO_HOME" >&2
        exit 1
    fi
    export KINUVO_HOME="$kinuvo_expected_home"
fi
if [ -f "$kinuvo_project_root/.kinuvo-port" ]; then
    kinuvo_expected_port=$(cat "$kinuvo_project_root/.kinuvo-port")
    case "$kinuvo_expected_port" in
        ''|*[!0-9]*) echo "Invalid .kinuvo-port: expected a TCP port" >&2; exit 1 ;;
    esac
    if [ "$kinuvo_expected_port" -lt 1 ] || [ "$kinuvo_expected_port" -gt 65535 ]; then
        echo "Invalid .kinuvo-port: expected 1..65535" >&2; exit 1
    fi
    if [ -n "${KINUVO_EMBEDDED_PORT:-}" ] && [ "$KINUVO_EMBEDDED_PORT" != "$kinuvo_expected_port" ]; then
        echo "KINUVO_EMBEDDED_PORT conflicts with this checkout: expected $kinuvo_expected_port" >&2
        exit 1
    fi
    export KINUVO_EMBEDDED_PORT="$kinuvo_expected_port"
fi

fi
