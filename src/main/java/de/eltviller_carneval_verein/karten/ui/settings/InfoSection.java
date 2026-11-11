package de.eltviller_carneval_verein.karten.ui.settings;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.ui.shared.UpdateTexts;
import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.FolderOffer;
import de.eltviller_carneval_verein.karten.update.FolderState;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.Plattform;
import de.eltviller_carneval_verein.karten.update.StationEntry;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Der letzte Abschnitt der Einstellungen, „Info und Updates“ (KARV-131/132): die Version, der Stand der Update-Prüfung
 * mit „Jetzt prüfen“, „Jetzt aktualisieren“ und dem Ausweg „Update im Browser laden“, darunter der Stand des gemeinsamen
 * Ordners (Update im Ordner, Ladestand, zurückgezogene Version, neuere Kasse mit nächstem Schritt, Liste der Kassen) und
 * unter Windows im Paket die Adresse der Update-Datei zum Kopieren. Die Zeile „Updates“ folgt beiden Zuständen über
 * {@link WeakChangeListener}; die Listener selbst hält der Knoten, wie in {@link SharedFolderSection}.
 */
public final class InfoSection {

	private InfoSection() {
	}

	/**
	 * Baut den Abschnitt; die Knoten gehören zu genau einem Aufbau des Bildschirms.
	 *
	 * @param version          die installierte Version, leer in der Entwicklung
	 * @param inst             die Installation, leer ohne Update-Prüfung
	 * @param state            der Zustand der Prüfung
	 * @param folder           der Stand des gemeinsamen Ordners
	 * @param ownMachineId     die Rechner-ID dieser Kasse (Markierung in der Liste)
	 * @param checkNow         „Jetzt prüfen“
	 * @param update           „Jetzt aktualisieren“ auf die bekannte Version
	 * @param updateFromFolder „Jetzt aktualisieren“ aus dem gemeinsamen Ordner
	 * @param updateViaBrowser „Update im Browser laden“
	 */
	public static SettingsSection build(Optional<AppVersion> version, Optional<Installation> inst,
			ReadOnlyObjectProperty<UpdateState> state, ReadOnlyObjectProperty<FolderState> folder,
			Supplier<String> ownMachineId, Runnable checkNow, Consumer<UpdateState.Available> update,
			Consumer<FolderOffer> updateFromFolder, Runnable updateViaBrowser) {
		List<SettingRow> rows = new ArrayList<>();
		Label versionLabel = new Label(version.map(AppVersion::display).orElse(UpdateTexts.DEVELOPMENT_VERSION));
		versionLabel.setId("update-version");
		rows.add(new SettingRow(UpdateTexts.ROW_VERSION, null, versionLabel));
		rows.add(new SettingRow(UpdateTexts.ROW_UPDATES, null, new UpdatesRow(version, inst, state, folder, ownMachineId,
				checkNow, update, updateFromFolder, updateViaBrowser).node));
		if (inst.map(i -> i.plattform() == Plattform.WINDOWS_PAKET).orElse(false)) {
			TextField file = new TextField(inst.get().appInstallerUri().toString());
			file.setId("update-file");
			file.setEditable(false);
			rows.add(new SettingRow(UpdateTexts.ROW_UPDATE_FILE, UpdateTexts.UPDATE_FILE_HINT, file));
		}
		return new SettingsSection(UpdateTexts.SECTION_TITLE, rows);
	}

	/** Die Zeile „Updates“: Stand, Hinweis zur Plattform, Knöpfe und der gemeinsame Ordner. */
	private static final class UpdatesRow {

		private final Optional<AppVersion> version;
		private final Optional<Installation> inst;
		private final ReadOnlyObjectProperty<UpdateState> state;
		private final ReadOnlyObjectProperty<FolderState> folder;
		// einmal beim Aufbau gelesen: die ID ändert sich praktisch nie, und so nimmt das Auffrischen keine Sperre
		private final String ownMachineId;
		private final Label status = new Label();
		private final Label platform = new Label();
		private final Button checkNowButton = new Button(UpdateTexts.BUTTON_CHECK_NOW);
		private final Button updateButton = new Button(UpdateTexts.BUTTON_UPDATE_NOW);
		private final Button folderButton = new Button(UpdateTexts.BUTTON_UPDATE_NOW);
		private final Button browserButton = new Button(UpdateTexts.BUTTON_UPDATE_BROWSER);
		private final Label folderStatus = new Label();
		private final Label stationsTitle = new Label(UpdateTexts.STATIONS_TITLE);
		private final Label stations = new Label();
		private final VBox node;
		private final ChangeListener<UpdateState> refresher = (obs, old, now) -> refresh();
		private final ChangeListener<FolderState> folderRefresher = (obs, old, now) -> refresh();

		UpdatesRow(Optional<AppVersion> version, Optional<Installation> inst, ReadOnlyObjectProperty<UpdateState> state,
				ReadOnlyObjectProperty<FolderState> folder, Supplier<String> ownMachineId, Runnable checkNow,
				Consumer<UpdateState.Available> update, Consumer<FolderOffer> updateFromFolder, Runnable updateViaBrowser) {
			this.version = version;
			this.inst = inst;
			this.state = state;
			this.folder = folder;
			this.ownMachineId = ownMachineId.get();
			status.setId("update-status");
			status.setWrapText(true);
			platform.setId("update-platform");
			platform.setWrapText(true);
			checkNowButton.setId("update-check-now");
			checkNowButton.getStyleClass().add("button-default");
			checkNowButton.setOnAction(e -> checkNow.run());
			updateButton.setId("update-now");
			updateButton.getStyleClass().add("button-primary");
			updateButton.setOnAction(e -> state.get().knownUpdate().ifPresent(update));
			folderButton.setId("folder-update");
			folderButton.getStyleClass().add("button-primary");
			folderButton.setOnAction(e -> folder.get().offer().ifPresent(updateFromFolder));
			browserButton.setId("update-browser");
			browserButton.getStyleClass().add("button-default");
			browserButton.setOnAction(e -> updateViaBrowser.run());
			for (Button b : List.of(checkNowButton, updateButton, folderButton, browserButton)) {
				b.setMinWidth(Region.USE_PREF_SIZE);
			}
			folderStatus.setId("folder-status");
			folderStatus.setWrapText(true);
			stationsTitle.setId("folder-stations-title");
			stationsTitle.getStyleClass().add("settings-label");
			stations.setId("folder-stations");
			stations.setWrapText(true);
			HBox buttons = new HBox(10, checkNowButton, updateButton, folderButton, browserButton);
			buttons.setAlignment(Pos.CENTER_LEFT);
			node = new VBox(6, status, platform, folderStatus, buttons, stationsTitle, stations);
			node.getProperties().put(UpdatesRow.class, this);
			state.addListener(new WeakChangeListener<>(refresher));
			folder.addListener(new WeakChangeListener<>(folderRefresher));
			refresh();
		}

		private void refresh() {
			UpdateState s = state.get();
			FolderState f = folder.get();
			boolean unsupported = s instanceof UpdateState.Unsupported || inst.isEmpty();
			status.setText(UpdateTexts.stateText(s, version, ZoneId.systemDefault()));
			Plattform plattform = inst.map(Installation::plattform).orElse(null);
			boolean windowsPackage = plattform == Plattform.WINDOWS_PAKET && !unsupported;
			Optional<UpdateState.Available> known = s.knownUpdate();
			boolean inPlace = known.isPresent() && inst.map(i -> i.canUpdateInPlace(known.get().neu())).orElse(false);
			boolean reinstall = known.isPresent() && !inPlace || f.reinstallNeeded().isPresent();
			String platformText = reinstall && plattform != null
					? UpdateTexts.platformText(plattform, plattform == Plattform.WINDOWS_PAKET) : null;
			platform.setText(platformText);
			show(platform, platformText != null);
			show(checkNowButton, !unsupported);
			checkNowButton.setDisable(s instanceof UpdateState.Checking);
			boolean offer = f.offer().isPresent();
			show(updateButton, windowsPackage && inPlace && !offer);
			show(folderButton, offer);
			boolean browser = windowsPackage && !(s.effective() instanceof UpdateState.UpToDate)
					&& !(known.isPresent() && !inPlace) && !offer;
			show(browserButton, browser);

			String folderText = folderText(f, plattform, inPlace);
			folderStatus.setText(folderText);
			show(folderStatus, folderText != null);
			String own = ownMachineId;
			List<String> lines = f.stations().stream()
					.map(e -> UpdateTexts.stationLine(e, e.machineId().equals(own), ZoneId.systemDefault())).toList();
			stations.setText(String.join("\n", lines));
			show(stationsTitle, !lines.isEmpty());
			show(stations, !lines.isEmpty());
		}

		/** Der Stand des gemeinsamen Ordners, {@code null} wenn es nichts zu sagen gibt. */
		private static String folderText(FolderState f, Plattform plattform, boolean githubInPlace) {
			List<String> parts = new ArrayList<>();
			f.loading().ifPresent(v -> parts.add(UpdateTexts.LOADING));
			f.offer().ifPresent(o -> parts.add(UpdateTexts.folderIndicatorText(o.version())));
			f.withdrawn().ifPresent(v -> parts.add(UpdateTexts.withdrawnText(v)));
			if (f.newerStation().isPresent()) {
				StationEntry e = f.newerStation().get();
				String text = UpdateTexts.newerStationText(e.name(), e.version());
				if (f.offer().isEmpty() && !githubInPlace && f.loading().isEmpty()) {
					text += ". " + (plattform == Plattform.WINDOWS_PAKET ? UpdateTexts.NEXT_STEP_PACKAGE
							: UpdateTexts.NEXT_STEP_MANUAL);
				}
				parts.add(text);
			}
			return parts.isEmpty() ? null : String.join("\n", parts);
		}

		private static void show(Node n, boolean shown) {
			n.setVisible(shown);
			n.setManaged(shown);
		}
	}
}
