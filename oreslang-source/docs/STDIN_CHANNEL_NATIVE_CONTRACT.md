# Stdin channel design — consolidated

This earlier draft is superseded by
[Native stdin/stdout/stderr channel review](NATIVE_STDIO_CHANNELS.md).

The current PR implements `stdio.in`/`stdio.stdin` as a context-owned
read-only `Channel<String>` subtype, `stdio.out` and `stdio.err` as
write-only subtypes, clean-EOF channel iteration, bounded OS descriptor
bridges, and native Oreslang `Stdio` and pull-based `Observable` modules.

The canonical syntax is:

```oreslang
import module Stdio from "std/stdio";
import module Observable from "std/observable";

pub gen* read_stdin(): Iterator<String> {
  for const message of stdio.in {
    yield message;
  }
  return;
}

pub routine main(): void {
  for const message of Observable.from_ch(stdio.stdin) {
    Stdio.println_blocking(message);
  }
  Stdio.flush_blocking();
  return;
}
```

Plain `readch stdio.in` still throws on a drained closed channel;
`for ... of stdio.in` completes naturally at clean EOF. Nothing requires
`async` or `await` for suspendable `readch`/`writech`.

This implementation remains a **draft** until real GitHub Actions/alternate
funded-org runners execute the exact source head, with JDK/GraalVM test and
native AOT conformance. Unrun tests and OS-pipe cancellation are not proofs.

The low-level descriptor bridge in the existing Java/Truffle interpreter is
not the native Oreslang userland implementation; the generator, observable,
printing composition, and flush barrier are all implemented in `.ores`.
