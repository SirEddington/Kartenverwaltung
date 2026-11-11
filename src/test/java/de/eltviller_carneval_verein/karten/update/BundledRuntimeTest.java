package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Die Laufzeit im Paket enthält nur die Module, die Conveyor erkennt bzw. {@code conveyor.conf} nennt. Über den
 * Dienstmechanismus geladene Teile fehlen dort, obwohl sie im vollen JDK der Tests da sind (Handtest KARV-132:
 * {@code RandomGenerator.getDefault()} braucht {@code jdk.random} und warf im Paket beim Start).
 */
class BundledRuntimeTest {

	@Test
	void mainCodeUsesNoRandomGeneratorFactory() throws IOException {
		List<String> hits;
		try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
			hits = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
				try {
					String text = Files.readString(p);
					return text.contains("RandomGenerator.getDefault(") || text.contains("RandomGenerator.of(")
							|| text.contains("RandomGeneratorFactory");
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}).map(Path::toString).toList();
		}
		assertEquals(List.of(), hits, "braucht das Modul jdk.random, das im Paket fehlt; java.util.Random nehmen");
	}
}
