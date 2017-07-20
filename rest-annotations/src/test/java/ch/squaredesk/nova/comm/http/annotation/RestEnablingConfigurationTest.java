/*
 * Copyright (c) Squaredesk GmbH and Oliver Dotzauer.
 *
 * This program is distributed under the squaredesk open source license. See the LICENSE file
 * distributed with this work for additional information regarding copyright ownership. You may also
 * obtain a copy of the license at
 *
 *   https://squaredesk.ch/license/oss/LICENSE
 */

package ch.squaredesk.nova.comm.http.annotation;

import ch.squaredesk.nova.comm.http.HttpServerConfiguration;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
<<<<<<< HEAD
import org.springframework.context.ApplicationContext;
=======
>>>>>>> admin-work
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestEnablingConfigurationTest {
    HttpServerConfiguration serverConfiguration;
    ResourceConfig resourceConfig;

<<<<<<< HEAD
    private ApplicationContext setupContext(Class configClass) throws Exception {
=======
    private void setupContext(Class configClass) throws Exception {
>>>>>>> admin-work
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(configClass);
        ctx.refresh();
        serverConfiguration = ctx.getBean(HttpServerConfiguration.class);
        resourceConfig = ctx.getBean(ResourceConfig.class);
<<<<<<< HEAD
        return ctx;
=======
>>>>>>> admin-work
    }

    @BeforeEach
    void clearEnvironment() {
        System.clearProperty("NOVA.HTTP.REST.INTERFACE_NAME");
        System.clearProperty("NOVA.HTTP.REST.PORT");
<<<<<<< HEAD
        System.clearProperty("NOVA.HTTP.REST.CAPTURE_METRICS");
    }

    @Test
    void ifNothingSpecifiedMetricsAreCaptured() throws Exception{
        ApplicationContext ctx = setupContext(MyConfig.class);
        assertThat(ctx.getBean("captureRestMetrics"), is(true));
    }

    @Test
    void metricsCanBeSwitchedOffWithEnvVariable() throws Exception{
        System.setProperty("NOVA.HTTP.REST.CAPTURE_METRICS", "false");
        ApplicationContext ctx = setupContext(MyConfig.class);
        assertThat(ctx.getBean("captureRestMetrics"), is(false));
    }

    @Test
    void ifNothingSpecifiedRestServerListensOn10000OnAllInterfaces() throws Exception{
        setupContext(MyConfig.class);
        assertThat(serverConfiguration.port, is(10000));
=======
    }

    @Test
    void ifNothingSpecifiedRestServerListensOn8888OnAllInterfaces() throws Exception{
        setupContext(MyConfig.class);
        assertThat(serverConfiguration.port, is(8080));
>>>>>>> admin-work
        assertThat(serverConfiguration.interfaceName, is("0.0.0.0"));
        assertTrue(resourceConfig.getResources().isEmpty());
    }

    @Test
    void portCanBeOverridenWithEnvironmentVariable() throws Exception{
        System.setProperty("NOVA.HTTP.REST.PORT", "9999");
        setupContext(MyConfig.class);
        assertThat(serverConfiguration.port, is(9999));
        assertTrue(resourceConfig.getResources().isEmpty());
    }

    @Test
    void interfaceCanBeOverridenWithEnvironmentVariable() throws Exception{
        System.setProperty("NOVA.HTTP.REST.INTERFACE_NAME", "myInterface");
        setupContext(MyConfig.class);
        assertThat(serverConfiguration.interfaceName, is("myInterface"));
        assertTrue(resourceConfig.getResources().isEmpty());
    }

    @Test
    void annotatedBeansAreAddedToResourceConfig() throws Exception{
        setupContext(MyConfigWithAnnotatedBean.class);
        assertThat(resourceConfig.getResources().size(), is(2));
    }

    @Configuration
    @Import(RestEnablingConfiguration.class)
    public static class MyConfig {
    }

    @Configuration
    @Import(RestEnablingConfiguration.class)
    public static class MyConfigWithAnnotatedBean {
        @Bean
        public MyDummyBeanToHaveAtLeastOneRestEndpoint dummyBeanToHaveAtLeastOneRestEndpoint() {
            return new MyDummyBeanToHaveAtLeastOneRestEndpoint();
        }
    }

    public static class MyDummyBeanToHaveAtLeastOneRestEndpoint {
        @OnRestRequest("somePath")
        public void foo() {
        }

        @OnRestRequest("someAdditionalPath")
        public void bar() {
        }
    }
}
