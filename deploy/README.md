# Nasazení Bank iD backendu (privátně, hardened)

- `backend/Dockerfile` – distroless Java 17 `nonroot` (UID 65532) pinovaný na digest, bez shellu; artefakt z `./gradlew :backend:installDist`.
- `deploy/docker-compose.yml` – `read_only`, `tmpfs /tmp` (noexec,nosuid), `cap_drop: ALL`, `no-new-privileges`, výchozí seccomp, limity PID/paměti/CPU, port jen na 127.0.0.1.
- `deploy/k8s/bankid.yaml` – namespace s Pod Security Standard *restricted*, `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, bez SA tokenu; NetworkPolicy default-deny + výjimky (ingress controller → 8080, egress DNS a 443 mimo privátní rozsahy a 169.254.0.0/16).
- Tajemství: jen `backend/.env` (necommitovat) / Kubernetes Secret; image se identifikuje digestem.

## Budoucí možnost: Firecracker microVM
Pro silnější izolaci (samostatné jádro na instanci) lze backend provozovat ve **Firecracker** microVM (AWS, KVM, minimální virtio zařízení, jailer s cgroups/seccomp/chroot), např. přes **Kata Containers** (`runtimeClassName: kata-fc`) v Kubernetes, nebo přímo pomocí firecracker-containerd. Předpoklady: hostitel s KVM (bare-metal nebo nested virtualizace), image beze změn. Zdroje: https://firecracker-microvm.github.io/ , https://github.com/firecracker-microvm/firecracker/blob/main/docs/design.md , https://katacontainers.io/
