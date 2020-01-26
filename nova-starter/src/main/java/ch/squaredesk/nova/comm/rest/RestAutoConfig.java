/*
 * Copyright (c) 2020 Squaredesk GmbH and Oliver Dotzauer.
 *
 * This program is distributed under the squaredesk open source license. See the LICENSE file
 * distributed with this work for additional information regarding copyright ownership. You may also
 * obtain a copy of the license at
 *
 *   https://squaredesk.ch/license/oss/LICENSE
 *
 */

package ch.squaredesk.nova.comm.rest;

import ch.squaredesk.nova.Nova;
import ch.squaredesk.nova.comm.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        HttpServerConfigurationProperties.class,
        HttpClientConfigurationProperties.class,
        RestConfigurationProperties.class})
@ConditionalOnClass(HttpAdapter.class)
@AutoConfigureBefore(HttpServerAutoConfig.class)
public class RestAutoConfig {

    @Bean
    @ConditionalOnProperty(name = "nova.http.rest.log-invocations", havingValue = "true", matchIfMissing = true)
    RestInvocationLogger restInvocationLogger() {
        return new RestInvocationLogger();
    }

    @Bean
    @ConditionalOnMissingBean(name = BeanIdentifiers.SERVER)
    RestServerStarter restServerStarter(HttpServerConfigurationProperties httpServerConfigurationProperties,
                                        RestConfigurationProperties restConfigurationProperties,
                                        RestBeanPostprocessor restBeanPostprocessor,
                                        @Qualifier(BeanIdentifiers.OBJECT_MAPPER) @Autowired(required = false) ObjectMapper httpObjectMapper,
                                        Nova nova) {

        ch.squaredesk.nova.comm.http.HttpServerSettings serverSettings =
                ch.squaredesk.nova.comm.http.HttpServerSettings.builder()
                        .port(httpServerConfigurationProperties.getPort())
                        .compressData(httpServerConfigurationProperties.isCompressData())
                        .sslNeedsClientAuth(httpServerConfigurationProperties.isSslNeedsClientAuth())
                        .sslKeyStorePass(httpServerConfigurationProperties.getSslKeyStorePass())
                        .sslKeyStorePath(httpServerConfigurationProperties.getSslKeyStorePath())
                        .sslTrustStorePass(httpServerConfigurationProperties.getSslTrustStorePass())
                        .sslTrustStorePath(httpServerConfigurationProperties.getSslTrustStorePath())
                        .interfaceName(httpServerConfigurationProperties.getInterfaceName())
                        .addCompressibleMimeTypes(httpServerConfigurationProperties.getCompressibleMimeTypes().toArray(new String[0]))
                        .build();

        return new RestServerStarter(
                serverSettings,
                restConfigurationProperties.getServerProperties(),
                restBeanPostprocessor,
                httpObjectMapper,
                restConfigurationProperties.isCaptureMetrics(),
                nova);
    }

    @Bean
    RestBeanPostprocessor restBeanPostprocessor() {
        return new RestBeanPostprocessor();
    }

}