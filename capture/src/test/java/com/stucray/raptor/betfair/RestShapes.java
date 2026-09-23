package com.stucray.raptor.betfair;

import com.stucray.raptor.shape.WireShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One {@link WireShape} per REST endpoint, read from a directory of responses.
 *
 * <p>Per endpoint rather than one shape for all of them, because the endpoints
 * answer with different nodes. A single shape would accept a catalogue node
 * carrying a book's keys, and that is the kind of invention this exists to catch.
 * A file belongs to the endpoint its name starts with, so a second capture of one
 * endpoint ({@code list-market-book-after-kickoff.json}) joins the first.
 */
final class RestShapes {

	static final List<String> ENDPOINTS =
			List.of("list-competitions", "list-market-catalogue", "list-market-book");

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private RestShapes() {}

	static Map<String, WireShape> of(Path dir) throws IOException {
		Map<String, WireShape> shapes = new LinkedHashMap<>();
		ENDPOINTS.forEach(e -> shapes.put(e, WireShape.empty(WireShape.Spec.REST)));
		List<Path> files;
		try (Stream<Path> list = Files.list(dir)) {
			files = list.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			String name = file.getFileName().toString();
			String endpoint = ENDPOINTS.stream().filter(name::startsWith).findFirst()
					.orElseThrow(() -> new IllegalStateException("no endpoint for " + file));
			shapes.get(endpoint).add(MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)));
		}
		return shapes;
	}

	static String toJson(Map<String, WireShape> shapes) {
		Map<String, Object> document = new LinkedHashMap<>();
		shapes.forEach((endpoint, shape) -> document.put(endpoint, shape.document()));
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
	}

	static Map<String, WireShape> fromJson(String json) {
		JsonNode root = MAPPER.readTree(json);
		Map<String, WireShape> shapes = new LinkedHashMap<>();
		ENDPOINTS.forEach(e -> shapes.put(e, WireShape.fromJson(WireShape.Spec.REST, root.get(e))));
		return shapes;
	}
}
