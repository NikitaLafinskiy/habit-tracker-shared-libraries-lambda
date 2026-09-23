# habit-tracker-shared-libraries-lambda

`com.habittracker:lambda-dispatch` — the Lambda event dispatcher shared by the `api`, `auth` and
`inference` services. Each service has exactly one Lambda entry point (`StreamLambdaHandler`)
that hands the raw payload to `LambdaEventDispatcher`, which routes it to the first strategy that
claims it.

Until 2026-09-23 this code was copied verbatim into api and auth. It moved here once a third
service needed it. It lives in its own module, **not in `auth-client`**, which has to stay free
of Lambda dependencies.

## What it registers

`LambdaDispatchAutoConfiguration` (via `META-INF/spring/...AutoConfiguration.imports`):

| Bean | When |
|---|---|
| `LambdaEventMapper` | always (unless the service defines its own) |
| `LambdaEventDispatcher` | always — takes every `LambdaEventStrategy` bean in `@Order` order, including the service's own `@Component` strategies |
| `ApiGatewayHttpEventStrategy` | only if the service defines an `HttpRequestProxy` bean (api, auth; not the inference worker) |
| `SqsEventStrategy` | always — routes each message to the first `SqsMessageHandler` bean that `supports()` it; zero handlers is fine |
| `SesNotificationEventStrategy` | only if the service defines a `SesNotificationHandler` bean (auth) |
| `KeepWarmEventStrategy` | always |

## What a service supplies

- `StreamLambdaHandler`. Its FQN is pinned in each service's `.infra/main.tf`, so it is not in
  here.
- An `HttpRequestProxy` bean, if the service serves API Gateway. Write it as an explicit lambda
  body, never a method reference to `StreamLambdaHandler::proxy`. The reason is in
  `docs/api-CLAUDE.md` "Lambda event dispatch".
- `SqsMessageHandler` implementations, **one per message type, in the `sqs/` package of the
  domain that owns them** (`domain/billing/sqs/BillingSqsHandler`, …). There is no global
  handler directory in any service.
- A `SesNotificationHandler`, if the service receives SES bounces and complaints over SNS.

`RequestIdMdc.KEY` (`"requestId"`) is the MDC key async strategies stamp the AWS request id into.
Each service's `RequestLoggingFilter` uses the same constant, and its `logging.pattern.level`
prints `%X{requestId:-}`.

## Releasing

Push a `v*` tag. `publish.yaml` runs the checks and publishes to this repo's GitHub Packages
registry. Consumers read it with a `read:packages` PAT (`PACKAGES_READ_TOKEN` in CI, `gpr.user` /
`gpr.token` locally), exactly like `auth-client`.

To build a service against unpublished changes here, use a composite build instead of
publishing:

```bash
./gradlew --include-build ../shared-libraries/lambda check
```

## Code style

Three tools, no overlap (wired in [`gradle/quality.gradle`](gradle/quality.gradle),
matching the `api` and `auth` services):

| Tool | Owns | Fix it with |
|---|---|---|
| **Spotless** (google-java-format, `aosp`) | all layout — 4-space indent, 100-column wrap, whitespace, import order | `./gradlew spotlessApply` |
| **Checkstyle** | semantic rules only — naming, declaration order, visibility, switch correctness | by hand |
| **Error Prone** | real defects at compile time — it runs inside `javac` | `./gradlew compileJava -PerrorproneFix=<Check1,Check2>` |

`./gradlew check` runs all three. **Never hand-format Java** — run `spotlessApply` and
let it decide. Don't add layout rules back to `config/checkstyle/checkstyle.xml`; they
will either be silently redundant or fight the formatter.

[`.githooks/pre-commit`](.githooks/pre-commit) runs `spotlessApply` over staged Java and
re-stages it. It is committed, but **`core.hooksPath` is local config and does not
survive a clone** — run this once per checkout or the hook silently never fires:

```sh
git config core.hooksPath .githooks
```

## Publishing

Publishes to this repo's own GitHub Packages Maven registry
(`com.habittracker:auth-client`) via the `Publish` workflow, which runs on
any pushed tag matching `v*` and authenticates with the default
`GITHUB_TOKEN` (no PAT needed - the workflow declares `packages: write`).

To cut a release: bump `version` in `build.gradle`, commit, tag that commit
`vX.Y.Z` matching the new version, and push the tag.
