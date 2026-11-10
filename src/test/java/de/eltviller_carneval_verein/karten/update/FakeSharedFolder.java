package de.eltviller_carneval_verein.karten.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Ein gemeinsamer Ordner für Tests (KARV-132): Pfad, Anwesenheit und Erreichbarkeit einstellbar. */
final class FakeSharedFolder implements SharedFolderAccess {

	volatile Path folder;
	volatile boolean reachable = true;
	volatile String machineId = "aaaa1111-0000-0000-0000-000000000001";
	volatile String machineName = "Kasse 1";
	final Set<String> ownTags = new HashSet<>();

	FakeSharedFolder(Path folder) {
		this.folder = folder;
	}

	@Override
	public Optional<Path> folder() {
		return Optional.ofNullable(folder);
	}

	@Override
	public boolean isPresent(Path f) {
		return Files.isDirectory(f);
	}

	@Override
	public boolean isReachable(Path f) {
		return reachable && Files.isDirectory(f);
	}

	@Override
	public String machineId() {
		return machineId;
	}

	@Override
	public String machineName() {
		return machineName;
	}

	@Override
	public boolean isOwnTempTag(String tag) {
		return tag.equals(machineId) || ownTags.contains(tag);
	}
}
