#!/bin/sh
# Runs swift with settings that work on a Command Line Tools-only Mac:
#  - SwiftUI's macro plugins ship with CLT only for the 26.x SDKs, so prefer one.
#  - Swift Testing's macro plugin lives outside the default plugin search path.
#  - Test suites share URLProtocol stubs, so tests run serially.
# With full Xcode selected this is a plain passthrough.
set -e
cd "$(dirname "$0")"
CLT=/Library/Developer/CommandLineTools
if [ "$(xcode-select -p 2>/dev/null)" = "$CLT" ]; then
    if [ -z "${SDKROOT:-}" ]; then
        for sdk in "$CLT"/SDKs/MacOSX26*.sdk; do [ -d "$sdk" ] && SDKROOT="$sdk"; done
        [ -n "${SDKROOT:-}" ] && export SDKROOT
    fi
    if [ "${1:-}" = "test" ] || [ "${1:-}" = "build" ]; then
        command="$1"
        shift
        set -- "$command" -Xswiftc -plugin-path -Xswiftc "$CLT/usr/lib/swift/host/plugins/testing" "$@"
    fi
fi
if [ "${1:-}" = "test" ]; then
    shift
    exec swift test --no-parallel "$@"
fi
exec swift "$@"
