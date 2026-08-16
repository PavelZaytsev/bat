# Stage-3 Apache Commons CSV experiment

This stage is a one-session, end-to-end BDRv1 run on the pre-fix tree of Apache Commons CSV PR
625. The candidate sees a synthetic single-root Git repository at upstream tree
`ea9c3554f29d2edf7ddaf582371f77886a7b569c` and no remote, PR text, future objects, prior probe, or
hidden evaluator.

The connected preparation run must execute the untouched full Maven suite to populate a private
dependency cache. Candidate sessions mount a copy of that cache and run with `--network=none`.

`CommonsCsvChunkedReaderHiddenTest.java` is a hidden evaluator input and must never be mounted into
a candidate session. It covers parser equivalence under one- and two-character delivery, escaped
multi-character delimiters, Reader-backed printing and round-trip, reader-level fill and EOF
behavior, and zero-length operations.
