/*******************************************************************************
 * Copyright (c) 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.manifest.yaml;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Callable;

import org.springframework.ide.vscode.commons.util.IntegerRange;
import org.springframework.ide.vscode.commons.util.Renderable;
import org.springframework.ide.vscode.commons.util.Renderables;
import org.springframework.ide.vscode.commons.util.ValueParsers;
import org.springframework.ide.vscode.commons.yaml.schema.YType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.AbstractType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YAtomicType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YBeanType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YTypedPropertyImpl;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeUtil;
import org.springframework.ide.vscode.commons.yaml.schema.YValueHint;
import org.springframework.ide.vscode.commons.yaml.schema.YamlSchema;

import com.google.common.collect.ImmutableSet;

/**
 * @author Kris De Volder
 */
public class ManifestYmlSchema implements YamlSchema {

	private final AbstractType TOPLEVEL_TYPE;
	private final YTypeUtil TYPE_UTIL;
	private final Callable<Collection<YValueHint>> buildpackProvider;

	private static final Set<String> TOPLEVEL_EXCLUDED = ImmutableSet.of(
		"name", "host", "hosts"
	);

	@Override
	public IntegerRange expectedNumberOfDocuments() {
		return IntegerRange.exactly(1);
	}


	public ManifestYmlSchema(ManifestYmlHintProviders providers) {
		this.buildpackProvider = providers.getBuildpackProviders();
		Callable<Collection<YValueHint>> servicesProvider = providers.getServicesProvider();
		Callable<Collection<YValueHint>> domainsProvider = providers.getDomainsProvider();


		YTypeFactory f = new YTypeFactory();
		TYPE_UTIL = f.TYPE_UTIL;

		// define schema types
		TOPLEVEL_TYPE = f.ybean("Cloudfoundry Manifest");

		AbstractType application = f.ybean("Application");
		YAtomicType t_path = f.yatomic("Path");

		YAtomicType t_buildpack = f.yatomic("Buildpack");
		if (this.buildpackProvider != null) {
			t_buildpack.addHintProvider(this.buildpackProvider);
//			t_buildpack.parseWith(ManifestYmlValueParsers.fromHints(t_buildpack.toString(), buildpackProvider));
		}

		YAtomicType t_domain = f.yatomic("Domain");

		if (domainsProvider != null) {
			t_domain.addHintProvider(domainsProvider);
		}

		YAtomicType t_service = f.yatomic("Service");
		if (servicesProvider != null) {
			t_service.addHintProvider(servicesProvider);
			t_service.parseWith(new CFServicesValueParser(t_service.toString(),
					YTypeFactory.valuesFromHintProvider(servicesProvider)));
		}

		YAtomicType t_boolean = f.yenum("boolean", "true", "false");
		YAtomicType t_ne_string = f.yatomic("String");
		t_ne_string.parseWith(ValueParsers.NE_STRING);
		YType t_string = f.yatomic("String");
		YType t_strings = f.yseq(t_string);

		// "routes" has nested required property "route":
		// routes:
		// - route: someroute.io

		YBeanType route = f.ybean("Route");
		YAtomicType t_route_string = f.yatomic("route");
		route.addProperty(f.yprop("route", t_route_string).isRequired(true));
		t_route_string.parseWith(new RouteValueParser(YTypeFactory.valuesFromHintProvider(domainsProvider)));

		YAtomicType t_memory = f.yatomic("Memory");
		t_memory.addHints("256M", "512M", "1024M");
		t_memory.parseWith(ManifestYmlValueParsers.MEMORY);

		YAtomicType t_health_check_type = f.yenumBuilder("Health Check Type", "none", "process", "port", "http")
				.deprecateWithReplacement("none", "process")
				.build();

		YAtomicType t_strictly_pos_integer = f.yatomic("Strictly Positive Integer");
		t_strictly_pos_integer.parseWith(ManifestYmlValueParsers.integerAtLeast(1));

		YAtomicType t_pos_integer = f.yatomic("Positive Integer");
		t_pos_integer.parseWith(ManifestYmlValueParsers.POS_INTEGER);

		YType t_env = f.ymap(t_string, t_string);

		// define schema structure...
		TOPLEVEL_TYPE.addProperty(f.yprop("applications", f.yseq(application)));
		TOPLEVEL_TYPE.addProperty("inherit", t_string, descriptionFor("inherit"));

		YTypedPropertyImpl[] props = {
			f.yprop("buildpack", t_buildpack),
			f.yprop("command", t_string),
			f.yprop("disk_quota", t_memory),
			f.yprop("domain", t_domain),
			f.yprop("domains", f.yseq(t_domain)),
			f.yprop("env", t_env),
			f.yprop("host", t_string),
			f.yprop("hosts", t_strings),
			f.yprop("instances", t_strictly_pos_integer),
			f.yprop("memory", t_memory),
			f.yprop("name", t_ne_string).isRequired(true),
			f.yprop("no-hostname", t_boolean),
			f.yprop("no-route", t_boolean),
			f.yprop("path", t_path),
			f.yprop("random-route", t_boolean),
			f.yprop("routes", f.yseq(route)),
			f.yprop("services", f.yseq(t_service)),
			f.yprop("stack", t_string),
			f.yprop("timeout", t_pos_integer),
			f.yprop("health-check-type", t_health_check_type),
			f.yprop("health-check-http-endpoint", t_ne_string)
		};

		for (YTypedPropertyImpl prop : props) {
			prop.setDescriptionProvider(descriptionFor(prop));
			if (!TOPLEVEL_EXCLUDED.contains(prop.getName())) {
				TOPLEVEL_TYPE.addProperty(prop);
			}
			application.addProperty(prop);
		}
	}

	private Renderable descriptionFor(String propName) {
		return Renderables.fromClasspath(this.getClass(), "/description-by-prop-name/"+propName);
	}

	private Renderable descriptionFor(YTypedPropertyImpl prop) {
		return descriptionFor(prop.getName());
	}

	@Override
	public AbstractType getTopLevelType() {
		return TOPLEVEL_TYPE;
	}

	@Override
	public YTypeUtil getTypeUtil() {
		return TYPE_UTIL;
	}
}
