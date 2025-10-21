package dev.victormartin.oci.genai.backend.backend;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import dev.victormartin.oci.genai.backend.backend.data.InteractionRepository;
import dev.victormartin.oci.genai.backend.backend.service.GenAiClientService;
import dev.victormartin.oci.genai.backend.backend.service.GenAiInferenceClientService;
import dev.victormartin.oci.genai.backend.backend.service.OCIGenAIService;

@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		LiquibaseAutoConfiguration.class,
		JpaRepositoriesAutoConfiguration.class
})
@SpringBootTest
class BackendApplicationTests {

	// Replace OCI clients with mocks so no external calls/config parsing are done in tests
	@MockBean
	private GenAiInferenceClientService genAiInferenceClientService;

	@MockBean
	private OCIGenAIService ociGenAIService;

	@MockBean
	private GenAiClientService genAiClientService;

	@MockBean
	private InteractionRepository interactionRepository;

	@MockBean
	private DataSource dataSource;

	@Test
	void contextLoads() {
		// Context should load without connecting to Oracle DB or OCI
	}
}
