package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackageFamilyTest {

	private static final String PUBLISHER = "CN=Kartenverwaltung - Eltviller Carneval Verein";

	@Test
	void knownPublisherId() {
		assertEquals("0cs1gtds5d9x0", PackageFamily.publisherId(PUBLISHER));
	}

	@Test
	void familyJoinsNameAndId() {
		assertEquals("EcvKartenFx132_0cs1gtds5d9x0", PackageFamily.of("EcvKartenFx132", PUBLISHER));
	}
}
