# Archon: notes for agents

Archon coordinates a fleet of Java application processes: registries elect a primary, applications register and get
ranks (1 = leader), services are discovered through the registry, archon-config distributes configurations, a CA in
the registries issues TLS certificates, and a portal shows and manages it all, including live logs.

Read before changing anything substantial:

* `docs/technical-reference.md`: how every part works (election, ranks, services, configs, TLS, live logs, portal,
  data model, deployment, timings).
* `docs/sdk-developer-guide.md`: how applications use the SDK.

Keep both documents true when behaviour changes; they quote numbers from the code.

## Repositories

| Path | What |
|---|---|
| `/Users/sulochana/Documents/git_tree/archon` | This repo. Maven multi-module. |
| `/Users/sulochana/Documents/git_tree/databridge` | DataBridge, the Netty message transport every connection uses. Archon depends on `dev.heysulo.databridge:databridge-core:1.0-SNAPSHOT` from the local Maven repo. |
| `/Users/sulochana/Documents/git_tree/lunar-perihelion` | The example application `Group:Application` (`com.example.Main`, a ping service with leader/mirrors). No git remote: commits stay local. |

## Modules

| Module | Holds |
|---|---|
| `archon-dictionary` | Every wire message (`sdk/messages`), protocol constants (`RegistryProtocol`: port 8080, ranks), config model (`sdk/config`), log line (`sdk/logs`), TLS building blocks (`tls/`: `ArchonIdentity`, `ArchonTrustManager`, `CaTrust`, `Certificates`, `TlsMaterial`, `TlsMode`, `JoinTokens`). |
| `archon-registry` | The registry. `server/RegistryServer` (message routing, registration, election glue), `client/LeaderElectorClient` + `LeaderElectionPolicy` + `Ballot` (election), `applications/` (records, ranks, definitions), `services/` (catalog, running services), `tls/` (CA, master key, join tokens, `RegistryTls`), `server/LogRelays` + `RegistryLogs` (live logs), `server/RegistryReadiness`. |
| `archon-sdk` | `ArchonInterface` (entry point), `ArchonCallback`, `registry/` (primary lookup and connection), `services/` (serving, clients, warm-up), `config/` (config client and replica), `tls/ArchonTls` (join, renew), `logs/` (capture, buffer, shipper, settings). |
| `archon-config` | Configuration service, itself an SDK app (`Archon:Config`). `ConfigService` (in-memory index, push), `ConfigStore` (DB, revisions), `ConfigServer`, `ConfigDefaults` (seeds global log settings). |
| `archon-portal` | Backend: `PortalServer` (JDK HttpServer), `*Api` classes, `auth/` (users, roles, `Permission` enum), `tls/` (join tokens, HTTPS), `LogStreams` (SSE logs), `RegistryWatcher`. Frontend in `frontend/`: Vite, React 19, TypeScript, Mantine 9, TanStack Query, Vitest. |

## Building and testing

The local Maven runs on JDK 23; modules compile with `--release 17` (the images run Java 17). Always build through
Maven, never trust an IDE-only compile.

```bash
# DataBridge first, when it changed
cd /Users/sulochana/Documents/git_tree/databridge && mvn -q -o install

# Everything: compile, checkstyle, SpotBugs, tests, portal frontend
cd /Users/sulochana/Documents/git_tree/archon && mvn -q -o clean install

# One module (install upstream modules first if they changed)
mvn -q -o install -pl archon-dictionary,archon-sdk -DskipTests
mvn -q -o verify -pl archon-registry

# Frontend alone
cd archon-portal/frontend && npm run check      # typecheck + eslint + vitest
```

* `-o` (offline) works; new dependencies need one online run first.
* The portal build prints a Vite "chunks larger than 800 kB" warning as `[ERROR]` lines. It is not a failure; filter it.
* Checkstyle: 120-column lines, no star or unused imports, braces always, utility classes need private constructors.
* SpotBugs runs in `verify`. Intended exposure of shared objects (`EI_EXPOSE_REP*`) goes in
  `config/spotbugs-exclude.xml` with a reason. Singletons must have private constructors (add a package-private static
  factory for tests).
* Tests: TestNG; H2 in MySQL mode for database tests (`jdbc:h2:mem:…;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE`).
  `FakeConnection` (registry tests) records what is sent and can present a peer certificate.
* Frontend tests use `renderWith` and `mockPortal` from `src/test/render.tsx`; `src/test/setup.ts` polyfills
  `matchMedia`, `ResizeObserver` and `document.fonts` for jsdom. Mantine `Select` options: query with
  `{ hidden: true }`.
* DataBridge's `ProtocolHardeningTest.clientStopFromCallbackDoesNotDeadlock` can time out under load; rerun before
  suspecting a change.

## Deploying (reference cluster)

Single-node Kubernetes (`kubernetes`, 10.21.3.100). Images go to the registry at `10.21.3.30:80` (pushes sometimes
time out through the proxy; retry). MySQL at `mysql.heysulo.internal:3306`, schema `archon`.

| Component | Kubernetes | Address |
|---|---|---|
| Registries | StatefulSet `archon-registry` (3), Services `archon-registry-{0,1,2}-svc` | 10.21.3.201–203:8080 |
| archon-config | Deployment `archon-config` (2) | pod IPs, port 9100 |
| Portal | Deployment `archon-portal` (2) | http://10.21.3.204 |
| Example app | Deployment `hello-world-java` (5) | pod IPs, ping on 9001 |

```bash
# Build an image and roll it out (repeat per module that changed)
cd archon-registry && docker build -q --platform linux/amd64 -t 10.21.3.30:80/archon-registry:latest . \
  && docker push -q 10.21.3.30:80/archon-registry:latest
kubectl rollout restart statefulset/archon-registry && kubectl rollout status statefulset/archon-registry
kubectl rollout restart deployment/archon-config deployment/archon-portal
# Example app
cd ../lunar-perihelion && mvn -q clean package && docker build -q --platform linux/amd64 . \
  -t 10.21.3.30:80/hello-world-java:latest && docker push -q 10.21.3.30:80/hello-world-java:latest \
  && kubectl rollout restart deployment/hello-world-java
```

* Roll registries first, then archon-config, then the portal and apps, when a change spans them.
* **Never restart every registry at once** (registry state is only in memory). Do not use
  `archon-registry/update-deployment.sh`: it deletes all registry pods together.
* After a rollout, check `kubectl get pods` for restarts and read the logs of anything that restarted
  (`kubectl logs <pod> --previous`). An app restarting during a registry rollout is a bug.
* TLS is **required** on the cluster. ConfigMap `archon-tls` (`mode`, `ca-fingerprint`); Secrets `archon-db`,
  `archon-ca` (`master-key`), `hello-world-java-join` (`token`). Never print, create or change secrets yourself; ask the
  user. CA fingerprint: `a3e075c258f5082bc10dcc385aed6ca26a613d43e3d114788d6b2e525f9f12a2`.
* Verify features live: the portal in the built-in browser (the user is usually signed in; never enter credentials),
  `kubectl logs`, and the local smoke program pattern (compile a tiny SDK client against the example jar).
  Do not run `javascript_tool` on the portal page; the user declined it.

## Git

* Commit straight to `main` in archon, databridge and lunar-perihelion; no feature branches.
* Commit or push only when the user asks. Use `arch -arm64 /usr/bin/git` (the default `git` may run under the wrong
  architecture).
* One logical change per commit, message in the imperative with a body explaining why, ending with the
  `Co-Authored-By` line the session gives.

## Design rules this code follows

* **Never block a network thread.** Netty callbacks hand blocking work (database, waiting for answers) to executors.
  The SDK refuses blocking calls on the registry connection's thread.
* **Trust identities, not addresses.** With TLS, peers are checked by `archon://Group/Name` in their certificate
  (`ArchonTrustManager`); hostname verification is off on every Archon client.
* **Migrations are gradual.** New wire fields must deserialize from old senders (Java serialization: add fields,
  keep `serialVersionUID`, treat missing as default). TLS has `off → optional → required`. Components must keep
  working against the previous version during a rolling restart.
* **The registry's state is in memory and synced to mirrors.** Anything a promoted mirror needs (records, tokens) must
  be in `ApplicationSyncMessage` before an instance can depend on it.
* **Instances acknowledge ranks when ready.** Only `RUNNING` instances are promoted; never acknowledge on behalf of an
  application.
* **Deserialization is allow-listed.** DataBridge trusts `dev.heysulo.**`, `java.lang.*` and common `java.util`
  collections. New message types belong in `dev.heysulo.archon…`.
* **Configurations resolve application > group > global.** Built-in settings are global configurations seeded by
  `ConfigDefaults`; registries read theirs from the `configurations` table as `Archon:Registry`.
* **Portal permissions** come from the `Permission` enum (backend) and `PERMISSIONS` (frontend `auth/permissions.ts`);
  add both, and a label in `pages/roles/model.ts`. A feature's `manage` implies its `view`.
* **Comments explain why**, in plain sentences; Javadoc on public API says what callers rely on.

## Pitfalls already hit

* Java API newer than 17 (`PrintStream.charset()`) compiled fine on JDK 23 and crash-looped pods; `--release 17`
  now catches it.
* The SDK no longer ships an slf4j binding: every application (and archon-config, the portal) must declare one, or it
  logs nothing.
* Electing a leader during a new primary's 20 s reconnect grace stole ranks from reconnecting leaders; promotions wait
  for the grace (`RegistryServer.inReconnectGrace`).
* A token generated after the STARTING sync left mirrors with stale tokens; generate before syncing.
* Re-announcing services after failover must carry the TLS flag too (`ServiceManager.announceAll`).
* React lint forbids reading refs during render; keep render values in state.
* Mantine `Badge` truncates; use `styles={{ label: { overflow: "visible" } }}` for labels that must show whole.
