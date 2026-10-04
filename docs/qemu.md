# QEMU development caveats

Prefer native arm64 for local development and native amd64 CI for amd64
verification. Beware that QEMU is not a deployment target.

## Observed failures

On 2026-10-04, Apple Silicon / OrbStack running amd64 Temurin 25.0.4.1+1
under QEMU showed two separate failures:

- E2E completed, but the JVM did not exit: a Jetty acceptor remained blocked
  in `sun.nio.ch.Net.accept`.
- Intermittent `SIGSEGV` in `G1ParScanThreadState`. The GC frame identifies
  the crash location; the underlying cause remains unknown.

Native arm64 completed the same E2E; existing native amd64 CI had passed.

## Exit-hang workaround

A JDK-only socket-close probe reproduced `NativeThread.signal0` throwing
`IOException: Invalid argument`. The syscall trace showed
`tgkill(..., 62) = -1 EINVAL`: QEMU's default mapping left guest signal 62
unmapped. [OpenJDK NIO](https://github.com/openjdk/jdk25u/blob/master/src/java.base/unix/native/libnio/ch/NativeThread.c)
uses `SIGRTMAX - 2` to interrupt blocking I/O; see
[QEMU's signal mapping](https://github.com/qemu/qemu/blob/master/linux-user/signal.c).

For an optional emulated amd64 run, add this option to `docker run`:

```sh
-e 'QEMU_RTSIG_MAP=32 36 28,62 64 1'
```

This allowed the socket probe to finish and one full E2E run to exit normally.
The next E2E run still crashed with `SIGSEGV`. It addresses the shutdown
failure, not overall emulation reliability. It also replaces guest signal
60's mapping with signal 62's; keep it scoped to development containers,
outside production images and native CI.

## Avoid misdiagnosing the crash

SQLite concurrency/lifecycle tests passed on both architectures. An 80-cycle,
640-evaluation arena probe closed every arena and showed no accumulating RSS.
The final crash's loaded-library list contained neither SQLite nor SlateDB.
These observations weaken those hypotheses without proving all FFI paths safe.

If a failure reproduces on native amd64, retain the command, image digest,
JDK version, and `hs_err_pid*.log` for further investigation. Until then,
use native arm64 locally and native amd64 CI rather than treating a GC change
or one successful QEMU run as a fix.
