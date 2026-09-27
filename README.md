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

It also registers `SsmBackedPropertiesEnvironmentPostProcessor` (via `META-INF/spring.factories`).
The `iac` Lambda module passes a service's `ssm_parameter_paths` as one `SSM_BACKED_PROPERTIES`
env var of `property.name=/ssm/path` lines. This post-processor resolves each line at startup
(decrypted, inside the SnapStart snapshot) and publishes the values as the highest-precedence
property source. A missing grant fails startup with a message naming the parameter. It moved here
in 0.2.0: before that, api, auth and ai-insight each carried a copy, and auth-client ≤ 0.3.0 had a
fourth. Since the `lib/` zip keeps every jar's `spring.factories`, api and auth ran two of them
and fetched every secret twice.

## Native runtime loop (0.3.0)

`com.habittracker.lambda.runtime.LambdaRuntimeLoop` is the Lambda entry point for a service built
as a GraalVM native executable in a `provided.al2023` container image
(see `docs/GRAALVM-MIGRATION.md` in the superproject).
A service's `main` builds its Spring context and calls:

```java
LambdaRuntimeLoop.run(() -> context.getBean(LambdaEventDispatcher.class));
```

It speaks the [Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html)
over the JDK's `HttpClient`:
- **Invocations:** `invocation/next`, then `response` for the dispatcher's output. A handler
  exception goes to `error`, in Lambda's `errorMessage`/`errorType`/`stackTrace` shape, and the loop
  keeps serving.
- **Rejected outcome posts:** a `response`/`error` post the Runtime API answers with 4xx is logged
  and dropped — that invocation's outcome is lost, but the loop keeps serving. Only a 5xx container
  error exits the process, which is what the Runtime API contract prescribes ("non-recoverable
  state, exit promptly").
- **Startup failures:** a failure building the context goes to `init/error` and exits the process.
- **The `Context`** is built from the Runtime API headers and the `AWS_LAMBDA_*` environment.
  `Lambda-Runtime-Trace-Id` is exposed as the `com.amazonaws.xray.traceHeader` system property for
  the length of the invocation.

It is hand-rolled on purpose, and it is the only hand-rolled piece of the native migration:
- `aws-serverless-java-container`'s native loop is HTTP-only, so SQS, SNS and keep-warm events would
  never reach the dispatcher.
- AWS's Java runtime interface client does its Runtime API calls over JNI and loads handlers through
  a runtime class loader. Neither works in a native image.

There is **no SnapStart support** (`/runtime/restore/next`). The migration decided against SnapStart
on custom images, because outside the Java managed runtime its cache is a fixed monthly charge.

## Native-image hints

`LambdaDispatchRuntimeHints`, imported by the auto-configuration with `@ImportRuntimeHints`,
declares at build time what the library reaches reflectively:
- the event types `LambdaEventMapper` binds (`SqsEvent`, `SqsMessage`, `SqsBatchResponse` and its
  item, `SnsLambdaEvent`, `SnsRecord`, `SnsMessage`);
- the `SsmBackedPropertiesEnvironmentPostProcessor` constructor.

A new event type bound through `LambdaEventMapper.read` must be added to `EVENT_TYPES`, or it fails
only in the native binary. `LambdaDispatchRuntimeHintsTest` checks every entry.

## What a service supplies

- Its entry point: a `main` calling `LambdaRuntimeLoop.run` for a native image, or, until a
  service has migrated, the JVM `StreamLambdaHandler` whose FQN is pinned in its `.infra/main.tf`.
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
