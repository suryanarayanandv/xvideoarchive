# X Edit Archive

This is the initial Spring Boot foundation for the X Edit Archive project.

## Quick start

1. Ensure Java 17+ and Maven are installed.
2. Run:
   ```bash
   mvn test
   ```
3. Start the app:
   ```bash
   mvn spring-boot:run
   ```
4. Submit a post URL:
   ```bash
   curl -X POST http://localhost:8080/api/v1/ingestions \
     -H "Content-Type: application/json" \
     -d '{"url":"https://x.com/example/status/123456789"}'
   ```

## Supported URL format

The initial implementation accepts status URLs such as:

```text
https://x.com/example/status/123456789
https://twitter.com/example/status/123456789
```

## Milestone status

This repository now includes the first milestone foundation:
- Spring Boot application bootstrap
- URL validation and parsing
- ingestion creation and lookup API
- JPA-backed persistence for ingestions
- tests for valid, invalid, unsupported, duplicate, and lookup cases

## Notes

This milestone intentionally does not yet include media retrieval, recognition, or account polling. Those remain for the next implementation phases described in the project plan.
