/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.security.config.annotation.web.configurers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.servlet.Filter;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.saml2.serviceprovider.authentication.OpenSamlAuthenticationProvider;
import org.springframework.security.saml2.serviceprovider.authentication.OpenSamlAuthenticationRequestResolver;
import org.springframework.security.saml2.serviceprovider.authentication.Saml2AuthenticationRequestResolver;
import org.springframework.security.saml2.serviceprovider.provider.Saml2RelyingPartyRegistration;
import org.springframework.security.saml2.serviceprovider.provider.Saml2RelyingPartyRepository;
import org.springframework.security.saml2.serviceprovider.servlet.filter.RelyingPartyAliasUrlRequestMatcher;
import org.springframework.security.saml2.serviceprovider.servlet.filter.Saml2LoginPageGeneratingFilter;
import org.springframework.security.saml2.serviceprovider.servlet.filter.Saml2RequestMatcher;
import org.springframework.security.saml2.serviceprovider.servlet.filter.Saml2WebSsoAuthenticationFilter;
import org.springframework.security.saml2.serviceprovider.servlet.filter.Saml2WebSsoAuthenticationRequestFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.header.HeaderWriterFilter;

import static java.util.Optional.ofNullable;

public class Saml2ServiceProviderConfigurer
		extends AbstractHttpConfigurer<Saml2ServiceProviderConfigurer, HttpSecurity> {

	private static final String PREFIX = "/saml/sp";

	public static Saml2ServiceProviderConfigurer saml2Login() {
		return saml2Login(PREFIX);
	}

	public static Saml2ServiceProviderConfigurer saml2Login(String filterPrefix) {
		return new Saml2ServiceProviderConfigurer(filterPrefix);
	}



	static {
		java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
	}


	private AuthenticationProvider authenticationProvider;
	private Saml2RelyingPartyRepository providerDetailsRepository;
	private AuthenticationEntryPoint entryPoint = null;
	private Saml2AuthenticationRequestResolver authenticationRequestResolver;

	private final String filterPrefix;

	protected  Saml2ServiceProviderConfigurer(String filterPrefix) {
		this.filterPrefix = filterPrefix;
	}

	public Saml2ServiceProviderConfigurer authenticationProvider(AuthenticationProvider provider) {
		this.authenticationProvider = provider;
		return this;
	}

	public Saml2ServiceProviderConfigurer authenticationRequestResolver(Saml2AuthenticationRequestResolver resolver) {
		this.authenticationRequestResolver = resolver;
		return this;
	}

	public Saml2ServiceProviderConfigurer identityProviderRepository(Saml2RelyingPartyRepository repo) {
		this.providerDetailsRepository = repo;
		return this;
	}

	public Saml2ServiceProviderConfigurer authenticationEntryPoint(AuthenticationEntryPoint ep) {
		this.entryPoint = ep;
		return this;
	}

	@Override
	public void init(HttpSecurity builder) throws Exception {
		super.init(builder);
		builder.authorizeRequests().mvcMatchers(filterPrefix + "/**").permitAll().anyRequest().authenticated();
		builder.csrf().ignoringAntMatchers(filterPrefix + "/**");

		providerDetailsRepository = getSharedObject(
			builder,
			Saml2RelyingPartyRepository.class,
			() -> providerDetailsRepository,
			providerDetailsRepository
		);

		if (authenticationProvider == null) {
			authenticationProvider = new OpenSamlAuthenticationProvider();
		}
		builder.authenticationProvider(postProcess(authenticationProvider));

		if (entryPoint != null) {
			registerDefaultAuthenticationEntryPoint(builder, entryPoint);
		} else {
			final Map<String, String> providerUrlMap =
				getIdentityProviderUrlMap(filterPrefix + "/authenticate/", providerDetailsRepository);

			String loginUrl = (providerUrlMap.size() != 1) ?
				"/login" :
				providerUrlMap.entrySet().iterator().next().getValue();
			registerDefaultAuthenticationEntryPoint(builder, new LoginUrlAuthenticationEntryPoint(loginUrl));
		}

		authenticationRequestResolver = getSharedObject(builder,
			Saml2AuthenticationRequestResolver.class,
			() -> new OpenSamlAuthenticationRequestResolver(),
			authenticationRequestResolver
		);
	}

	@Override
	public void configure(HttpSecurity builder) throws Exception {
		String authNRequestUrlPrefix = filterPrefix + "/authenticate/";
		configureSaml2LoginPageFilter(builder, authNRequestUrlPrefix, "/login");

		String authNRequestUriMatcher = authNRequestUrlPrefix + "**";
		String authNAliasExtractor = authNRequestUrlPrefix + "{alias}/**";
		configureSaml2AuthenticationRequestFilter(
			builder,
			new RelyingPartyAliasUrlRequestMatcher(authNRequestUriMatcher, authNAliasExtractor)
		);

		String ssoUriPrefix = filterPrefix + "/SSO/";
		String ssoUriMatcher = ssoUriPrefix + "**";
		String ssoAliasExtractor = ssoUriPrefix + "{alias}/**";
		configureSaml2WebSsoAuthenticationFilter(
			builder,
			new RelyingPartyAliasUrlRequestMatcher(ssoUriMatcher, ssoAliasExtractor)
		);

	}

	protected void configureSaml2AuthenticationRequestFilter(HttpSecurity builder, Saml2RequestMatcher matcher) {
		Filter authenticationRequestFilter = new Saml2WebSsoAuthenticationRequestFilter(
			matcher,
			"{baseUrl}" + filterPrefix + "/SSO/{alias}",
			providerDetailsRepository,
			authenticationRequestResolver
		);
		builder.addFilterAfter(authenticationRequestFilter, HeaderWriterFilter.class);
	}

	protected void configureSaml2LoginPageFilter(HttpSecurity builder,
												 String authRequestPrefixUrl,
												 String loginFilterUrl) {
		Saml2RelyingPartyRepository idpRepo = providerDetailsRepository;
		Map<String, String> idps = getIdentityProviderUrlMap(authRequestPrefixUrl, idpRepo);
		Filter loginPageFilter =  new Saml2LoginPageGeneratingFilter(loginFilterUrl, idps);
		builder.addFilterAfter(loginPageFilter, HeaderWriterFilter.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> getIdentityProviderUrlMap(String authRequestPrefixUrl,
														  Saml2RelyingPartyRepository idpRepo) {
		Map<String,String> idps = new LinkedHashMap<>();
		if (idpRepo instanceof Iterable) {
			Iterable<Saml2RelyingPartyRegistration> repo = (Iterable<Saml2RelyingPartyRegistration>) idpRepo;
			repo.forEach(
				p -> idps.put(p.getAlias(), authRequestPrefixUrl +p.getAlias())
			);
		}
		return idps;
	}

	protected void configureSaml2WebSsoAuthenticationFilter(HttpSecurity builder,
															Saml2RequestMatcher matcher) {
		AuthenticationFailureHandler failureHandler =
			new SimpleUrlAuthenticationFailureHandler("/login?error=saml2-error");
		Saml2WebSsoAuthenticationFilter webSsoFilter =
			new Saml2WebSsoAuthenticationFilter(matcher, providerDetailsRepository);
		webSsoFilter.setAuthenticationFailureHandler(failureHandler);
		webSsoFilter.setAuthenticationManager(builder.getSharedObject(AuthenticationManager.class));
		builder.addFilterAfter(webSsoFilter, HeaderWriterFilter.class);
	}


	private <C> C getSharedObject(HttpSecurity http, Class<C> clazz) {
		return http.getSharedObject(clazz);
	}

	private <C> void setSharedObject(HttpSecurity http, Class<C> clazz, C object) {
		if (http.getSharedObject(clazz) == null) {
			http.setSharedObject(clazz, object);
		}
	}

	private <C> C getSharedObject(HttpSecurity http, Class<C> clazz, Supplier<? extends C> creator,
			Object existingInstance) {
		C result = ofNullable((C) existingInstance).orElseGet(() -> getSharedObject(http, clazz));
		if (result == null) {
			ApplicationContext context = getSharedObject(http, ApplicationContext.class);
			try {
				result = context.getBean(clazz);
			}
			catch (NoSuchBeanDefinitionException e) {
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

	@SuppressWarnings("unchecked")
	private void registerDefaultAuthenticationEntryPoint(HttpSecurity http, AuthenticationEntryPoint entryPoint) {
		ExceptionHandlingConfigurer<HttpSecurity> exceptionHandling =
			http.getConfigurer(ExceptionHandlingConfigurer.class);

		if (exceptionHandling == null) {
			return;
		}

		exceptionHandling.authenticationEntryPoint(entryPoint);
	}

}
