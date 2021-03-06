/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web.client.feign;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.netflix.hystrix.HystrixCommand;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.HttpMessageConverters;
import org.springframework.cloud.netflix.feign.FeignAutoConfiguration;
import org.springframework.cloud.netflix.feign.FeignClientSpecification;
import org.springframework.cloud.netflix.feign.FeignContext;
import org.springframework.cloud.netflix.feign.support.ResponseEntityDecoder;
import org.springframework.cloud.netflix.feign.support.SpringDecoder;
import org.springframework.cloud.sleuth.SpanInjector;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.instrument.hystrix.SleuthHystrixAutoConfiguration;
import org.springframework.cloud.sleuth.instrument.web.HttpTraceKeysInjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

import feign.Client;
import feign.Feign;
import feign.FeignException;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration
 * Auto-configuration} enables span information propagation when using Feign.
 *
 * @author Marcin Grzejszczak
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(value = "spring.sleuth.feign.enabled", matchIfMissing = true)
@ConditionalOnClass(Client.class)
@ConditionalOnBean(Tracer.class)
@AutoConfigureBefore(FeignAutoConfiguration.class)
@AutoConfigureAfter(SleuthHystrixAutoConfiguration.class)
public class TraceFeignClientAutoConfiguration {

	@Autowired
	private ObjectFactory<HttpMessageConverters> messageConverters;

	@Bean
	@Scope("prototype")
	@ConditionalOnClass(HystrixCommand.class)
	@ConditionalOnProperty(name = "feign.hystrix.enabled", matchIfMissing = true)
	Feign.Builder feignHystrixBuilder(Tracer tracer, HttpTraceKeysInjector keysInjector) {
		return SleuthFeignBuilder.builder(tracer, keysInjector);
	}

	@Configuration
	protected static class FeignBeanPostProcessorConfiguration {

		@Bean
		@ConditionalOnProperty(name = "spring.sleuth.feign.processor.enabled", matchIfMissing = true)
		FeignBeanPostProcessor feignBeanPostProcessor(TraceFeignObjectWrapper traceFeignObjectWrapper) {
			return new FeignBeanPostProcessor(traceFeignObjectWrapper);
		}
	}

	@Bean
	TraceFeignObjectWrapper traceFeignObjectWrapper(BeanFactory beanFactory) {
		return new TraceFeignObjectWrapper(beanFactory);
	}

	@Bean
	@Primary
	Decoder feignDecoder(final Tracer tracer) {
		return new TraceFeignDecoder(tracer,
				new ResponseEntityDecoder(new SpringDecoder(this.messageConverters)) {
					@Override
					public Object decode(Response response, Type type)
							throws IOException, FeignException {
						FeignRequestContext feignRequestContext = FeignRequestContext
								.getInstance();
						FeignResponseHeadersHolder feignResponseHeadersHolder = new FeignResponseHeadersHolder(
								response.headers());
						feignResponseHeadersInjector().inject(
								feignRequestContext.getCurrentSpan(),
								feignResponseHeadersHolder);
						return super.decode(
								Response.create(response.status(), response.reason(),
										feignResponseHeadersHolder.responseHeaders,
										response.body()),
								type);
					}
				});
	}

	/**
	 * Sleuth {@link feign.RequestInterceptor} that either starts a new Span or continues
	 * an existing one if a retry takes place.
	 */
	@Bean
	RequestInterceptor traceIdRequestInterceptor(Tracer tracer) {
		return new TraceFeignRequestInterceptor(tracer, feignRequestTemplateInjector());
	}

	@Autowired(required = false)
	private List<FeignClientSpecification> configurations = new ArrayList<>();

	@Bean
	@Primary
	FeignContext sleuthFeignContext(TraceFeignObjectWrapper traceFeignObjectWrapper) {
		FeignContext feignContext = new TraceFeignContext(traceFeignObjectWrapper);
		feignContext.setConfigurations(this.configurations);
		return feignContext;
	}

	private SpanInjector<RequestTemplate> feignRequestTemplateInjector() {
		return new FeignRequestTemplateInjector();
	}

	private SpanInjector<FeignResponseHeadersHolder> feignResponseHeadersInjector() {
		return new FeignResponseHeadersInjector();
	}
}
