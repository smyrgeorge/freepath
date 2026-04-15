FROM rust:1.94-slim-bullseye AS builder
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM debian:bullseye-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home --shell /usr/sbin/nologin freepath
COPY --from=builder /build/target/release/freepath-relay /usr/local/bin/freepath-relay
USER freepath
ENTRYPOINT ["freepath-relay"]
CMD ["--listen-addr", "/ip4/0.0.0.0/tcp/4001"]
