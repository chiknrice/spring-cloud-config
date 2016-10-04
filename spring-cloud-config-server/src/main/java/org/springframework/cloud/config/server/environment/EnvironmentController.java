/*
 * Copyright 2013-2015 the original author or authors.
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
package org.springframework.cloud.config.server.environment;

import static org.springframework.cloud.config.server.support.EnvironmentPropertySource.prepareEnvironment;
import static org.springframework.cloud.config.server.support.EnvironmentPropertySource.resolvePlaceholders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Dave Syer
 * @author Spencer Gibb
 * @author Roy Clarkson
 * @author Bartosz Wojtkiewicz
 * @author Rafal Zukowski
 * @author Ivan Corrales Solera
 * @author Daniel Frey
 * @author Ian Bondoc
 *
 */
@RestController
@RequestMapping(method = RequestMethod.GET, path = "${spring.cloud.config.server.prefix:}")
public class EnvironmentController {

	private EnvironmentRepository repository;
	private ObjectMapper objectMapper;

	private boolean stripDocument = true;

	public EnvironmentController(EnvironmentRepository repository) {
		this(repository, new ObjectMapper());
	}

	public EnvironmentController(EnvironmentRepository repository,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	/**
	 * Flag to indicate that YAML documents which are not a map should be stripped of the
	 * "document" prefix that is added by Spring (to facilitate conversion to Properties).
	 *
	 * @param stripDocument the flag to set
	 */
	public void setStripDocumentFromYaml(boolean stripDocument) {
		this.stripDocument = stripDocument;
	}

	@RequestMapping("/{name}/{profiles:.*[^-].*}")
	public Environment defaultLabel(@PathVariable String name,
			@PathVariable String profiles) {
		return labelled(name, profiles, null);
	}

	@RequestMapping("/{name}/{profiles}/{label:.*}")
	public Environment labelled(@PathVariable String name, @PathVariable String profiles,
			@PathVariable String label) {
		if (label != null && label.contains("(_)")) {
			// "(_)" is uncommon in a git branch name, but "/" cannot be matched
			// by Spring MVC
			label = label.replace("(_)", "/");
		}
		Environment environment = this.repository.findOne(name, profiles, label);
		return environment;
	}

	@RequestMapping("/{name}-{profiles}.properties")
	public ResponseEntity<String> properties(@PathVariable String name,
			@PathVariable String profiles,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws IOException {
		return labelledProperties(name, profiles, null, resolvePlaceholders);
	}

	@RequestMapping("/{label}/{name}-{profiles}.properties")
	public ResponseEntity<String> labelledProperties(@PathVariable String name,
			@PathVariable String profiles, @PathVariable String label,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws IOException {
		validateProfiles(profiles);
		Environment environment = labelled(name, profiles, label);
		Map<String, Object> properties = convertToProperties(environment);
		String propertiesString = getPropertiesString(properties);
		if (resolvePlaceholders) {
			propertiesString = resolvePlaceholders(prepareEnvironment(environment),
					propertiesString);
		}
		return getSuccess(propertiesString);
	}

	@RequestMapping("{name}-{profiles}.json")
	public ResponseEntity<String> jsonProperties(@PathVariable String name,
			@PathVariable String profiles,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws Exception {
		return labelledJsonProperties(name, profiles, null, resolvePlaceholders);
	}

	@RequestMapping("/{label}/{name}-{profiles}.json")
	public ResponseEntity<String> labelledJsonProperties(@PathVariable String name,
			@PathVariable String profiles, @PathVariable String label,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws Exception {
		validateProfiles(profiles);
		Environment environment = labelled(name, profiles, label);
		Map<String, Object> properties = convertToMap(environment);
		String json = this.objectMapper.writeValueAsString(properties);
		if (resolvePlaceholders) {
			json = resolvePlaceholders(prepareEnvironment(environment), json);
		}
		return getSuccess(json, MediaType.APPLICATION_JSON);
	}

	private String getPropertiesString(Map<String, Object> properties) {
		StringBuilder output = new StringBuilder();
		for (Entry<String, Object> entry : properties.entrySet()) {
			if (output.length() > 0) {
				output.append("\n");
			}
			String line = entry.getKey() + ": " + entry.getValue();
			output.append(line);
		}
		return output.toString();
	}

	@RequestMapping({ "/{name}-{profiles}.yml", "/{name}-{profiles}.yaml" })
	public ResponseEntity<String> yaml(@PathVariable String name,
			@PathVariable String profiles,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws Exception {
		return labelledYaml(name, profiles, null, resolvePlaceholders);
	}

	@RequestMapping({ "/{label}/{name}-{profiles}.yml",
			"/{label}/{name}-{profiles}.yaml" })
	public ResponseEntity<String> labelledYaml(@PathVariable String name,
			@PathVariable String profiles, @PathVariable String label,
			@RequestParam(defaultValue = "true") boolean resolvePlaceholders)
			throws Exception {
		validateProfiles(profiles);
		Environment environment = labelled(name, profiles, label);
		Map<String, Object> result = convertToMap(environment);
		if (this.stripDocument && result.size() == 1
				&& result.keySet().iterator().next().equals("document")) {
			Object value = result.get("document");
			if (value instanceof Collection) {
				return getSuccess(new Yaml().dumpAs(value, Tag.SEQ, FlowStyle.BLOCK));
			}
			else {
				return getSuccess(new Yaml().dumpAs(value, Tag.STR, FlowStyle.BLOCK));
			}
		}
		String yaml = new Yaml().dumpAsMap(result);

		if (resolvePlaceholders) {
			yaml = resolvePlaceholders(prepareEnvironment(environment), yaml);
		}

		return getSuccess(yaml);
	}

	/**
	 * Method {@code convertToMap} converts an {@code Environment} to a nested Map which represents a yml/json structure.
	 *
	 * @param input the environment to be converted
	 * @return the nested map containing the environment's properties
	 */
	private Map<String, Object> convertToMap(Environment input) {
		// First use the current convertToProperties to get a flat Map from the environment
		Map<String, Object> properties = convertToProperties(input);

		// A regex pattern to be used for identifying key of arrays (e.g. some-key[0])
		// and to capture the key portion and the array index portion
		Pattern arrayPattern = Pattern.compile("(.*)\\[(\\d+)]");

		// The root map which holds all the first level properties
		Map<String, Object> rootMap = new LinkedHashMap<>();

		// For each property
		for (Map.Entry<String, Object> property : properties.entrySet()) {
			String propertyKey = property.getKey();
			Object propertyValue = property.getValue();
			// Break down the property key at dot (.) delimiter
			String[] nestedKeys = propertyKey.split("\\.");
			Map<String, Object> currentMap = rootMap;
			// For each level of property key
			for (int i = 0; i < nestedKeys.length; i++) {
				Matcher arrayMatcher = arrayPattern.matcher(nestedKeys[i]);
				// The arrayIndex would tell later on if the current nestedKey is an array or not (-1 is not an array)
				int arrayIndex = -1;
				String nestedKey;
				// Identify the actual nestedKey (and arrayIndex if necessary)
				if (arrayMatcher.matches()) {
					nestedKey = arrayMatcher.group(1);
					arrayIndex = Integer.parseInt(arrayMatcher.group(2));
				} else {
					nestedKey = nestedKeys[i];
				}
				// Identify if the current nestedKey is for the leaf (the key which contains the property value)
				boolean isLeaf = i + 1 == nestedKeys.length;
				if (arrayIndex > -1) {
					// If currently nestedKey is an array, get the array
					@SuppressWarnings("unchecked")
					ArrayList<Object> array = (ArrayList<Object>) currentMap.get(nestedKey);
					// Or create if necessary
					if (array == null) {
						array = new ArrayList<>();
						currentMap.put(nestedKey, array);
					}
					// Fill array if necessary (in the case the property keys are not naturally sorted)
					while (array.size() <= arrayIndex) {
						array.add(null);
					}
					// Either set the value if nestedKey is leaf or create a sub-map as an entry in the array
					if (isLeaf) {
						array.set(arrayIndex, propertyValue);
					} else {
						@SuppressWarnings("unchecked")
						Map<String, Object> childMap = (Map<String, Object>) array.get(arrayIndex);
						if (childMap == null) {
							childMap = new LinkedHashMap<>();
							array.set(arrayIndex, childMap);
						}
						currentMap = childMap;
					}
				} else if (isLeaf) {
					// If current nestedKey is leaf, just set the value
					currentMap.put(nestedKey, propertyValue);
				} else {
					// Else, the child element is always a nested Map
					@SuppressWarnings("unchecked")
					Map<String, Object> childMap = (Map<String, Object>) currentMap.get(nestedKey);
					// Create if necessary
					if (childMap == null) {
						childMap = new LinkedHashMap<>();
						currentMap.put(nestedKey, childMap);
					}
					currentMap = childMap;
				}
			}
		}
		return rootMap;
	}

	@ExceptionHandler(NoSuchLabelException.class)
	public void noSuchLabel(HttpServletResponse response) throws IOException {
		response.sendError(HttpStatus.NOT_FOUND.value());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public void illegalArgument(HttpServletResponse response) throws IOException {
		response.sendError(HttpStatus.BAD_REQUEST.value());
	}

	private void validateProfiles(String profiles) {
		if (profiles.contains("-")) {
			throw new IllegalArgumentException(
					"Properties output not supported for name or profiles containing hyphens");
		}
	}

	private HttpHeaders getHttpHeaders(MediaType mediaType) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setContentType(mediaType);
		return httpHeaders;
	}

	private ResponseEntity<String> getSuccess(String body) {
		return new ResponseEntity<>(body, getHttpHeaders(MediaType.TEXT_PLAIN),
				HttpStatus.OK);
	}

	private ResponseEntity<String> getSuccess(String body, MediaType mediaType) {
		return new ResponseEntity<>(body, getHttpHeaders(mediaType), HttpStatus.OK);
	}

	private Map<String, Object> convertToProperties(Environment profiles) {

		// Map of unique keys containing full map of properties for each unique
		// key
		Map<String, Map<String, Object>> map = new LinkedHashMap<>();
		List<PropertySource> sources = new ArrayList<>(profiles.getPropertySources());
		Collections.reverse(sources);
		Map<String, Object> combinedMap = new TreeMap<>();
		for (PropertySource source : sources) {

			@SuppressWarnings("unchecked")
			Map<String, Object> value = (Map<String, Object>) source.getSource();
			for (String key : value.keySet()) {

				if (!key.contains("[")) {

					// Not an array, add unique key to the map
					combinedMap.put(key, value.get(key));

				}
				else {

					// An existing array might have already been added to the property map
					// of an unequal size to the current array. Replace the array key in
					// the current map.
					key = key.substring(0, key.indexOf("["));
					Map<String, Object> filtered = new TreeMap<>();
					for (String index : value.keySet()) {
						if (index.startsWith(key + "[")) {
							filtered.put(index, value.get(index));
						}
					}
					map.put(key, filtered);
				}
			}

		}

		// Combine all unique keys for array values into the combined map
		for (Entry<String, Map<String, Object>> entry : map.entrySet()) {
			combinedMap.putAll(entry.getValue());
		}

		postProcessProperties(combinedMap);
		return combinedMap;
	}

	private void postProcessProperties(Map<String, Object> propertiesMap) {
		for (Iterator<String> iter = propertiesMap.keySet().iterator(); iter.hasNext();) {
			String key = iter.next();
			if (key.equals("spring.profiles")) {
				iter.remove();
			}
		}
	}

}
