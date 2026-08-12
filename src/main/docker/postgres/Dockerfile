# syntax=docker/dockerfile:1.7

FROM pgvector/pgvector:0.8.5-pg16-bookworm@sha256:1d533553fefe4f12e5d80c7b80622ba0c382abb5758856f52983d8789179f0fb

USER root

# PostgreSQL permits a non-superuser with CREATE on the target database to
# install a trusted extension. The extension DDL remains Flyway-owned; this
# image changes only the version-pinned extension trust metadata.
RUN set -eux; \
    control_file=/usr/share/postgresql/16/extension/vector.control; \
    expected_version="default_version = '0.8.5'"; \
    expected_trust='trusted = true'; \
    test -f "$control_file"; \
    test "$(find /usr/share/postgresql/16/extension -maxdepth 1 -type f -name vector.control | wc -l)" = 1; \
    test "$(grep -Fxc "$expected_version" "$control_file")" = 1; \
    if grep -Eq '^[[:space:]]*trusted[[:space:]]*=' "$control_file"; then exit 1; fi; \
    printf '\n%s\n' "$expected_trust" >> "$control_file"; \
    test "$(grep -Fxc "$expected_trust" "$control_file")" = 1
