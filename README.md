# Standalone GraphQL Runtime Example

This directory contains a minimal GraphQL Java example.

The example shows four things:

1. A simple SDL in `src/main/resources/library.graphqls`
2. Parsing that SDL into a `TypeDefinitionRegistry`
3. Auto-wiring resolvers by iterating over SDL object and union definitions
4. Using small `Box` objects so nested resolvers can read `env.getSource()`, and a `RecordBox`
   so union resolution can recover the concrete member type

The chosen domain is intentionally small:

- `Query.library(id: ID!): Library`
- `Library.shelves: [Shelf!]!`
- `Library.highlighted: FeaturedItem`
- `Shelf.featured: FeaturedItem`
- `FeaturedItem = Book | Gadget`

## Request and resolver sequence

The important split is that resolver setup happens in `StandaloneGraphqlRuntime()` when the
`GraphQL` instance is constructed. Actual resolving happens later, per call to `execute(query)`,
when GraphQL Java invokes the already-registered `DataFetcher` and `TypeResolver` closures.

```mermaid
%%{init: {"sequence": {"messageMargin": 48, "noteMargin": 16}, "themeVariables": {"primaryTextColor": "#ffffff", "actorTextColor": "#ffffff", "signalColor": "#000000", "signalTextColor": "#000000", "labelTextColor": "#000000", "noteBkgColor": "#222222", "noteBorderColor": "#000000", "noteTextColor": "#ffffff", "tertiaryColor": "#222222", "tertiaryTextColor": "#ffffff", "sequenceNumberColor": "#000000"}}}%%
sequenceDiagram
    autonumber
    participant App as App / main / test
    participant Runtime as StandaloneGraphqlRuntime
    participant Parser as SchemaParser
    participant Registry as TypeDefinitionRegistry
    participant Wiring as RuntimeWiring.Builder
    participant Generator as SchemaGenerator
    participant GraphQL as GraphQL
    participant Repo as SampleRepository
    participant Fetcher as DataFetcher closures
    participant TypeResolver as TypeResolver closures

    rect rgb(255, 255, 255)
        Note over App,GraphQL: Setup, once per StandaloneGraphqlRuntime instance
        App->>Runtime: new StandaloneGraphqlRuntime()
        Runtime->>Parser: parse(loadSdl())
        Parser-->>Runtime: TypeDefinitionRegistry
        Runtime->>Wiring: newRuntimeWiring()
        Runtime->>Repo: new SampleRepository()
        Runtime->>Registry: get Query fields
        Runtime->>Wiring: wireQueries(...)
        Note over Runtime,Fetcher: Creates Query.library fetcher closure capturing fieldName and repository
        Runtime->>Registry: get object type fields
        Runtime->>Wiring: wireObjects(...)
        Note over Runtime,Fetcher: Creates object field fetcher closures capturing typeName, fieldName, fieldTypeName, scalarLike
        Runtime->>Registry: get union definitions
        Runtime->>Wiring: wireUnions(...)
        Note over Runtime,TypeResolver: Creates union resolver closures capturing unionName
        Runtime->>Generator: makeExecutableSchema(registry, wiring.build())
        Generator-->>Runtime: GraphQLSchema
        Runtime->>GraphQL: newGraphQL(schema).build()
        GraphQL-->>Runtime: reusable GraphQL engine
    end

    rect rgb(255, 255, 255)
        Note over App,TypeResolver: Query execution, once per request
        App->>Runtime: execute(query)
        Runtime->>GraphQL: execute(ExecutionInput(query))
        GraphQL->>Fetcher: Query.library(env)
        Fetcher->>Repo: findLibrary(id)
        Repo-->>Fetcher: Map for library
        Fetcher-->>GraphQL: Box(library)
        GraphQL->>Fetcher: Library.id/name/shelves/highlighted(env)
        Note over GraphQL,Fetcher: env.getSource() is the parent Box and fieldName selects the Map value
        Fetcher-->>GraphQL: scalar value, Box, RecordBox, or List of boxed values
        GraphQL->>TypeResolver: FeaturedItem resolver(env)
        Note over GraphQL,TypeResolver: env.getObject() is a RecordBox preserving Book or Gadget
        TypeResolver-->>GraphQL: concrete GraphQL object type
        GraphQL->>Fetcher: Book.* or Gadget.* field fetchers(env)
        Fetcher-->>GraphQL: selected scalar fields
        GraphQL-->>Runtime: ExecutionResult
        Runtime-->>App: ExecutionResult
    end
```

In other words, the `TypeDefinitionRegistry` and SDL inspection are setup inputs. They are used to
create runtime wiring and closures, but they are not re-inspected for each field during query
execution. During execution, GraphQL Java walks the query against the executable schema and calls the
closures that were registered during setup.

## Closure trick

Each field resolver is created while iterating over the SDL. The resolver captures:

- the GraphQL type name
- the field name
- the unwrapped field type
- whether the field is scalar-like or object-like

This "closure trick", is based on expensive SDL inspection happening once up front, 
and that information is captured at setuptime so that when GraphQL Java later invokes
a tiny closure per requested field, it is based on information that was available at
setup time. The resolver becomes very simple.

## Box trick

Root resolvers return a `Box` rather than a raw `Map`. Nested resolvers then do:

- `env.getSource()` -> parent `Box`
- `box.value(fieldName)` -> underlying field value
- scalar values are returned directly
- object or union values are wrapped in a new `Box`

For union members, the runtime wraps the value in a `RecordBox` that preserves the concrete type
name (`Book` or `Gadget`). The union `TypeResolver` uses that information to choose the GraphQL
object type.

## Files

- `src/main/resources/library.graphqls`: SDL
- `src/main/java/example/graphql/StandaloneGraphqlRuntime.java`: runtime, sample data, and `main`
- `src/test/java/example/graphql/StandaloneGraphqlRuntimeTest.java`: executes a sample query

## Run

```bash
mvn test
mvn exec:java
```

The `main` method runs a sample query and prints the JSON result.
