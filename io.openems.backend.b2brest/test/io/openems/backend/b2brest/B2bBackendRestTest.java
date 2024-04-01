package io.openems.backend.b2brest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class B2bBackendRestTest {

	private Backend2BackendRest service;
	private HttpClient client;

	@BeforeEach
	void setUp() throws Exception {

		Config config = new Config() {
			@Override
			public int port() {
				return 8080;
			}

			@Override
			public Class<? extends java.lang.annotation.Annotation> annotationType() {
				return null;
			}

			@Override
			public String webconsole_configurationFactory_nameHint() {
				return null;
			}
		};

		this.service = new Backend2BackendRest();

		Method activateMethod = Backend2BackendRest.class.getDeclaredMethod("activate", Config.class);
		activateMethod.setAccessible(true);
		activateMethod.invoke(this.service, config);

		this.client = new HttpClient();
		this.client.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		Method deactivateMethod = Backend2BackendRest.class.getDeclaredMethod("deactivate");
		deactivateMethod.setAccessible(true);
		deactivateMethod.invoke(this.service);

		this.client.stop();
	}

	@Test
	void testActivateDeactivate() {
		assertNotNull(this.service, "Service should be activated.");
	}

	@Test
	void testJsonRpcRequestHandling() throws Exception {
		String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{\"sample\":true},\"id\":1}";
		ContentResponse response = this.client.newRequest("http://localhost:8080/jsonrpc").method(HttpMethod.POST)
				.content(new org.eclipse.jetty.client.util.StringContentProvider(jsonRpcRequest), "application/json")
				.send();
		assertEquals(200, response.getStatus(), "Response status should be 200 OK.");

		JsonObject responseObject = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
		assertEquals("2.0", responseObject.get("jsonrpc").getAsString(), "JSON-RPC version should be 2.0.");
		assertEquals(1, responseObject.get("id").getAsInt(), "Response ID should match request ID.");
		assertTrue(responseObject.has("result") || responseObject.has("error"),
				"Response should contain either 'result' or 'error'.");
	}
}
