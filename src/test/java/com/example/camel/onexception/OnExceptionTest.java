package com.example.camel.onexception;

import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spring.javaconfig.SingleRouteCamelConfiguration;
import org.apache.camel.test.spring.CamelSpringBootRunner;
import org.apache.camel.test.spring.MockEndpoints;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.Mockito.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

@RunWith(CamelSpringBootRunner.class)
@SpringBootTest
@MockEndpoints
@ContextConfiguration
public class OnExceptionTest {

    @Configuration
    static class OnExceptionTestConfiguration extends SingleRouteCamelConfiguration {

        @Bean
        public DummyService dummyService() {
            return mock(DummyService.class);

        }

        @Bean
        public RouteBuilder route() {
            return new RouteBuilder() {

                @Override
                public void configure() throws Exception {
                    errorHandler(deadLetterChannel("mock:error")
                        .redeliveryDelay(0)
                        .logStackTrace(true));

                    from("direct:start")
                    .onException(IllegalStateException.class)
                        .maximumRedeliveries(5)
                        .redeliveryDelay(1000)
                        .logRetryAttempted(true)
                        .logExhausted(true)
                        .handled(true)
                    .end()
                    .bean("dummyService", "doSomething(${body})")
                    .to("mock:result");
                }
            };
        }
    }

    @EndpointInject(uri = "mock:result")
    private MockEndpoint resultEndpoint;

    @EndpointInject(uri = "mock:error")
    private MockEndpoint errorEndpoint;

    @Produce(uri = "direct:start")
    private ProducerTemplate producer;

    @Autowired
    private DummyService dummyService;

    @Test
    @DirtiesContext
    public void testRoute() throws Exception {
        final String messageBody = "Test message";

        /* Throw a single IllegalStateException followed thereafter by NullPointerExceptions. */
        when(dummyService.doSomething(messageBody))
                .thenThrow(new IllegalStateException())
                .thenThrow(new NullPointerException());

        errorEndpoint.expectedMessageCount(1);

        producer.sendBody(messageBody);

        errorEndpoint.assertIsSatisfied();

        /*
         * Since there is only a redelivery policy configured for IllegalStateException, it seems
         * logical that when a different exception e.g. a NullPointerException is subsequently
         * thrown then the redelivery policy should not be applied and the message should be routed
         * to the dead letter channel. This is not the case, however, as redelivery attempts are
         * continually made regardless of the type of exception thrown in those subsequent attempts.
         */
        verify(dummyService, times(6)).doSomething(messageBody);
    }
}