package com.stucray.raptor.shape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one of Betfair's documents looks like, with every value that could be
 * Betfair's data taken out. It keeps the key paths and their JSON types, the key
 * sets that occur together on the objects a reader dispatches on, and the few
 * enumerated strings a reader branches on.
 *
 * <p>This is what ties a synthetic test sample to the real upstream (#303, #304).
 * Samples are generated because Betfair's data may not be committed. The rule a
 * sample must not break is PRD #95's, where fixtures and code came from the same
 * head and invented a shape the upstream never sends. So a shape is read off real
 * data held in custody, committed as a manifest, and every synthetic document must
 * stay inside it. A generator that invents a key, a type, a combination of keys or
 * an enumerated value fails the build.
 *
 * <p>Numbers are one type. The wire writes a whole-number price as {@code 5} and a
 * fractional one as {@code 4.8} within the same ladder, so int and float are the
 * same shape here.
 *
 * <p>Paths are dotted, with {@code []} for an array's elements and {@code $} for
 * the document root. Which paths carry key sets and which carry values is fixed
 * per upstream in a {@link Spec}, in code rather than in the manifest, so a
 * manifest cannot quietly widen what it checks.
 */
public record WireShape(
		Spec spec,
		SortedSet<String> paths,
		SortedMap<String, SortedSet<String>> keySets,
		SortedMap<String, SortedSet<String>> values) {

	/**
	 * Per upstream: the object paths whose key sets are part of the shape, and the
	 * string leaves whose values are.
	 */
	public record Spec(Set<String> keySetPaths, Set<String> enumerated) {

		/** One line of a stream capture: {@code mc} is a single market's change. */
		public static final Spec CAPTURE = new Spec(
				Set.of("mc", "mc.marketDefinition"),
				Set.of(
						"mc.marketDefinition.status",
						"mc.marketDefinition.marketType",
						"mc.marketDefinition.bettingType",
						"mc.marketDefinition.eventTypeId",
						"mc.marketDefinition.suspendReason",
						"mc.marketDefinition.timezone",
						"mc.marketDefinition.countryCode",
						"mc.marketDefinition.betDelayModels[]",
						"mc.marketDefinition.regulators[]",
						"mc.marketDefinition.priceLadderDefinition.type",
						"mc.marketDefinition.runners[].status"));

		/** One line of a historic BASIC file: {@code mc} is an array of changes. */
		public static final Spec BASIC = new Spec(
				Set.of("$", "mc[]", "mc[].marketDefinition", "mc[].marketDefinition.runners[]",
						"mc[].rc[]"),
				Set.of(
						"op",
						"mc[].marketDefinition.status",
						"mc[].marketDefinition.marketType",
						"mc[].marketDefinition.bettingType",
						"mc[].marketDefinition.eventTypeId",
						"mc[].marketDefinition.timezone",
						"mc[].marketDefinition.countryCode",
						"mc[].marketDefinition.regulators[]",
						"mc[].marketDefinition.runners[].status"));

		/** A response of Betfair's REST betting API: an array of nodes. */
		public static final Spec REST = new Spec(
				Set.of("[]", "[].competition", "[].description", "[].event", "[].runners[]"),
				Set.of(
						"[].status",
						"[].runners[].status",
						"[].description.marketType",
						"[].description.bettingType",
						"[].description.priceLadderDescription.type",
						"[].event.countryCode",
						"[].event.timezone",
						"[].betDelayModels[]"));
	}

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	public static WireShape empty(Spec spec) {
		return new WireShape(spec, new TreeSet<>(), new TreeMap<>(), new TreeMap<>());
	}

	public static WireShape of(Spec spec, Iterable<String> documents) {
		WireShape shape = empty(spec);
		for (String document : documents) {
			shape.add(MAPPER.readTree(document));
		}
		return shape;
	}

	public void add(JsonNode document) {
		walk("", document);
	}

	/** Everything in {@code other} as well as this; for reading a corpus in parallel. */
	public WireShape merge(WireShape other) {
		paths.addAll(other.paths);
		other.keySets.forEach((path, sets) ->
				keySets.computeIfAbsent(path, k -> new TreeSet<>()).addAll(sets));
		other.values.forEach((path, seen) ->
				values.computeIfAbsent(path, k -> new TreeSet<>()).addAll(seen));
		return this;
	}

	/**
	 * Every way this shape strays outside {@code reference}, one line each. Empty
	 * means it is contained.
	 */
	public List<String> outside(WireShape reference) {
		List<String> violations = new ArrayList<>();
		paths.stream().filter(p -> !reference.paths.contains(p))
				.forEach(p -> violations.add("path never seen on the wire: " + p));
		keySets.forEach((path, sets) -> {
			Set<String> allowed = reference.keySets.getOrDefault(path, new TreeSet<>());
			sets.stream().filter(k -> !allowed.contains(k))
					.forEach(k -> violations.add("key set never seen on the wire at " + path + ": " + k));
		});
		values.forEach((path, seen) -> {
			Set<String> allowed = reference.values.getOrDefault(path, new TreeSet<>());
			seen.stream().filter(v -> !allowed.contains(v))
					.forEach(v -> violations.add("value never seen on the wire: " + path + " = " + v));
		});
		return violations;
	}

	/**
	 * Deterministic: the manifest is compared as text, so the key order is fixed
	 * here rather than left to a {@code Map.of}, whose iteration order changes
	 * between JVM runs.
	 */
	public String toJson() {
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document()) + "\n";
	}

	/** The manifest's content, for a manifest that holds several shapes. */
	public Map<String, Object> document() {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("paths", paths);
		document.put("keySets", keySets);
		document.put("values", values);
		return document;
	}

	public static WireShape fromJson(Spec spec, String json) {
		return fromJson(spec, MAPPER.readTree(json));
	}

	public static WireShape fromJson(Spec spec, JsonNode root) {
		WireShape shape = empty(spec);
		root.get("paths").forEach(n -> shape.paths.add(n.asText()));
		root.get("keySets").properties().forEach(e -> {
			SortedSet<String> set = new TreeSet<>();
			e.getValue().forEach(n -> set.add(n.asText()));
			shape.keySets.put(e.getKey(), set);
		});
		root.get("values").properties().forEach(e -> {
			SortedSet<String> set = new TreeSet<>();
			e.getValue().forEach(n -> set.add(n.asText()));
			shape.values.put(e.getKey(), set);
		});
		return shape;
	}

	private void walk(String path, JsonNode node) {
		String at = path.isEmpty() ? "$" : path;
		if (node.isObject()) {
			record(at, "object");
			if (spec.keySetPaths().contains(at)) {
				keySets.computeIfAbsent(at, k -> new TreeSet<>())
						.add(String.join(",", new TreeSet<>(node.propertyNames())));
			}
			node.properties().forEach(e -> walk(join(path, e.getKey()), e.getValue()));
		} else if (node.isArray()) {
			record(at, "array");
			node.forEach(element -> walk(path + "[]", element));
		} else if (node.isString()) {
			record(at, "string");
			if (spec.enumerated().contains(at)) {
				values.computeIfAbsent(at, k -> new TreeSet<>()).add(node.asText());
			}
		} else if (node.isNumber()) {
			record(at, "number");
		} else if (node.isBoolean()) {
			record(at, "boolean");
		} else if (node.isNull()) {
			record(at, "null");
		} else {
			record(at, node.getNodeType().name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private void record(String path, String type) {
		paths.add(path + " : " + type);
	}

	private static String join(String path, String key) {
		return path.isEmpty() ? key : path + "." + key;
	}
}
