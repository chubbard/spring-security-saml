/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.springframework.security.config.annotation.web.configurers;

import java.io.IOException;
import java.util.function.Supplier;
import javax.servlet.Filter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml.SamlException;
import org.springframework.security.saml.SamlTemplateEngine;
import org.springframework.security.saml.SamlTransformer;
import org.springframework.security.saml.serviceprovider.DefaultServiceProviderResolver;
import org.springframework.security.saml.serviceprovider.ServiceProviderResolver;
import org.springframework.security.saml.serviceprovider.authentication.SamlAuthenticationFailureHandler;
import org.springframework.security.saml.serviceprovider.configuration.ServiceProviderConfigurationResolver;
import org.springframework.security.saml.serviceprovider.filters.SamlAuthenticationRequestFilter;
import org.springframework.security.saml.serviceprovider.filters.SamlProcessingFilter;
import org.springframework.security.saml.serviceprovider.filters.SamlServiceProviderMetadataFilter;
import org.springframework.security.saml.serviceprovider.filters.SamlWebSsoAuthenticationFilter;
import org.springframework.security.saml.serviceprovider.filters.SelectIdentityProviderUIFilter;
import org.springframework.security.saml.serviceprovider.filters.ServiceProviderLogoutFilter;
import org.springframework.security.saml.serviceprovider.html.HtmlWriter;
import org.springframework.security.saml.serviceprovider.metadata.DefaultServiceProviderMetadataResolver;
import org.springframework.security.saml.serviceprovider.metadata.ServiceProviderMetadataResolver;
import org.springframework.security.saml.serviceprovider.validation.DefaultServiceProviderValidator;
import org.springframework.security.saml.serviceprovider.validation.ServiceProviderValidator;
import org.springframework.security.saml.spi.VelocityTemplateEngine;
import org.springframework.security.saml.util.StringUtils;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import static java.util.Optional.ofNullable;
import static org.springframework.util.Assert.notNull;

public class SamlServiceProviderConfigurer extends AbstractHttpConfigurer<SamlServiceProviderConfigurer, HttpSecurity> {

	public static SamlServiceProviderConfigurer saml2Login() {
		return new SamlServiceProviderConfigurer();
	}

	/*
	 * Fields with implementation defaults
	 */
	private ServiceProviderResolver providerResolver = null;
	private SamlTransformer samlTransformer = null;
	private ServiceProviderValidator samlValidator = null;
	private SamlTemplateEngine samlTemplateEngine = null;
	private AuthenticationManager authenticationManager = null;
	private ServiceProviderMetadataResolver metadataResolver = null;
	private ServiceProviderConfigurationResolver configurationResolver;
	private HtmlWriter htmlTemplateProcessor;
	private AuthenticationFailureHandler failureHandler;

	/*
	 * Filters for handling requests
	 */
	private Filter metadataFilter;
	private Filter selectIdentityProviderFilter;
	private Filter authenticationRequestFilter;
	private AbstractAuthenticationProcessingFilter authenticationFilter;
	private Filter logoutFilter;

	/*
	 * =========== Setters ============
	 */
	/**
	 * Sets the configuration resolver for the SAML filter chain
	 * @param resolver
	 * @return
	 */
	public SamlServiceProviderConfigurer configurationResolver(
		ServiceProviderConfigurationResolver resolver
	) {
		notNull(resolver, "configurationResolver must not be null");
		assertNull(providerResolver, "providerResolver", "configurationResolver");
		this.configurationResolver = resolver;
		return this;
	}

	/**
	 * Configures a service provider resolver. This is an alternative
	 * to configuring the
	 * @param resolver
	 * @return
	 */
	public SamlServiceProviderConfigurer providerResolver(ServiceProviderResolver resolver) {
		notNull(resolver, "providerResolver must not be null");
		assertNull(configurationResolver, "configurationResolver", "providerResolver");
		this.providerResolver = resolver;
		return this;
	}

	/*
	 * Configuration
	 */
	@Override
	public void init(HttpSecurity http) throws Exception {
		samlTransformer = getSharedObject(
			http,
			SamlTransformer.class,
			this::createDefaultSamlTransformer,
			samlTransformer
		);

		samlValidator = getSharedObject(
			http,
			ServiceProviderValidator.class,
			() -> new DefaultServiceProviderValidator(samlTransformer),
			samlValidator
		);

		samlTemplateEngine = getSharedObject(
			http,
			SamlTemplateEngine.class,
			() -> new VelocityTemplateEngine(true),
			samlTemplateEngine
		);

		metadataResolver = getSharedObject(
			http,
			ServiceProviderMetadataResolver.class,
			() -> new DefaultServiceProviderMetadataResolver(samlTransformer),
			metadataResolver
		);

		providerResolver = getSharedObject(
			http,
			ServiceProviderResolver.class,
			() -> null,
			providerResolver
		);

		if (ofNullable(providerResolver).isPresent()) {
			notNull(
				providerResolver.getConfiguredPathPrefix(),
				ServiceProviderResolver.class.getName() + ".getConfiguredPathPrefix() must not return null"
			);
		}
		else {
			//do we have a configurationResolver?
			configurationResolver = getSharedObject(
				http,
				ServiceProviderConfigurationResolver.class,
				null,
				configurationResolver
			);

			notNull(
				configurationResolver,
				ServiceProviderConfigurationResolver.class.getName() + " must not be null"
			);

			notNull(
				configurationResolver.getConfiguredPathPrefix(),
				ServiceProviderConfigurationResolver.class.getName() + ".getConfiguredPathPrefix() must not return null"
			);

			providerResolver = new DefaultServiceProviderResolver(metadataResolver, configurationResolver);
		}


		htmlTemplateProcessor = new HtmlWriter(samlTemplateEngine);
		failureHandler = new SamlAuthenticationFailureHandler(htmlTemplateProcessor);;

		String samlPattern = getPathPrefix() + "/**";
		registerDefaultAuthenticationEntryPoint(http, getPathPrefix());
		// @formatter:off
		http
			.csrf()
				.ignoringAntMatchers(samlPattern)
				.and()
			.authorizeRequests()
				.mvcMatchers(samlPattern)
				.permitAll()
		;
		// @formatter:on

	}

	@Override
	public void configure(HttpSecurity http) throws Exception {
		String pathPrefix = getPathPrefix();

		metadataFilter = getSharedObject(
			http,
			SamlServiceProviderMetadataFilter.class,
			() -> new SamlServiceProviderMetadataFilter(
				new AntPathRequestMatcher(pathPrefix + "/metadata/**"),
				samlTransformer
			),
			metadataFilter
		);

		selectIdentityProviderFilter = getSharedObject(
			http,
			SelectIdentityProviderUIFilter.class,
			() ->
				new SelectIdentityProviderUIFilter(
					pathPrefix,
					new AntPathRequestMatcher(pathPrefix + "/select/**"),
					htmlTemplateProcessor
				)
					.setRedirectOnSingleProvider(false),
			selectIdentityProviderFilter
		);

		authenticationRequestFilter = getSharedObject(
			http,
			SamlAuthenticationRequestFilter.class,
			() -> new SamlAuthenticationRequestFilter(
				new AntPathRequestMatcher(pathPrefix + "/discovery/**"),
				samlTransformer,
				htmlTemplateProcessor
			),
			authenticationRequestFilter
		);

		authenticationFilter = getSharedObject(
			http,
			SamlWebSsoAuthenticationFilter.class,
			() -> new SamlWebSsoAuthenticationFilter(
				new AntPathRequestMatcher(pathPrefix + "/SSO/**"),
				samlValidator
			),
			authenticationFilter
		);
		authenticationFilter.setAuthenticationManager(ofNullable(authenticationManager).orElseGet(() -> a -> a));
		authenticationFilter.setAuthenticationFailureHandler(failureHandler);

		logoutFilter = getSharedObject(
			http,
			ServiceProviderLogoutFilter.class,
			() -> {
				SimpleUrlLogoutSuccessHandler logoutSuccessHandler = new SimpleUrlLogoutSuccessHandler();
				logoutSuccessHandler.setDefaultTargetUrl(pathPrefix + "/select");
				return new ServiceProviderLogoutFilter(
					new AntPathRequestMatcher(pathPrefix + "/logout/**"),
					samlTransformer,
					samlValidator
				)
					.setLogoutSuccessHandler(logoutSuccessHandler);
			},
			logoutFilter
		);

		SamlProcessingFilter processingFilter = new SamlProcessingFilter(
			samlTransformer,
			providerResolver,
			samlValidator,
			new AntPathRequestMatcher(pathPrefix + "/**")
		);

		http.addFilterAfter(processingFilter, BasicAuthenticationFilter.class);
		http.addFilterAfter(metadataFilter, processingFilter.getClass());
		http.addFilterAfter(selectIdentityProviderFilter, metadataFilter.getClass());
		http.addFilterAfter(authenticationRequestFilter, selectIdentityProviderFilter.getClass());
		http.addFilterAfter(authenticationFilter, authenticationRequestFilter.getClass());
		http.addFilterAfter(logoutFilter, authenticationFilter.getClass());
	}

	private String getPathPrefix() {
		return "/" + StringUtils.stripSlashes(providerResolver.getConfiguredPathPrefix());
	}

	@SuppressWarnings("unchecked")
	private void registerDefaultAuthenticationEntryPoint(HttpSecurity http, String pathPrefix) {
		ExceptionHandlingConfigurer<HttpSecurity> exceptionHandling =
			http.getConfigurer(ExceptionHandlingConfigurer.class);

		if (exceptionHandling == null) {
			return;
		}

		String entryPointUrl = pathPrefix + "/select?redirect=true";
		LoginUrlAuthenticationEntryPoint authenticationEntryPoint =
			new LoginUrlAuthenticationEntryPoint(entryPointUrl) {
				@Override
				public void commence(HttpServletRequest request,
									 HttpServletResponse response,
									 AuthenticationException authException) throws IOException, ServletException {
					super.commence(request, response, authException);
				}
			};
		exceptionHandling.authenticationEntryPoint(authenticationEntryPoint);
	}


	private SamlTransformer createDefaultSamlTransformer() {
		try {
			return getClassInstance("org.springframework.security.saml.spi.opensaml.OpenSamlTransformer");
		} catch (SamlException e) {
			try {
				return getClassInstance("org.springframework.security.saml.spi.keycloak.KeycloakSamlTransformer");
			} catch (SamlException e2) {
				throw e;
			}
		}
	}

	private SamlTransformer getClassInstance(String className) {
		try {
			Class<?> clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
			return (SamlTransformer) clazz.newInstance();
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
			throw new SamlException(
				"Unable to instantiate the default SAML transformer. " +
					"Have you included the transform-opensaml or transform-keycloak dependency in your project?",
				e
			);
		}
	}

	private <C> C getSharedObject(HttpSecurity http, Class<C> clazz) {
		return http.getSharedObject(clazz);
	}

	private <C> void setSharedObject(HttpSecurity http, Class<C> clazz, C object) {
		if (http.getSharedObject(clazz) == null) {
			http.setSharedObject(clazz, object);
		}
	}

	private <C> C getSharedObject(HttpSecurity http,
								  Class<C> clazz,
								  Supplier<? extends C> creator,
								  Object existingInstance) {
		C result = ofNullable((C) existingInstance).orElseGet(() -> getSharedObject(http, clazz));
		if (result == null) {
			ApplicationContext context = getSharedObject(http, ApplicationContext.class);
			try {
				result = context.getBean(clazz);
			} catch (NoSuchBeanDefinitionException e) {
				if (creator != null) {
					result = creator.get();
				}
				else {
					return null;
				}
			}
		}
		setSharedObject(http, clazz, result);
		return result;
	}

	private void assertNull(Object configuredObject, String identifier, String alternate) {
		if (ofNullable(configuredObject).isPresent()) {
			throw new IllegalStateException(identifier +" should be null if you wish to configure a "+ alternate);
		}
	}

}
