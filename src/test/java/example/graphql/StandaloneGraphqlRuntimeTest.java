package example.graphql;

import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StandaloneGraphqlRuntimeTest {

    @Test
    void executesSampleQueryThroughBoxedResolvers() {
        StandaloneGraphqlRuntime runtime = new StandaloneGraphqlRuntime();

        ExecutionResult result = runtime.execute(StandaloneGraphqlRuntime.sampleQuery());

        assertNull(result.getErrors().isEmpty() ? null : result.getErrors());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = result.getData();

        @SuppressWarnings("unchecked")
        Map<String, Object> library = (Map<String, Object>) data.get("library");
        assertEquals("central", library.get("id"));
        assertEquals("Central Library", library.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shelves = (List<Map<String, Object>>) library.get("shelves");
        assertEquals(2, shelves.size());
        assertEquals("Book", ((Map<?, ?>) shelves.get(0).get("featured")).get("__typename"));
        assertEquals("Gadget", ((Map<?, ?>) library.get("highlighted")).get("__typename"));
    }
}
