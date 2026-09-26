# Deep Dive: AWS DynamoDB Vector Search

This document explains the Java classes in the project, how Spring Boot wires them together, and how a paper moves through ingestion, embedding generation, DynamoDB storage, and similarity search.

## 1. End-to-end execution flow

When the application starts, Spring Boot creates the configured beans and then invokes `DatabaseInitializerRunner`:

```text
Application startup
        |
        v
Create or verify DynamoDB table
        |
        v
Download and parse arXiv dataset
        |
        v
For each paper:
  title + abstract -> Amazon Bedrock embedding
  paper metadata + embedding -> DynamoDB
        |
        v
Embed search query
        |
        v
Scan DynamoDB and calculate cosine similarity
        |
        v
Print top K papers
```

## 2. Application entry point

**File:** `AwsDynamodbVectorSearchApplication.java`

```java
@SpringBootApplication
public class AwsDynamodbVectorSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AwsDynamodbVectorSearchApplication.class, args);
    }
}
```

`@SpringBootApplication` combines three Spring features:

- `@Configuration`: allows the class to contribute application configuration.
- `@EnableAutoConfiguration`: configures Spring Boot based on dependencies on the classpath.
- `@ComponentScan`: discovers components such as `@Service`, `@Component`, and `@Configuration` classes below the application package.

`SpringApplication.run(...)` starts the application context, creates all dependencies, and executes `CommandLineRunner` beans after startup.

## 3. Configuration properties

**File:** `config/AppProperties.java`

```java
@ConfigurationProperties(prefix = "app.aws")
public record AppProperties(
        DynamoDbProperties dynamoDb,
        BedrockProperties bedrockProperties,
        DatasetProperties dataset) {

    public record DynamoDbProperties(String tableName, String indexName) {}

    public record BedrockProperties(
            String modelId,
            Long dimensions,
            String distanceFunction,
            int maxEmbedChars) {}

    public record DatasetProperties(String url, Long sampleSize) {}
}
```

`@ConfigurationProperties(prefix = "app.aws")` maps values from `application.properties` into an immutable record:

```properties
app.aws.dynamo-db.table-name=ArxivPaperVectorStore
app.aws.bedrock-properties.model-id=amazon.titan-embed-text-v2:0
app.aws.dataset.sample-size=1000
```

Spring's relaxed binding maps:

| Properties key | Java accessor |
| --- | --- |
| `app.aws.dynamo-db.table-name` | `properties.dynamoDb().tableName()` |
| `app.aws.bedrock-properties.model-id` | `properties.bedrockProperties().modelId()` |
| `app.aws.dataset.sample-size` | `properties.dataset().sampleSize()` |

Using a record makes the configuration immutable after binding and gives the application type-safe access to configuration values.

## 4. AWS client configuration

**File:** `config/AwsConfig.java`

```java
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AwsConfig {

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.create();
    }
}
```

`@EnableConfigurationProperties(AppProperties.class)` registers `AppProperties` as a Spring bean.

`DynamoDbClient.create()` and `BedrockRuntimeClient.create()` use the AWS SDK default provider chain for:

- AWS credentials
- AWS Region
- SDK client settings

The clients are injected into `DynamoDbTableManager`, `PaperVectorService`, and `EmbeddingServiceImpl`.

## 5. Domain model

**File:** `domain/ArxivPaper.java`

```java
public record ArxivPaper(
        String id,
        String title,
        String abstractText,
        String authors) {

    public ArxivPaper {
        id = (id == null) ? "" : id;
        title = (title == null) ? "" : title.trim();
        abstractText = (abstractText == null) ? "" : abstractText.trim();
        authors = (authors == null) ? "" : authors.trim();
    }
}
```

`ArxivPaper` is an immutable data-transfer object. Its compact constructor normalizes incoming values:

- Null IDs become empty strings.
- Null text values become empty strings.
- Titles, abstracts, and authors are trimmed.

This keeps the rest of the application from repeatedly checking for null paper fields.

## 6. Dataset streaming abstraction

**File:** `service/DatasetStreamer.java`

```java
public interface DatasetStreamer {
    List<ArxivPaper> loadPapers() throws Exception;
}
```

The interface separates the dataset source from the application workflow. `DatabaseInitializerRunner` depends on the abstraction rather than directly depending on HTTP or gzip implementation details.

This makes it possible to add another implementation later, such as:

- Reading papers from Amazon S3
- Reading a local test fixture
- Reading from a database
- Reading from an API

## 7. Gzip HTTP dataset implementation

**File:** `service/GzipHttpDatasetStreamer.java`

### Dependencies

```java
@Service
public class GzipHttpDatasetStreamer implements DatasetStreamer {

    private final AppProperties config;
    private final JsonMapper mapper;
}
```

`@Service` registers this class as the default `DatasetStreamer` implementation. Spring injects the application configuration and Jackson `JsonMapper`.

### HTTP request and redirects

```java
try (var client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()) {
    var request = HttpRequest.newBuilder()
            .uri(URI.create(datasetUrl))
            .header("User-Agent", "Mozilla/5.0")
            .GET()
            .build();

    HttpResponse<InputStream> response =
            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    // Validate and process the response inside this resource scope.
}
```

The dataset URL redirects to a content-delivery endpoint, so redirects are enabled. The response body is received as an `InputStream` to avoid loading the complete compressed file into memory.

`HttpClient` implements `AutoCloseable`, so it is declared in the outer try-with-resources block. This ensures the HTTP client's resources are released after the request and response processing finish, including when an exception occurs.

### Response validation

```java
if (response.statusCode() != 200) {
    throw new RuntimeException(
            "Failed to download dataset. HTTP status code: "
                    + response.statusCode());
}
```

Non-success responses stop processing instead of being treated as valid dataset content.

### Gzip and JSON Lines parsing

```java
try (var client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()) {
    HttpResponse<InputStream> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream());

    try (var gzStream = new GZIPInputStream(response.body());
         var reader = new BufferedReader(
                 new InputStreamReader(gzStream, StandardCharsets.UTF_8))) {

        String line;
        while ((line = reader.readLine()) != null
                && papers.size() < config.dataset().sampleSize()) {
            if (line.trim().isEmpty()) {
                continue;
            }

            var jsonNode = mapper.readTree(line);
            // Read the paper fields and create an ArxivPaper.
        }
    }
}
```

The stream is processed line by line:

1. `GZIPInputStream` decompresses the response.
2. `BufferedReader` reads one JSON Lines record at a time.
3. `JsonMapper` parses each line.
4. The sample-size limit stops reading after the configured number of records.
5. The outer try-with-resources closes `HttpClient`; the inner try-with-resources closes the HTTP response body, `GZIPInputStream`, and `BufferedReader`.

This nested resource structure prevents connection and stream leaks while preserving streaming behavior. The compressed dataset is still processed incrementally rather than loaded entirely into memory.

### Field extraction

```java
String id = jsonNode.path("id").asString("");
String title = jsonNode.path("title").asString("");
String abstractText = jsonNode.path("abstract").asString("");
String authors = jsonNode.path("authors").asString("");

papers.add(new ArxivPaper(id, title, abstractText, authors));
```

The `path(...).asString("")` pattern provides an empty-string default when a field is absent. The `ArxivPaper` constructor then applies its own normalization.

## 8. Embedding service abstraction

**File:** `service/EmbeddingService.java`

```java
public interface EmbeddingService {
    String clean(String text);
    List<Float> generateEmbedding(String text);
}
```

The interface defines two responsibilities:

- Normalize text before it is sent to the model.
- Convert text into a numeric vector.

`PaperVectorService` depends on this interface, which keeps storage and search logic independent from the Bedrock request implementation.

## 9. Amazon Bedrock embedding implementation

**File:** `service/EmbeddingServiceImpl.java`

### Service dependencies

```java
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final AppProperties properties;
    private final JsonMapper jsonMapper;
}
```

The class uses:

- `BedrockRuntimeClient` to invoke the embedding model.
- `AppProperties` for model and request settings.
- `JsonMapper` to create and parse JSON payloads.

### Text cleaning and length control

```java
@Override
public String clean(String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").trim();
}
```

This collapses repeated whitespace and removes leading or trailing whitespace.

```java
String inputText = clean(text);
if (inputText.length() > properties.bedrockProperties().maxEmbedChars()) {
    inputText = inputText.substring(
            0,
            properties.bedrockProperties().maxEmbedChars());
}
```

The input is truncated to the configured maximum before calling Bedrock. This protects the request from exceeding the application’s configured character limit.

### Titan request payload

```java
var payload = jsonMapper.createObjectNode();
payload.put("inputText", inputText);
payload.put("dimensions",
        properties.bedrockProperties().dimensions());
payload.put("normalize", true);
```

For the current configuration, the generated JSON is equivalent to:

```json
{
  "inputText": "cleaned paper text",
  "dimensions": 1024,
  "normalize": true
}
```

`normalize: true` requests normalized embeddings, which is useful for similarity comparisons.

### Bedrock model invocation

```java
var invokeModelRequest = InvokeModelRequest.builder()
        .modelId(properties.bedrockProperties().modelId())
        .contentType("application/json")
        .accept("application/json")
        .body(SdkBytes.fromUtf8String(
                jsonMapper.writeValueAsString(payload)))
        .build();

String responseBody = bedrockRuntimeClient
        .invokeModel(invokeModelRequest)
        .body()
        .asUtf8String();
```

The request body is serialized to UTF-8 bytes because the AWS SDK `InvokeModelRequest` expects an `SdkBytes` payload.

The configured model is:

```properties
app.aws.bedrock-properties.model-id=amazon.titan-embed-text-v2:0
```

### Response parsing

```java
JsonNode root = jsonMapper.readTree(responseBody);
List<Float> embedding = new ArrayList<>();

for (JsonNode node : root.get("embedding")) {
    embedding.add((float) node.asDouble());
}

return embedding;
```

The Bedrock response contains an `embedding` JSON array. Each number is converted into a `Float`, producing the vector used by DynamoDB storage and cosine similarity.

## 10. DynamoDB table manager

**File:** `infrastructure/DynamoDbTableManager.java`

### Component dependencies

```java
@Component
public class DynamoDbTableManager {

    private final DynamoDbClient dynamodb;
    private final AppProperties config;
}
```

This class owns table lifecycle operations. It does not store papers or calculate vectors.

### Table creation

```java
var request = CreateTableRequest.builder()
        .tableName(config.dynamoDb().tableName())
        .attributeDefinitions(
                AttributeDefinition.builder()
                        .attributeName("paper_id")
                        .attributeType(ScalarAttributeType.S)
                        .build())
        .keySchema(
                KeySchemaElement.builder()
                        .attributeName("paper_id")
                        .keyType(KeyType.HASH)
                        .build())
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build();

dynamodb.createTable(request);
```

The table has:

- Table name: `ArxivPaperVectorStore`
- Partition key: `paper_id`
- Partition-key type: String
- Billing mode: on-demand / pay per request

Only the key attribute must be declared in `CreateTableRequest`. Other item attributes are schemaless in DynamoDB.

### Existing-table handling

```java
try {
    dynamodb.createTable(request);
} catch (ResourceInUseException e) {
    out.println("Table already exists. Proceeding...");
}
```

If the table already exists, the AWS SDK raises `ResourceInUseException`. The application treats that condition as expected and continues.

### Waiting for an active table

```java
while (true) {
    var request = DescribeTableRequest.builder()
            .tableName(config.dynamoDb().tableName())
            .build();

    var status = dynamodb.describeTable(request)
            .table()
            .tableStatus();

    if (TableStatus.ACTIVE.equals(status)) {
        return;
    }

    Thread.sleep(2000);
}
```

Table creation is asynchronous. The polling loop prevents writes from starting until DynamoDB reports the table as `ACTIVE`.

## 11. Paper storage and vector search

**File:** `service/PaperVectorService.java`

### Dependencies

```java
@Service
public class PaperVectorService {

    private final DynamoDbClient dynamoDbClient;
    private final EmbeddingService embeddingService;
    private final AppProperties properties;
}
```

This class connects three operations:

1. Text normalization.
2. Bedrock embedding generation.
3. DynamoDB persistence and retrieval.

### Building the paper embedding

```java
String cleanTitle = embeddingService.clean(paper.title());
String cleanAbstract = embeddingService.clean(paper.abstractText());

List<Float> embedding =
        embeddingService.generateEmbedding(
                cleanTitle + "." + cleanAbstract);
```

The stored paper embedding is based on the cleaned title followed by the cleaned abstract:

```text
clean(title) + "." + clean(abstract)
```

Authors are stored as metadata but are not included in the embedding input.

### Converting the vector for DynamoDB

```java
List<AttributeValue> embeddingDetails = embedding.stream()
        .map(value -> AttributeValue.builder()
                .n(String.valueOf(value))
                .build())
        .toList();
```

Each floating-point value is converted to a DynamoDB Number attribute. The complete vector is then stored as a DynamoDB List.

### Building and writing the item

```java
Map<String, AttributeValue> items = Map.of(
        "paper_id", AttributeValue.builder().s(paper.id()).build(),
        "title", AttributeValue.builder().s(cleanTitle).build(),
        "abstract", AttributeValue.builder().s(cleanAbstract).build(),
        "authors", AttributeValue.builder()
                .s(embeddingService.clean(paper.authors()))
                .build(),
        "embedding", AttributeValue.builder()
                .l(embeddingDetails)
                .build()
);

dynamoDbClient.putItem(PutItemRequest.builder()
        .tableName(properties.dynamoDb().tableName())
        .item(items)
        .build());
```

The DynamoDB item contains:

| Attribute | DynamoDB type | Purpose |
| --- | --- | --- |
| `paper_id` | String | Partition key |
| `title` | String | Displayable paper title |
| `abstract` | String | Displayable paper abstract |
| `authors` | String | Paper author metadata |
| `embedding` | List of Numbers | Semantic vector |

### Creating a query vector

```java
List<Float> queryVector =
        embeddingService.generateEmbedding(queryText);
```

The query uses the same embedding model and settings as the stored papers. This is important because cosine similarity is meaningful only when both vectors come from a compatible embedding space.

### Reading stored vectors

```java
var scanResponse = dynamoDbClient.scan(
        ScanRequest.builder()
                .tableName(properties.dynamoDb().tableName())
                .build());
```

The current implementation uses `Scan`, which reads the table records. It is simple for a demonstration but becomes expensive and slow as the table grows.

### Calculating similarity

```java
List<Float> itemVector = item.get("embedding").l().stream()
        .map(av -> Float.parseFloat(av.n()))
        .toList();

double similarity =
        calculateCosineSimilarity(queryVector, itemVector);
```

The stored DynamoDB number list is converted back into a Java `List<Float>` before comparison.

The cosine implementation is:

```java
return dotProduct
        / (Math.sqrt(normA) * Math.sqrt(normB));
```

Mathematically:

```text
cosine(A, B) = dot(A, B) / (||A|| * ||B||)
```

The method returns `0.0` when vectors are null, empty, different lengths, or have a zero norm.

### Ranking and limiting results

```java
return scoredItems.stream()
        .sorted((e1, e2) ->
                Double.compare(e2.getValue(), e1.getValue()))
        .limit(topK)
        .map(Map.Entry::getKey)
        .toList();
```

Results are sorted from highest similarity to lowest similarity and limited to the requested number of records.

## 12. Startup workflow runner

**File:** `runner/DatabaseInitializerRunner.java`

```java
@Component
public class DatabaseInitializerRunner
        implements CommandLineRunner {
    // ...
}
```

Spring invokes `run(...)` after the application context has started.

### Step 1: Prepare DynamoDB

```java
tableManager.createTableIfNotExists();
tableManager.waitUntilActive();
```

This ensures the target table exists and is ready for writes.

### Step 2: Ingest and index papers

```java
List<ArxivPaper> papers = datasetStreamer.loadPapers();

for (ArxivPaper paper : papers) {
    paperVectorService.storePaper(paper);
}
```

Each paper causes one embedding request and one DynamoDB write.

### Step 3: Search

```java
String query = "quantum physics and qubit states";
List<Map<String, AttributeValue>> results =
        paperVectorService.searchInMemory(query, 2);
```

The runner requests the top two papers for the sample query and prints their IDs and titles.

## 13. Configuration reference

**File:** `src/main/resources/application.properties`

```properties
app.aws.dynamo-db.table-name=ArxivPaperVectorStore
app.aws.dynamo-db.index-name=PaperVectorIndex
app.aws.bedrock-properties.model-id=amazon.titan-embed-text-v2:0
app.aws.bedrock-properties.dimensions=1024
app.aws.bedrock-properties.distance-function=COSINE
app.aws.bedrock-properties.max-embed-chars=20000
app.aws.bedrock-properties.normalize=true
app.aws.dataset.url=https://huggingface.co/datasets/gfissore/arxiv-abstracts-2021/resolve/main/arxiv-abstracts.jsonl.gz
app.aws.dataset.sample-size=1000
```

| Property | Used by | Meaning |
| --- | --- | --- |
| `app.aws.dynamo-db.table-name` | `DynamoDbTableManager`, `PaperVectorService` | DynamoDB table name |
| `app.aws.dynamo-db.index-name` | Configuration only currently | Reserved for a future index |
| `app.aws.bedrock-properties.model-id` | `EmbeddingServiceImpl` | Bedrock embedding model |
| `app.aws.bedrock-properties.dimensions` | `EmbeddingServiceImpl` | Requested vector dimensions |
| `app.aws.bedrock-properties.distance-function` | Configuration only currently | Planned distance metric |
| `app.aws.bedrock-properties.max-embed-chars` | `EmbeddingServiceImpl` | Maximum input characters |
| `app.aws.bedrock-properties.normalize` | `EmbeddingServiceImpl`, `PaperVectorService` | Whether Bedrock normalizes vectors |
| `app.aws.dataset.url` | `GzipHttpDatasetStreamer` | Dataset download URL |
| `app.aws.dataset.sample-size` | `GzipHttpDatasetStreamer` | Maximum records to load |

## 14. Current limitations and production considerations

The project is a focused demonstration. Important production improvements would include:

- Use paginated DynamoDB reads instead of a single `Scan`. The implementation now
  scans all pages and maintains only the best K results in memory.
- Use a vector-capable index or managed vector search service for large datasets.
- Add retries and backoff for Bedrock throttling and transient AWS errors.
- Batch or queue ingestion to control model-invocation rate.
- Move the sample query into an API endpoint.
- Validate the Bedrock response vector dimension before storing or searching it.
- Add configuration validation for required values and positive dimensions.
- Avoid long-running ingestion directly inside application startup.
- Add structured logging, metrics, tracing, and cost monitoring.

## 15. Class relationship summary

```text
AwsDynamodbVectorSearchApplication
                |
                v
DatabaseInitializerRunner
     |              |                 |
     v              v                 v
TableManager   DatasetStreamer   PaperVectorService
                                      |
                                      v
                              EmbeddingService
                               /           \
                              v             v
                     BedrockRuntime    DynamoDbClient

AppProperties and AwsConfig provide configuration and AWS clients
to the application components.
```
