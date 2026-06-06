package org.yardship.core.domain.primitives;

import com.vdurmont.semver4j.SemverException;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import com.vdurmont.semver4j.Semver;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

public class Version {

    protected final Semver semver;

    public Version(String input) {
        input = notNull(input, new InvalidVersionException("Value cannot be null"));
        input = trimInput(input);

        try {
            this.semver = new Semver(input);
        }
        catch (SemverException ex) {
            throw new InvalidVersionException("Unable to parse version input: '" + input + "'");
        }
    }

    public boolean isOlderThan(Version comparable) {
        return this.semver.isLowerThan(comparable.semver);
    }

    public String value() {
        return semver.getValue();
    }

    @Override
    public String toString() {
        return semver.toString();
    }

    private String trimInput(String input) {
        input = input.trim();
        return input.replaceAll("^[vV]+", "");
    }
}
