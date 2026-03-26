package example.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.*;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class StandaloneGraphqlRuntime {
    private static final Set<String> BUILTIN_SCALARS = Set.of("ID", "String", "Int", "Float", "Boolean");
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final GraphQL graphQL;

    public StandaloneGraphqlRuntime() {
        TypeDefinitionRegistry registry = new SchemaParser().parse(loadSdl());
        RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
        SampleRepository repository = new SampleRepository();

        wireQueries(registry, wiring, repository);
        wireObjects(registry, wiring);
        wireUnions(registry, wiring);

        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
        this.graphQL = GraphQL.newGraphQL(schema).build();
    }

    public ExecutionResult execute(String query) {
        return graphQL.execute(ExecutionInput.newExecutionInput().query(query).build());
    }

    public static void main(String[] args) throws Exception {
        StandaloneGraphqlRuntime runtime = new StandaloneGraphqlRuntime();
        ExecutionResult result = runtime.execute(sampleQuery());
        System.out.println(MAPPER.writeValueAsString(result.toSpecification()));
    }

    private static void wireQueries(
            TypeDefinitionRegistry registry,
            RuntimeWiring.Builder wiring,
            SampleRepository repository
    ) {
        ObjectTypeDefinition query = registry.getTypeOrNull("Query", ObjectTypeDefinition.class);
        if (null == query) {
            throw new IllegalStateException("SDL must define Query");
        }

        wiring.type(query.getName(), builder -> {
            for (FieldDefinition field : query.getFieldDefinitions()) {
                final String fieldName = field.getName();

				// --- BEGIN resolver closure ---
                DataFetcher<?> fetcher = env -> switch (fieldName) {
                    case "library" -> {
                        String id = env.getArgument("id");
                        yield repository.findLibrary(id).map(Box::new).orElse(null);
                    }
                    default -> throw new IllegalStateException("No query resolver for " + fieldName);
                };
				// --- END resolver closure ---

                builder.dataFetcher(fieldName, fetcher);
            }
            return builder;
        });
    }

    private static void wireObjects(TypeDefinitionRegistry registry, RuntimeWiring.Builder wiring) {
        Set<String> operationTypes = Set.of("Query", "Mutation", "Subscription");

        for (ObjectTypeDefinition type : registry.getTypes(ObjectTypeDefinition.class)) {
            if (operationTypes.contains(type.getName())) {
                continue;
            }

            wiring.type(type.getName(), builder -> {
                for (FieldDefinition field : type.getFieldDefinitions()) {
                    final String typeName = type.getName();
                    final String fieldName = field.getName();
                    final String fieldTypeName = unwrap(field.getType());
                    final boolean scalarLike = BUILTIN_SCALARS.contains(fieldTypeName);

					// --- BEGIN resolver closure ---
                    DataFetcher<?> fetcher = env -> {
                        Object source = env.getSource();
                        if (!(source instanceof Box parent)) {
                            throw new IllegalStateException(
                                    "Expected Box as source for " + typeName + "." + fieldName
                            );
                        }

                        Object value = parent.value(fieldName);
                        if (value == null || scalarLike) {
                            return value;
                        }
                        return boxValue(fieldTypeName, value);
                    };
					// --- END resolver closure ---

                    builder.dataFetcher(fieldName, fetcher);
                }
                return builder;
            });
        }
    }

    private static void wireUnions(TypeDefinitionRegistry registry, RuntimeWiring.Builder wiring) {
        for (UnionTypeDefinition union : registry.getTypes(UnionTypeDefinition.class)) {
            final String unionName = union.getName();

			// --- BEGIN type resolver closure ---
            TypeResolver resolver = env -> {
                Object value = env.getObject();
                if (!(value instanceof RecordBox box)) {
                    throw new IllegalStateException("Expected RecordBox for union " + unionName);
                }
                return env.getSchema().getObjectType(box.typeName());
            };
			// --- END type resolver closure ---

            wiring.type(unionName, builder -> builder.typeResolver(resolver));
        }
    }

    private static Object boxValue(String fieldTypeName, Object value) {
        if (value instanceof List<?> list) {
            List<Object> boxed = new ArrayList<>(list.size());
            for (Object item : list) {
                boxed.add(boxSingle(fieldTypeName, item));
            }
            return boxed;
        }
        return boxSingle(fieldTypeName, value);
    }

    private static Object boxSingle(String fieldTypeName, Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("Expected object-like field to hold a Map but got " + value);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) rawMap;
        String concreteType = Optional.ofNullable((String) map.get("__typename")).orElse(fieldTypeName);

        if (Objects.equals(concreteType, fieldTypeName)) {
            return new Box(map);
        }
        return new RecordBox(concreteType, map);
    }

    private static String unwrap(Type<?> type) {
        if (type instanceof NonNullType nonNullType) {
            return unwrap(nonNullType.getType());
        }
        if (type instanceof ListType listType) {
            return unwrap(listType.getType());
        }
        if (type instanceof TypeName typeName) {
            return typeName.getName();
        }
        throw new IllegalStateException("Unsupported GraphQL type node " + type.getClass().getSimpleName());
    }

    private static String loadSdl() {
        try (InputStream input = StandaloneGraphqlRuntime.class.getResourceAsStream("/library.graphqls")) {
            if (input == null) {
                throw new IllegalStateException("Missing resource library.graphqls");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sampleQuery() {
        return """
                query {
                  library(id: "central") {
                    id
                    name
                    shelves {
                      code
                      featured {
                        __typename
                        ... on Book {
                          sku
                          title
                          author
                        }
                        ... on Gadget {
                          sku
                          name
                          warrantyMonths
                        }
                      }
                    }
                    highlighted {
                      __typename
                      ... on Book {
                        title
                      }
                      ... on Gadget {
                        name
                      }
                    }
                  }
                }
                """;
    }

	//
    static class Box {
        private final Map<String, Object> values;

        Box(Map<String, Object> values) {
            this.values = Map.copyOf(values);
        }

        Object value(String fieldName) {
            return values.get(fieldName);
        }
    }

	//
    static final class RecordBox extends Box {
        private final String typeName;

        RecordBox(String typeName, Map<String, Object> values) {
            super(values);
            this.typeName = typeName;
        }

        String typeName() {
            return typeName;
        }
    }

    // 
    static final class SampleRepository {
        private final Map<String, Map<String, Object>> libraries = Map.of("central", createCentralLibrary());

        Optional<Map<String, Object>> findLibrary(String id) {
            return Optional.ofNullable(libraries.get(id));
        }

        private static Map<String, Object> createCentralLibrary() {
            Map<String, Object> dune = book("B-100", "Dune", "Frank Herbert");
            Map<String, Object> sensorKit = gadget("G-200", "Sensor Kit", 24);

            return Map.of(
                    "id", "central",
                    "name", "Central Library",
                    "shelves", List.of(
                            shelf("A-01", dune),
                            shelf("B-09", sensorKit)
                    ),
                    "highlighted", sensorKit
            );
        }

        private static Map<String, Object> shelf(String code, Map<String, Object> featured) {
            return Map.of(
                    "code", code,
                    "featured", featured
            );
        }

        private static Map<String, Object> book(String sku, String title, String author) {
            Map<String, Object> book = new LinkedHashMap<>();
            book.put("__typename", "Book");
            book.put("sku", sku);
            book.put("title", title);
            book.put("author", author);
            return book;
        }

        private static Map<String, Object> gadget(String sku, String name, int warrantyMonths) {
            Map<String, Object> gadget = new LinkedHashMap<>();
            gadget.put("__typename", "Gadget");
            gadget.put("sku", sku);
            gadget.put("name", name);
            gadget.put("warrantyMonths", warrantyMonths);
            return gadget;
        }
    }
}
