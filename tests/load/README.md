# Load tests (k6)

System-level load tests against the running stack. Unit and integration tests live in each Maven module's `src/test`.

TODO(Day 7): k6 scripts for the three scenarios defined in [`docs/experiment_log.md`](../../docs/experiment_log.md):

- **baseline** — constant 1,000 req/s;
- **spike** — ramp to 5,000 req/s, then hold for N minutes (k6 `constant-arrival-rate` executor); QPS and p99 are
  measured on the hold window only, because a ramp has no steady state (target: p99 ≤ 180 ms);
- **chaos** — steady load while [`tests/chaos/`](../chaos/README.md) injects failures.

All traffic goes through the gateway (`http://localhost:8080`, Day 6) with a realistic mix of catalog reads, order
placements (each with its own `Idempotency-Key`) and order polling. Raw k6 output goes to `tests/load/results/`
(gitignored); summarised numbers go to `docs/experiment_log.md` with the environment template filled in.
