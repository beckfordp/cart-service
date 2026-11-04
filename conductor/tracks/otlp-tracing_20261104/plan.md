# Plan: OTLP trace export to Tempo (gluon PERF-5.3)

## Phase 1: Export to Tempo
- [x] Task: purerestlib 0.8.2 → 0.10.0 in `build.sbt` ae770ac
- [x] Task: `Main.scala`: `Tracing.console` → `Tracing.resource` with
      `TracingConfig.fromEnv(sys.env)`, `Left` fails startup ae770ac
- [x] Task: Full `sbt scalafmtCheck test` (98 tests, green)
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md):
      redeploy, spans in Tempo by `service.name`
