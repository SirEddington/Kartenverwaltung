package de.eltviller_carneval_verein.karten.store;

import java.util.Objects;

/**
 * Der Schlüssel eines offenen Konflikts: die Version, die ihn trägt, und die Art. Dieselbe Version kann einen Vermerk und
 * zugleich eine Elternlöschung tragen (eine Zusammenführung, die als Löschung entsteht); beide sind getrennte Konflikte und
 * werden getrennt bestätigt.
 *
 * @param id   die Version mit dem Vermerk bzw. die Löschversion
 * @param kind die Art des Konflikts
 */
public record ConflictKey(VersionId id, Kind kind) {

	/** Die Art eines offenen Konflikts. */
	public enum Kind {
		/** Ein Konfliktvermerk, der diesen Rechner betrifft. */
		NOTE("note"),
		/** Eine fremde Löschung eines Event-Kopfs oder einer Vorstellung über eigenen, unbekannten Verkäufen. */
		PARENT_DELETION("deletion");

		private final String prefix;

		Kind(String prefix) {
			this.prefix = prefix;
		}
	}

	/** Prüft Version und Art. */
	public ConflictKey {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
	}

	/** Die Schreibweise in {@code conflicts-ack.json}: {@code note:<Version>} bzw. {@code deletion:<Version>}. */
	String asText() {
		return kind.prefix + ":" + id.value();
	}

	/**
	 * Die Umkehrung von {@link #asText()}.
	 *
	 * @throws IllegalArgumentException wenn der Text weder eine bekannte Art noch eine gültige Versions-ID nennt
	 */
	static ConflictKey parse(String text) {
		int colon = text.indexOf(':');
		if (colon > 0) {
			String prefix = text.substring(0, colon);
			for (Kind kind : Kind.values()) {
				if (kind.prefix.equals(prefix)) {
					return new ConflictKey(VersionId.of(text.substring(colon + 1)), kind);
				}
			}
		}
		throw new IllegalArgumentException("Kein Konfliktschlüssel: " + text);
	}
}
