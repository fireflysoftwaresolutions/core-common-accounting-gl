/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.core.accounting.models.repositories.posting;

import io.r2dbc.spi.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.connection.DelegatingConnectionFactory;
import reactor.core.publisher.Mono;

/** Stable native factory identity; scopes own only handles returned by their own acquisition. */
public final class GlOwnedConnectionFactory extends DelegatingConnectionFactory {
    private static final Object SCOPE = new Object();
    private static final Duration CLEANUP = Duration.ofSeconds(2);

    public GlOwnedConnectionFactory(ConnectionFactory delegate) { super(Objects.requireNonNull(delegate)); }

    public <T> Mono<T> within(Supplier<Duration> remaining, Mono<T> work) {
        return Mono.defer(() -> {
            var scope = new Scope(this, remaining);
            return Mono.usingWhen(Mono.just(scope), owned -> work, Scope::cleanup,
                    (owned, error) -> owned.cleanup(), Scope::cleanup)
                .contextWrite(context -> context.put(SCOPE, scope));
        });
    }

    @Override public Mono<? extends Connection> create() {
        return Mono.deferContextual(context -> {
            var value = context.getOrDefault(SCOPE, null);
            // Legacy callers retain their existing semantics without an owned-lease guarantee.
            if (value == null) return Mono.from(super.create());
            if (!(value instanceof Scope scope) || scope.factory != this)
                return Mono.error(new IllegalStateException("Exact native lease factory required"));
            scope.remaining.get();
            return Mono.from(super.create()).map(scope::own);
        });
    }

    private static final class Scope {
        final GlOwnedConnectionFactory factory;
        final Supplier<Duration> remaining;
        final List<Lease> leases = Collections.synchronizedList(new ArrayList<>());
        final Mono<Void> cleanup;
        Scope(GlOwnedConnectionFactory factory, Supplier<Duration> remaining) {
            this.factory = factory; this.remaining = Objects.requireNonNull(remaining);
            cleanup = Mono.defer(() -> {
                List<Lease> owned; synchronized (leases) { owned = List.copyOf(leases); }
                return Mono.whenDelayError(owned.stream().map(Lease::close).toList()).timeout(CLEANUP);
            }).cache();
        }
        Connection own(Connection raw) { var lease = new Lease(raw, remaining); leases.add(lease); return lease; }
        Mono<Void> cleanup() { return cleanup; }
    }

    private static final class Lease implements Connection {
        private final Connection raw;
        private final Supplier<Duration> remaining;
        private final Mono<Void> close;
        Lease(Connection raw, Supplier<Duration> remaining) {
            this.raw = Objects.requireNonNull(raw); this.remaining = remaining;
            // Cache after the timeout so cancellation cannot detach an unbounded physical lease close.
            close = Mono.defer(() -> Mono.from(raw.close())).timeout(CLEANUP).cache();
        }
        public Publisher<Void> beginTransaction() { return Mono.defer(() -> { var limit=remaining.get(); return Mono.from(raw.beginTransaction()).timeout(limit); }); }
        public Publisher<Void> beginTransaction(io.r2dbc.spi.TransactionDefinition definition) { return Mono.defer(() -> { var limit=remaining.get(); return Mono.from(raw.beginTransaction(definition)).timeout(limit); }); }
        public Mono<Void> close() { return close; }
        public Publisher<Void> commitTransaction() { return Mono.defer(() -> { var limit=remaining.get(); return Mono.from(raw.commitTransaction()).timeout(limit); }); }
        public Publisher<Void> rollbackTransaction() { return Mono.defer(() -> Mono.from(raw.rollbackTransaction())).timeout(CLEANUP); }
        public Batch createBatch() { return raw.createBatch(); }
        public Publisher<Void> createSavepoint(String name) { return raw.createSavepoint(name); }
        public Statement createStatement(String sql) { return raw.createStatement(sql); }
        public boolean isAutoCommit() { return raw.isAutoCommit(); }
        public ConnectionMetadata getMetadata() { return raw.getMetadata(); }
        public IsolationLevel getTransactionIsolationLevel() { return raw.getTransactionIsolationLevel(); }
        public Publisher<Void> releaseSavepoint(String name) { return raw.releaseSavepoint(name); }
        public Publisher<Void> rollbackTransactionToSavepoint(String name) { return raw.rollbackTransactionToSavepoint(name); }
        public Publisher<Void> setAutoCommit(boolean value) { return raw.setAutoCommit(value); }
        public Publisher<Void> setLockWaitTimeout(Duration value) { return raw.setLockWaitTimeout(value); }
        public Publisher<Void> setStatementTimeout(Duration value) { return raw.setStatementTimeout(value); }
        public Publisher<Void> setTransactionIsolationLevel(IsolationLevel value) { return raw.setTransactionIsolationLevel(value); }
        public Publisher<Boolean> validate(ValidationDepth depth) { return raw.validate(depth); }
    }
}
