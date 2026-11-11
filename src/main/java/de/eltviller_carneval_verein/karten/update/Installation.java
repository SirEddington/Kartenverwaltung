package de.eltviller_carneval_verein.karten.update;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Die installierte App aus Sicht der Updates: Version, Update-Quelle ({@code app.repositoryUrl}), {@code app.fsname},
 * Plattform und Paketfamilie (KARV-131). Conveyor setzt die Werte beim Start als Systemeigenschaften; in Eclipse oder
 * mit {@code javafx:run} fehlen sie, dann gibt es keine Installation.
 *
 * @param base endet immer mit {@code /}, damit {@code resolve} die Dateien neben der Quelle findet
 */
public record Installation(AppVersion version, URI base, String fsname, Plattform plattform,
		Optional<String> packageFamilyName) {

	private static final Pattern GITHUB_LATEST = Pattern.compile("/[^/]+/[^/]+/releases/latest/download/");

	/**
	 * Liest die Installation aus Systemeigenschaften und Umgebung. Fehlt {@code app.version}, {@code app.repositoryUrl}
	 * oder {@code app.fsname} oder ist einer davon ungültig, ist das Ergebnis leer; eine Ausnahme gibt es nie.
	 */
	public static Optional<Installation> from(Function<String, String> props, Function<String, String> env) {
		Optional<AppVersion> version = AppVersion.from(props);
		String url = props.apply("app.repositoryUrl");
		String fsname = props.apply("app.fsname");
		if (version.isEmpty() || url == null || fsname == null || fsname.isBlank()) {
			return Optional.empty();
		}
		Optional<URI> base = base(url);
		if (base.isEmpty()) {
			return Optional.empty();
		}
		Optional<String> family = family(props.apply("app.windows.userModelID"));
		Plattform plattform = plattform(props.apply("os.name"), family, env.apply("PACKAGE_LOCALAPPDATA"));
		return Optional.of(new Installation(version.get(), base.get(), fsname, plattform,
				plattform == Plattform.WINDOWS_PAKET ? family : Optional.empty()));
	}

	/** Die Installation dieser laufenden App. */
	public static Optional<Installation> current() {
		return from(System::getProperty, System::getenv);
	}

	/** Die Adresse von {@code metadata.properties} der Update-Quelle. */
	public URI metadataUri() {
		return base.resolve("metadata.properties");
	}

	/** Die Adresse der {@code .appinstaller}-Datei der Update-Quelle. */
	public URI appInstallerUri() {
		return base.resolve(fsname + ".appinstaller");
	}

	/**
	 * Die Release-Seite von {@code v} auf GitHub (KARV-125): nur, wenn die Update-Quelle genau
	 * {@code https://github.com/<owner>/<repo>/releases/latest/download} ist (ohne Port, Userinfo, Query und Fragment);
	 * sonst leer, etwa bei der lokalen Testquelle. Die Revision zählt nicht, das Tag ist die Versionsnummer.
	 */
	public Optional<URI> releasePage(AppVersion v) {
		if (!"https".equalsIgnoreCase(base.getScheme()) || !"github.com".equalsIgnoreCase(base.getHost())
				|| base.getPort() != -1 || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
				|| !GITHUB_LATEST.matcher(base.getRawPath()).matches()) {
			return Optional.empty();
		}
		// …/releases/latest/download/ → …/releases/tag/<Version>, wie metadataUri über resolve (Kodierung bleibt erhalten)
		return Optional.of(base.resolve("../../tag/" + v.version()));
	}

	/**
	 * Ob der App Installer diese Installation auf {@code info} aktualisieren kann, statt ein zweites Paket zu installieren:
	 * nur im Paket und bei gleicher Paketfamilie. Nennt die Quelle keine Familie, entscheidet ihr Kennzeichen
	 * {@code internal.windows.package-family-name-changed}.
	 */
	public boolean canUpdateInPlace(UpdateInfo info) {
		if (plattform != Plattform.WINDOWS_PAKET || packageFamilyName.isEmpty()) {
			return false;
		}
		if (info.packageFamilyName() != null) {
			return info.packageFamilyName().equals(packageFamilyName.get());
		}
		return !info.familyChanged();
	}

	private static Optional<URI> base(String url) {
		try {
			URI uri = new URI(url.endsWith("/") ? url : url + "/");
			String scheme = uri.getScheme();
			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null) {
				return Optional.empty();
			}
			return Optional.of(uri);
		} catch (java.net.URISyntaxException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Der Teil von {@code app.windows.userModelID} vor dem {@code !}. */
	private static Optional<String> family(String userModelId) {
		if (userModelId == null) {
			return Optional.empty();
		}
		int bang = userModelId.indexOf('!');
		return bang > 0 ? Optional.of(userModelId.substring(0, bang)) : Optional.empty();
	}

	private static Plattform plattform(String osName, Optional<String> family, String packageLocalAppData) {
		String os = osName == null ? "" : osName;
		if (os.startsWith("Windows")) {
			boolean packaged = family.isPresent() && packageLocalAppData != null && packageLocalAppData
					.toLowerCase(Locale.ROOT).contains("\\packages\\" + family.get().toLowerCase(Locale.ROOT));
			return packaged ? Plattform.WINDOWS_PAKET : Plattform.WINDOWS_OHNE_PAKET;
		}
		return os.startsWith("Linux") ? Plattform.LINUX : Plattform.SONSTIGE;
	}
}
