package com.mimocodes.hrisrag;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full application context on a pgvector Testcontainer. All subclasses share ONE cached context (and container),
 * so keep these annotations identical across integration tests. Uses a placeholder Gemini key: no model calls.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.ai.google.genai.api-key=test-placeholder",
		"spring.ai.google.genai.embedding.api-key=test-placeholder" })
public abstract class IntegrationTestBase {
}
