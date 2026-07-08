package org.yardship.adapters.in.http;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;

import org.yardship.core.domain.primitives.Side;

import java.util.List;

/**
 * The JSON request body for {@code POST /api/v1/scrape/applications}:
 * {@code { "targets": [ { "name": "argo-cd", "side": "current" }, ... ] }}.
 *
 * <p>Hand-written (not OpenAPI-generated), mirroring {@link ScrapeController}'s style. {@code side}
 * binds straight to the {@link Side} enum, so the legal values ({@code current}/{@code latest}/
 * {@code both}) are part of the wire contract: an unknown value fails deserialisation and JAX-RS
 * returns 400, rather than the controller throwing on a hand-rolled {@code valueOf}. The
 * {@link JsonFormat.Feature#ACCEPT_CASE_INSENSITIVE_VALUES} keeps the lowercase input the API
 * accepts working against the uppercase enum constants — case-insensitivity handled by Jackson, not
 * by the adapter, and without coupling the domain {@code Side} to Jackson.
 */
@RegisterForReflection
public record ScrapeTargetsRequest(List<Target> targets) {

    @RegisterForReflection
    public record Target(
            String name,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES) Side side) {
    }
}
