package de.eltviller_carneval_verein.karten.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.mirror.MirrorService.StartupResult;

class MirrorServiceTest {

	@TempDir
	Path local;

	@TempDir
	Path target;

	@TempDir
	Path otherTarget;

	private final AtomicBoolean reachable = new AtomicBoolean(true);
	private final List<String> unreachableCalls = new ArrayList<>();
	private final List<String> errors = new ArrayList<>();
	private int reconnectedCalls;

	// Alles läuft synchron auf dem Testthread; die Erreichbarkeit steuert der Test
	private MirrorService service;

	@BeforeEach
	void setUp() {
		service = newService(Runnable::run, Runnable::run, path -> reachable.get());
	}

	private MirrorService newService(Executor ui, Executor worker, Predicate<Path> probe) {
		MirrorService created = new MirrorService(local, ui, worker, probe);
		created.setHandlers(unreachableCalls::add, () -> reconnectedCalls++, errors::add);
		return created;
	}

	private static void write(Path root, String rel, String text) throws IOException {
		Path file = root.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	private static String read(Path root, String rel) throws IOException {
		return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
	}

	private static boolean exists(Path root, String rel) {
		return Files.exists(root.resolve(rel));
	}

	@Test
	void startupWithoutMirrorDirIsOff() {
		assertEquals(StartupResult.OFF, service.startup(null));
		assertEquals(MirrorState.AUS, service.state().get());
		assertNull(service.target());

		assertEquals(StartupResult.OFF, service.startup("   "));
		assertEquals(MirrorState.AUS, service.state().get());
		assertNull(service.target());
	}

	@Test
	void startupReachableReconcilesAndEndsAktuell() throws IOException {
		write(local, "events_data/E1.json", "eins");
		write(local, "halls_data/H1.json", "halle");
		write(local, "events_data/backups/E1/b1.json", "sicherung");
		assertNull(service.lastSync().get());

		assertEquals(StartupResult.REACHABLE, service.startup(target.toString()));

		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertNotNull(service.lastSync().get());
		assertEquals(target.toString(), service.target());
		assertEquals("eins", read(target, "events_data/E1.json"));
		assertEquals("halle", read(target, "halls_data/H1.json"));
		assertEquals("sicherung", read(target, "events_data/backups/E1/b1.json"));
		assertTrue(unreachableCalls.isEmpty());
		assertTrue(errors.isEmpty());
		assertEquals(0, reconnectedCalls);
	}

	@Test
	void startupUnreachableReturnsUnreachableWithoutCallingTheHandler() throws IOException {
		write(local, "events_data/E1.json", "eins");
		reachable.set(false);

		assertEquals(StartupResult.UNREACHABLE, service.startup(target.toString()));

		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());
		assertEquals(target.toString(), service.target());
		assertTrue(unreachableCalls.isEmpty());
		assertFalse(exists(target, "events_data/E1.json"));
	}

	@Test
	void entitySavedPushesJsonAndBackups() throws IOException {
		service.startup(target.toString());

		write(local, "events_data/E1.json", "neu");
		write(local, "events_data/backups/E1/b1.json", "alt");
		service.entitySaved("events_data", "E1");

		assertEquals("neu", read(target, "events_data/E1.json"));
		assertEquals("alt", read(target, "events_data/backups/E1/b1.json"));
		assertEquals(MirrorState.AKTUELL, service.state().get());
	}

	@Test
	void entityDeletedRemovesTheFileInTheTarget() throws IOException {
		write(local, "events_data/E1.json", "eins");
		write(local, "events_data/E2.json", "zwei");
		service.startup(target.toString());
		assertTrue(exists(target, "events_data/E1.json"));

		Files.delete(local.resolve("events_data/E1.json"));
		write(local, "events_data/backups/E1/geloescht.json", "eins");
		service.entityDeleted("events_data", "E1");

		assertFalse(exists(target, "events_data/E1.json"));
		assertTrue(exists(target, "events_data/E2.json"));
		assertTrue(exists(target, "events_data/backups/E1/geloescht.json"));
		assertEquals(MirrorState.AKTUELL, service.state().get());
	}

	@Test
	void connectionLostDuringSessionNotifiesOnlyOncePerOutage() throws IOException {
		service.startup(target.toString());
		assertEquals(MirrorState.AKTUELL, service.state().get());

		reachable.set(false);
		write(local, "events_data/E1.json", "eins");
		service.entitySaved("events_data", "E1");
		write(local, "events_data/E2.json", "zwei");
		service.entitySaved("events_data", "E2");

		assertEquals(List.of(target.toString()), unreachableCalls);
		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());
		assertFalse(exists(target, "events_data/E1.json"));

		reachable.set(true);
		service.retryTick();
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals("eins", read(target, "events_data/E1.json"));
		assertEquals("zwei", read(target, "events_data/E2.json"));

		reachable.set(false);
		service.entitySaved("events_data", "E1");
		assertEquals(2, unreachableCalls.size());
		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());
	}

	@Test
	void retryTickAfterReconnectReconcilesAndReportsOnce() throws IOException {
		write(local, "events_data/E1.json", "eins");
		reachable.set(false);
		assertEquals(StartupResult.UNREACHABLE, service.startup(target.toString()));

		// Noch immer weg: nichts ändert sich, nichts wird gemeldet
		service.retryTick();
		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());
		assertEquals(0, reconnectedCalls);

		reachable.set(true);
		service.retryTick();

		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals(1, reconnectedCalls);
		assertEquals("eins", read(target, "events_data/E1.json"));
		assertNotNull(service.lastSync().get());
		assertTrue(unreachableCalls.isEmpty());

		// Im Zustand AKTUELL tut der Timer nichts mehr
		service.retryTick();
		assertEquals(1, reconnectedCalls);
	}

	@Test
	void deleteWhileUnreachableIsPropagatedAfterReconnect() throws IOException {
		write(local, "events_data/E1.json", "eins");
		write(local, "events_data/E2.json", "zwei");
		service.startup(target.toString());
		assertTrue(exists(target, "events_data/E1.json"));

		reachable.set(false);
		Files.delete(local.resolve("events_data/E1.json"));
		service.entityDeleted("events_data", "E1");
		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());
		assertTrue(exists(target, "events_data/E1.json"));

		reachable.set(true);
		service.retryTick();

		assertFalse(exists(target, "events_data/E1.json"));
		assertTrue(exists(target, "events_data/E2.json"));
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals(1, reconnectedCalls);
	}

	@Test
	void lockedTargetFileGivesFehlerAndSys003() throws IOException {
		write(local, "events_data/E1.json", "eins");
		write(local, "events_data/E2.json", "zwei");
		// Im Ziel steht an der Stelle der Datei ein nicht leerer Ordner: Er wird nie überschrieben
		write(target, "events_data/E1.json/inhalt.txt", "gehoert dem Nutzer");

		service.startup(target.toString());

		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).startsWith("SYS-003:"), errors.get(0));
		assertTrue(errors.get(0).contains(target.toString()), errors.get(0));
		assertTrue(exists(target, "events_data/E1.json/inhalt.txt"));

		// Eine andere Entität geht trotzdem durch; der Fehler bleibt bis zum nächsten erfolgreichen Abgleich stehen
		service.entitySaved("events_data", "E2");
		assertEquals("zwei", read(target, "events_data/E2.json"));
		assertEquals(MirrorState.FEHLER, service.state().get());

		// Auch ein Einzelfehler wirft nicht in den Aufrufer; dieselbe Störung wird nicht erneut gemeldet
		service.entitySaved("events_data", "E1");
		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(1, errors.size());

		// Ist das Hindernis weg, heilt der nächste Wiederholversuch
		Files.delete(target.resolve("events_data/E1.json/inhalt.txt"));
		service.retryTick();
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals("eins", read(target, "events_data/E1.json"));
	}

	@Test
	void abortGuardGivesSys004AndKeepsTarget() throws IOException {
		write(target, "events_data/E9.json", "kostbar");
		write(target, "halls_data/H9.json", "auch");

		service.startup(target.toString());

		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).startsWith("SYS-004:"), errors.get(0));
		assertEquals("kostbar", read(target, "events_data/E9.json"));
		assertEquals("auch", read(target, "halls_data/H9.json"));
	}

	@Test
	void repeatedIdenticalErrorIsReportedOnlyOnce() throws IOException {
		write(target, "events_data/E9.json", "kostbar");
		service.startup(target.toString());
		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(1, errors.size());

		List<MirrorState> changes = new ArrayList<>();
		service.state().addListener((observable, oldState, newState) -> changes.add(newState));
		service.retryTick();
		service.retryTick();
		service.retryTick();

		assertEquals(1, errors.size());
		assertEquals(MirrorState.FEHLER, service.state().get());
		assertTrue(changes.isEmpty(), "Zustandswechsel während der Wiederholversuche: " + changes);
		assertEquals("kostbar", read(target, "events_data/E9.json"));
	}

	@Test
	void changedErrorMessageIsReportedAgain() throws IOException {
		write(target, "events_data/E9.json", "kostbar");
		service.startup(target.toString());
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).startsWith("SYS-004:"), errors.get(0));

		// Die Ursache wechselt: lokal liegt jetzt etwas, im Ziel aber steht ein Ordner im Weg
		write(local, "events_data/E1.json", "eins");
		write(target, "events_data/E1.json/inhalt.txt", "gehoert dem Nutzer");
		service.retryTick();

		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(2, errors.size());
		assertTrue(errors.get(1).startsWith("SYS-003:"), errors.get(1));

		// Und der neue Fehler wird ebenfalls nur einmal gemeldet
		service.retryTick();
		assertEquals(2, errors.size());
	}

	@Test
	void errorIsReportedAgainAfterRecovery() throws IOException {
		write(target, "events_data/E9.json", "kostbar");
		service.startup(target.toString());
		assertEquals(1, errors.size());

		// Die Ursache verschwindet: Der Wiederholversuch heilt
		write(local, "events_data/E9.json", "kostbar");
		service.retryTick();
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals(1, errors.size());

		// Dieselbe Ursache tritt erneut auf: eine neue Störung, also wieder eine Meldung
		Files.delete(local.resolve("events_data/E9.json"));
		service.syncNow();
		assertEquals(MirrorState.FEHLER, service.state().get());
		assertEquals(2, errors.size());
		assertTrue(errors.get(1).startsWith("SYS-004:"), errors.get(1));
	}

	@Test
	void configureWithBlankTurnsOff() throws IOException {
		service.startup(target.toString());
		assertEquals(MirrorState.AKTUELL, service.state().get());

		service.configure("");
		assertEquals(MirrorState.AUS, service.state().get());
		assertNull(service.target());

		service.startup(target.toString());
		service.configure(null);
		assertEquals(MirrorState.AUS, service.state().get());
		assertNull(service.target());

		// Ausgeschaltet geht nichts mehr ins Ziel
		write(local, "events_data/E1.json", "eins");
		service.entitySaved("events_data", "E1");
		assertFalse(exists(target, "events_data/E1.json"));
	}

	@Test
	void configureNewTargetStartsAReconcile() throws IOException {
		write(local, "events_data/E1.json", "eins");
		assertEquals(MirrorState.AUS, service.state().get());

		service.configure(target.toString());
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals(target.toString(), service.target());
		assertEquals("eins", read(target, "events_data/E1.json"));

		service.configure(otherTarget.toString());
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals(otherTarget.toString(), service.target());
		assertEquals("eins", read(otherTarget, "events_data/E1.json"));
	}

	@Test
	void syncNowWhileOffDoesNothing() throws IOException {
		write(local, "events_data/E1.json", "eins");

		service.syncNow();

		assertEquals(MirrorState.AUS, service.state().get());
		assertNull(service.lastSync().get());
		assertFalse(exists(target, "events_data/E1.json"));
	}

	@Test
	void syncNowReconcilesWhileActive() throws IOException {
		service.startup(target.toString());
		// Von Hand verändert, ohne dass der Dienst davon erfuhr
		write(local, "events_data/E1.json", "eins");

		service.syncNow();

		assertEquals("eins", read(target, "events_data/E1.json"));
		assertEquals(MirrorState.AKTUELL, service.state().get());
	}

	@Test
	void neverCreatesTheBaseFolderAtRuntime() throws IOException {
		Path missing = target.resolve("fehlt-noch");
		// Wie die echte Prüfung: ein nicht vorhandener Ordner ist nicht erreichbar
		MirrorService strict = newService(Runnable::run, Runnable::run, Files::isDirectory);
		write(local, "events_data/E1.json", "eins");

		assertEquals(StartupResult.UNREACHABLE, strict.startup(missing.toString()));
		assertEquals(MirrorState.NICHT_ERREICHBAR, strict.state().get());

		strict.entitySaved("events_data", "E1");
		strict.syncNow();
		strict.retryTick();
		strict.configure(missing.toString());

		assertEquals(MirrorState.NICHT_ERREICHBAR, strict.state().get());
		assertFalse(Files.exists(missing));
	}

	@Test
	void queuedSavesOfTheSameEntityAreCoalesced() throws IOException {
		List<Runnable> pending = new ArrayList<>();
		MirrorService deferred = newService(Runnable::run, pending::add, path -> reachable.get());
		deferred.startup(target.toString());
		runAll(pending);

		write(local, "events_data/E1.json", "eins");
		deferred.entitySaved("events_data", "E1");
		deferred.entitySaved("events_data", "E1");
		deferred.entitySaved("events_data", "E2");
		deferred.entitySaved("events_data", "E1");

		// Ein einziger Arbeitsauftrag räumt die ganze Warteschlange ab
		assertEquals(1, pending.size());
		assertFalse(exists(target, "events_data/E1.json"));
		runAll(pending);
		assertEquals("eins", read(target, "events_data/E1.json"));
		assertFalse(exists(target, "events_data/E2.json"));
	}

	@Test
	void saveFollowedByDeleteKeepsTheLastOperation() throws IOException {
		write(local, "events_data/E1.json", "eins");
		write(local, "events_data/E2.json", "zwei");
		service.startup(target.toString());

		List<Runnable> pending = new ArrayList<>();
		MirrorService deferred = newService(Runnable::run, pending::add, path -> reachable.get());
		deferred.configure(target.toString());
		runAll(pending);

		write(local, "events_data/E1.json", "geaendert");
		deferred.entitySaved("events_data", "E1");
		Files.delete(local.resolve("events_data/E1.json"));
		deferred.entityDeleted("events_data", "E1");
		runAll(pending);

		assertFalse(exists(target, "events_data/E1.json"));
		assertEquals("zwei", read(target, "events_data/E2.json"));

		// Und umgekehrt: erst Löschen, dann wieder Speichern
		Files.delete(local.resolve("events_data/E2.json"));
		deferred.entityDeleted("events_data", "E2");
		write(local, "events_data/E2.json", "wieder da");
		deferred.entitySaved("events_data", "E2");
		runAll(pending);

		assertEquals("wieder da", read(target, "events_data/E2.json"));
	}

	@Test
	void stateAndHandlersArriveThroughTheUiExecutor() throws IOException {
		List<Runnable> uiQueue = new ArrayList<>();
		MirrorService viaUi = newService(uiQueue::add, Runnable::run, path -> reachable.get());
		List<MirrorState> seen = new ArrayList<>();
		viaUi.state().addListener((observable, oldState, newState) -> seen.add(newState));
		write(local, "events_data/E1.json", "eins");

		viaUi.startup(target.toString());
		reachable.set(false);
		viaUi.entitySaved("events_data", "E1");

		// Nichts erreicht die Oberfläche, solange deren Thread nicht dran ist
		assertEquals(MirrorState.AUS, viaUi.state().get());
		assertNull(viaUi.lastSync().get());
		assertTrue(seen.isEmpty());
		assertTrue(unreachableCalls.isEmpty());

		runAll(uiQueue);
		assertEquals(MirrorState.NICHT_ERREICHBAR, viaUi.state().get());
		assertNotNull(viaUi.lastSync().get());
		assertEquals(List.of(MirrorState.ABGLEICH, MirrorState.AKTUELL, MirrorState.NICHT_ERREICHBAR), seen);
		assertEquals(1, unreachableCalls.size());
	}

	@Test
	void throwingHandlerDoesNotBreakTheService() throws IOException {
		service.setHandlers(path -> {
			throw new IllegalStateException("Fehler im Rückruf");
		}, () -> {
			throw new IllegalStateException("Fehler im Rückruf");
		}, text -> {
			throw new IllegalStateException("Fehler im Rückruf");
		});
		service.startup(target.toString());
		reachable.set(false);
		write(local, "events_data/E1.json", "eins");

		service.entitySaved("events_data", "E1");
		assertEquals(MirrorState.NICHT_ERREICHBAR, service.state().get());

		reachable.set(true);
		service.retryTick();
		assertEquals(MirrorState.AKTUELL, service.state().get());
		assertEquals("eins", read(target, "events_data/E1.json"));
	}

	@Test
	void unreachableIsLoggedOncePerOutage() throws IOException {
		Logger logger = Logger.getLogger(MirrorService.class.getName());
		List<LogRecord> records = new ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				if (record.getMessage() != null && record.getMessage().startsWith(MirrorMessages.SYS_UNREACHABLE)) {
					records.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		logger.addHandler(handler);
		try {
			// Ausfall beim Start: einmal protokolliert, auch wenn der Wiederholversuch ihn erneut feststellt
			reachable.set(false);
			service.startup(target.toString());
			service.retryTick();
			assertEquals(1, records.size());

			// Rückkehr, dann ein Ausfall während der Sitzung: wieder genau einmal
			reachable.set(true);
			service.retryTick();
			assertEquals(MirrorState.AKTUELL, service.state().get());
			reachable.set(false);
			write(local, "events_data/E1.json", "eins");
			service.entitySaved("events_data", "E1");
			service.entitySaved("events_data", "E1");
			service.retryTick();
			assertEquals(2, records.size());

			for (LogRecord record : records) {
				assertEquals(Level.WARNING, record.getLevel());
				assertEquals(MirrorMessages.unreachable(target.toString()), record.getMessage());
				assertNull(record.getThrown());
			}
		} finally {
			logger.removeHandler(handler);
		}
	}

	private static void runAll(List<Runnable> queue) {
		while (!queue.isEmpty()) {
			queue.remove(0).run();
		}
	}
}
