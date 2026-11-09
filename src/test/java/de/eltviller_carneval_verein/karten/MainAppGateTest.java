package de.eltviller_carneval_verein.karten;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.repository.ContentRules;
import de.eltviller_carneval_verein.karten.store.SyncService;

/** Die App richtet vor dem ersten {@code SyncService.getInstance()} die Inhaltsregeln ein (ohne JavaFX-Start). */
class MainAppGateTest {

	@Test
	void appInstallsContentRules() {
		MainApp.installContentRules();

		assertSame(ContentRules.GATE, SyncService.installedContentGate());
	}
}
