# Jenkins distributed execution

Jenkins remains responsible for node provisioning, labels, workspace allocation, and executor count. ScenarioMesh does not replace Jenkins scheduling. It consumes worker processes started inside already allocated Jenkins executors and schedules lifecycle-safe test work across those processes.

## Capacity model

A ScenarioMesh remote worker process currently contributes one execution lane. To expose twelve Jenkins-allocated lanes, start twelve worker processes, for example:

- Agent A: 2 Jenkins executors -> 2 ScenarioMesh worker processes
- Agent B: 6 Jenkins executors -> 6 ScenarioMesh worker processes
- Agent C: 4 Jenkins executors -> 4 ScenarioMesh worker processes

The coordinator sees twelve authenticated sessions. Do not advertise multiple slots on one serial worker socket; a slot must correspond to actual concurrent execution capacity.

## Credentials

Use Jenkins Credentials Binding for the registration token and TLS files/passwords. Keep secrets in environment variables or secret files. Do not interpolate secret values into a Groovy double-quoted shell command.

A conceptual pipeline layout is:

```groovy
withCredentials([
  string(credentialsId: 'scenariomesh-token', variable: 'SCENARIOMESH_DISTRIBUTED_TOKEN'),
  file(credentialsId: 'scenariomesh-agent-keystore', variable: 'SCENARIOMESH_DISTRIBUTED_TLS_KEY_STORE'),
  string(credentialsId: 'scenariomesh-agent-keystore-password', variable: 'SCENARIOMESH_DISTRIBUTED_TLS_KEY_STORE_PASSWORD'),
  file(credentialsId: 'scenariomesh-agent-truststore', variable: 'SCENARIOMESH_DISTRIBUTED_TLS_TRUST_STORE'),
  string(credentialsId: 'scenariomesh-agent-truststore-password', variable: 'SCENARIOMESH_DISTRIBUTED_TLS_TRUST_STORE_PASSWORD')
]) {
  sh '''
    mvn -B io.scenariomesh:scenariomesh-maven-plugin:0.1.0-SNAPSHOT:worker \
      -Dscenariomesh.worker.host=scenariomesh-coordinator.internal \
      -Dscenariomesh.worker.port=43117
  '''
}
```

The coordinator must use a certificate whose SAN matches the host name used by workers. For multi-host operation, configure `distributed.tls.enabled=true`; non-loopback plaintext is rejected.

## Transparent Maven takeover

For `mvn test`/`mvn verify` transparent takeover, participating workers must register during ScenarioMesh preflight. Only after the required authenticated compatible worker set is proven does ScenarioMesh suppress the native Surefire/Failsafe execution. If the set is missing or incompatible, the build remains native Maven.

The exact sessions proven in preflight are transferred to the test execution phase. ScenarioMesh does not prove one set of workers and then reconnect to a different set after Maven has been suppressed.

## Agent loss

Workers emit authority-free idle presence heartbeats and lease-scoped heartbeats while running work. A stale worker is not assigned new work. A disconnected worker's uncertain lifecycle-scoped work is not automatically replayed. Retry is restricted to work classified safe by the execution model and retry policy.

Healthy shutdown uses graceful draining. Failure/protocol corruption uses immediate retirement.

## Workspace/runtime distribution

Each participating Jenkins executor must have the target repository runtime it is expected to execute: compiled test classes, test dependencies, ScenarioMesh plugin/runtime artifacts, the selected test JVM/toolchain, and required browser/native dependencies. Jenkins artifact/stash/cache mechanisms may distribute these inputs. ScenarioMesh does not silently copy arbitrary workspaces between nodes because doing so could change Maven classpath/toolchain semantics.

Workers publish Java/runtime and adapter/engine capability metadata. Transparent takeover stays fail-closed if that capability proof is insufficient.
