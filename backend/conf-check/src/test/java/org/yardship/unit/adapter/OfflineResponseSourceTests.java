package org.yardship.unit.adapter;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.adapter.OfflineResponseSource;
import org.yardship.confcheck.port.ResponseSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OfflineResponseSource}: fixture-driven checking with no network, mirroring
 * {@code OfflineBodySource}. Unlike {@code OfflineBodySource} (which performs real file/stdin I/O
 * and is therefore covered at the integration level), an offline {@link ResponseSource} is built
 * directly from values the {@code header} command's own options supply (status code + repeated
 * {@code --header-value} entries) with no I/O of its own, so this belongs at the unit level.
 */
class OfflineResponseSourceTests {

    @Test
    void fetch_returnsTheSuppliedStatusCodeAndHeadersVerbatim() {
        OfflineResponseSource source =
                OfflineResponseSource.of(200, Map.of("X-Jenkins", List.of("2.568.2")));

        ResponseSource.Response response = source.fetch();

        assertEquals(200, response.statusCode());
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    @Test
    void fetch_supportsANonTwoXxFixtureStatus_forSimulatingASecuredEndpointOffline() {
        OfflineResponseSource source =
                OfflineResponseSource.of(403, Map.of("X-Jenkins", List.of("2.568.2")));

        ResponseSource.Response response = source.fetch();

        assertEquals(403, response.statusCode());
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow(),
                "an operator must be able to rehearse the 403-with-header case entirely offline");
    }

    @Test
    void firstHeader_lookupIsCaseInsensitive() {
        OfflineResponseSource source =
                OfflineResponseSource.of(200, Map.of("x-jenkins", List.of("2.568.2")));

        ResponseSource.Response response = source.fetch();

        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    @Test
    void firstHeader_repeatedHeader_returnsFirstValue() {
        OfflineResponseSource source =
                OfflineResponseSource.of(200, Map.of("X-Jenkins", List.of("2.568.2", "9.9.9")));

        ResponseSource.Response response = source.fetch();

        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    @Test
    void firstHeader_absentHeader_returnsEmpty() {
        OfflineResponseSource source = OfflineResponseSource.of(200, Map.of());

        ResponseSource.Response response = source.fetch();

        assertTrue(response.firstHeader("X-Jenkins").isEmpty());
    }

    @Test
    void fetch_withNoHeaders_stillReportsTheStatusCode() {
        OfflineResponseSource source = OfflineResponseSource.of(404, Map.of());

        ResponseSource.Response response = source.fetch();

        assertEquals(404, response.statusCode());
    }
}
