package de.eltviller_carneval_verein.karten.ui.settings;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.ui.shared.UpdateTexts;
import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.Plattform;
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
 * Der letzte Abschnitt der Einstellungen, „Info und Updates“ (KARV-131): die Version, der Stand der Update-Prüfung mit
 * „Jetzt prüfen“, „Jetzt aktualisieren“ und dem Ausweg „Update im Browser laden“ und unter Windows im Paket die Adresse
 * der Update-Datei zum Kopieren. Die Zeile „Updates“ folgt dem Zustand über einen {@link WeakChangeListener}; den
 * Listener selbst hält der Knoten, wie in {@link SharedFolderSection}.
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
	 * @param checkNow         „Jetzt prüfen“
	 * @param update           „Jetzt aktualisieren“ auf die bekannte Version
	 * @param updateViaBrowser „Update im Browser laden“
	 */
	public static SettingsSection build(Optional<AppVersion> version, Optional<Installation> inst,
			ReadOnlyObjectProperty<UpdateState> state, Runnable checkNow, Consumer<UpdateState.Available> update,
			Runnable updateViaBrowser) {
		List<SettingRow> rows = new ArrayList<>();
		Label versionLabel = new Label(version.map(AppVersion::display).orElse(UpdateTexts.DEVELOPMENT_VERSION));
		versionLabel.setId("update-version");
		rows.add(new SettingRow(UpdateTexts.ROW_VERSION, null, versionLabel));
		rows.add(new SettingRow(UpdateTexts.ROW_UPDATES, null,
				new UpdatesRow(version, inst, state, checkNow, update, updateViaBrowser).node));
		if (inst.map(i -> i.plattform() == Plattform.WINDOWS_PAKET).orElse(false)) {
			TextField file = new TextField(inst.get().appInstallerUri().toString());
			file.setId("update-file");
			file.setEditable(false);
			rows.add(new SettingRow(UpdateTexts.ROW_UPDATE_FILE, UpdateTexts.UPDATE_FILE_HINT, file));
		}
		return new SettingsSection(UpdateTexts.SECTION_TITLE, rows);
	}

	/** Die Zeile „Updates“: Stand, Hinweis zur Plattform und Knöpfe. */
	private static final class UpdatesRow {

		private final Optional<AppVersion> version;
		private final Optional<Installation> inst;
		private final ReadOnlyObjectProperty<UpdateState> state;
		private final Label status = new Label();
		private final Label platform = new Label();
		private final Button checkNowButton = new Button(UpdateTexts.BUTTON_CHECK_NOW);
		private final Button updateButton = new Button(UpdateTexts.BUTTON_UPDATE_NOW);
		private final Button browserButton = new Button(UpdateTexts.BUTTON_UPDATE_BROWSER);
		private final VBox node;
		private final ChangeListener<UpdateState> refresher = (obs, old, now) -> refresh();

		UpdatesRow(Optional<AppVersion> version, Optional<Installation> inst, ReadOnlyObjectProperty<UpdateState> state,
				Runnable checkNow, Consumer<UpdateState.Available> update, Runnable updateViaBrowser) {
			this.version = version;
			this.inst = inst;
			this.state = state;
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
			browserButton.setId("update-browser");
			browserButton.getStyleClass().add("button-default");
			browserButton.setOnAction(e -> updateViaBrowser.run());
			for (Button b : List.of(checkNowButton, updateButton, browserButton)) {
				b.setMinWidth(Region.USE_PREF_SIZE);
			}
			HBox buttons = new HBox(10, checkNowButton, updateButton, browserButton);
			buttons.setAlignment(Pos.CENTER_LEFT);
			node = new VBox(6, status, platform, buttons);
			node.getProperties().put(UpdatesRow.class, this);
			state.addListener(new WeakChangeListener<>(refresher));
			refresh();
		}

		private void refresh() {
			UpdateState s = state.get();
			boolean unsupported = s instanceof UpdateState.Unsupported || inst.isEmpty();
			status.setText(UpdateTexts.stateText(s, version, ZoneId.systemDefault()));
			Plattform plattform = inst.map(Installation::plattform).orElse(null);
			boolean windowsPackage = plattform == Plattform.WINDOWS_PAKET && !unsupported;
			Optional<UpdateState.Available> known = s.knownUpdate();
			boolean inPlace = known.isPresent() && inst.map(i -> i.canUpdateInPlace(known.get().neu())).orElse(false);
			String platformText = known.isPresent() && !inPlace && plattform != null
					? UpdateTexts.platformText(plattform, plattform == Plattform.WINDOWS_PAKET) : null;
			platform.setText(platformText);
			show(platform, platformText != null);
			show(checkNowButton, !unsupported);
			checkNowButton.setDisable(s instanceof UpdateState.Checking);
			show(updateButton, windowsPackage && inPlace);
			boolean browser = windowsPackage && !(s.effective() instanceof UpdateState.UpToDate)
					&& !(known.isPresent() && !inPlace);
			show(browserButton, browser);
		}

		private static void show(Node n, boolean shown) {
			n.setVisible(shown);
			n.setManaged(shown);
		}
	}
}
