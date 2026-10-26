package de.eltviller_carneval_verein.karten.store;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Der Schlüssel einer Einheit im Versionsspeicher. Er bestimmt, in welchem Ordner (relativ zum Wurzelordner des
 * Speichers) die Versionsdateien der Einheit liegen:
 * <ul>
 * <li>Event: {@code events/<eventId>}</li>
 * <li>Auftritt: {@code events/<eventId>/presentations/<presentationId>}</li>
 * <li>Buchung auf einem Sitz: {@code events/<eventId>/presentations/<presentationId>/bookings/<seatId>}</li>
 * <li>Buchung ohne Sitz: {@code events/<eventId>/presentations/<presentationId>/bookings/_<bookingId>}</li>
 * <li>Halle: {@code halls/<hallId>}</li>
 * </ul>
 * IDs bestehen nur aus {@code [A-Za-z0-9-]} und sind 1 bis 64 Zeichen lang. Damit sind sie auf jedem
 * Dateisystem als Ordnername unbedenklich (keine Trenner, kein {@code ..}, keine Umlaute). Der Unterstrich ist
 * bewusst nicht erlaubt, damit er in {@link #bookingDir()} eindeutig eine Buchung ohne Sitz kennzeichnet.
 * <p>
 * Nicht belegte Komponenten sind {@code null}. {@link #bookingDir()} enthält bei einer Buchung ohne Sitz den
 * führenden Unterstrich.
 *
 * @param type           Art der Einheit
 * @param eventId        ID des Events (nicht bei Hallen)
 * @param presentationId ID des Auftritts (nur bei Auftritten und Buchungen)
 * @param bookingDir     Ordnername der Buchung: Sitz-ID oder {@code _<bookingId>} (nur bei Buchungen)
 * @param hallId         ID der Halle (nur bei Hallen)
 */
public record UnitKey(UnitType type, String eventId, String presentationId, String bookingDir, String hallId) {

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

	private static final String EVENTS = "events";
	private static final String PRESENTATIONS = "presentations";
	private static final String BOOKINGS = "bookings";
	private static final String HALLS = "halls";

	/** Präfix des Ordnernamens einer Buchung ohne Sitz. */
	private static final String UNASSIGNED_PREFIX = "_";

	/**
	 * Prüft, dass genau die Komponenten belegt sind, die zur Art gehören, und dass jede ein zulässiger Name ist.
	 *
	 * @throws IllegalArgumentException wenn die Art fehlt, eine Komponente fehlt, überzählig oder unzulässig ist
	 */
	public UnitKey {
		if (type == null) {
			throw new IllegalArgumentException("Die Art der Einheit fehlt.");
		}
		boolean needsEvent = type != UnitType.HALL;
		boolean needsPresentation = type == UnitType.PRESENTATION || type == UnitType.BOOKING;
		boolean needsBooking = type == UnitType.BOOKING;
		boolean needsHall = type == UnitType.HALL;
		require("eventId", eventId, needsEvent);
		require("presentationId", presentationId, needsPresentation);
		require("hallId", hallId, needsHall);
		if (needsBooking) {
			if (bookingDir == null || !isValidBookingDir(bookingDir)) {
				throw new IllegalArgumentException("Ungültiger Buchungsordner: " + bookingDir);
			}
		} else if (bookingDir != null) {
			throw new IllegalArgumentException("Der Buchungsordner passt nicht zur Art " + type + ".");
		}
	}

	private static void require(String name, String value, boolean needed) {
		if (needed) {
			checkId(name, value);
		} else if (value != null) {
			throw new IllegalArgumentException(name + " passt nicht zur Art der Einheit.");
		}
	}

	private static void checkId(String name, String value) {
		if (!isValidId(value)) {
			throw new IllegalArgumentException("Ungültige ID (" + name + "): " + value);
		}
	}

	private static boolean isValidId(String value) {
		return value != null && ID.matcher(value).matches();
	}

	private static boolean isValidBookingDir(String dir) {
		return isValidId(dir.startsWith(UNASSIGNED_PREFIX) ? dir.substring(UNASSIGNED_PREFIX.length()) : dir);
	}

	/** Schlüssel eines Events. */
	public static UnitKey event(String eventId) {
		return new UnitKey(UnitType.EVENT, eventId, null, null, null);
	}

	/** Schlüssel eines Auftritts. */
	public static UnitKey presentation(String eventId, String presentationId) {
		return new UnitKey(UnitType.PRESENTATION, eventId, presentationId, null, null);
	}

	/** Schlüssel einer Buchung auf einem Sitz; der Ordner heißt wie die Sitz-ID. */
	public static UnitKey booking(String eventId, String presentationId, String seatId) {
		checkId("seatId", seatId);
		return new UnitKey(UnitType.BOOKING, eventId, presentationId, seatId, null);
	}

	/** Schlüssel einer Buchung ohne Sitz; der Ordner heißt {@code _<bookingId>}. */
	public static UnitKey unassignedBooking(String eventId, String presentationId, String bookingId) {
		checkId("bookingId", bookingId);
		return new UnitKey(UnitType.BOOKING, eventId, presentationId, UNASSIGNED_PREFIX + bookingId, null);
	}

	/** Schlüssel einer Halle. */
	public static UnitKey hall(String hallId) {
		return new UnitKey(UnitType.HALL, null, null, null, hallId);
	}

	/** Der Ordner der Einheit relativ zum Wurzelordner des Speichers, immer mit {@code /} getrennt. */
	public String relativePath() {
		return switch (type) {
		case EVENT -> EVENTS + "/" + eventId;
		case PRESENTATION -> EVENTS + "/" + eventId + "/" + PRESENTATIONS + "/" + presentationId;
		case BOOKING -> EVENTS + "/" + eventId + "/" + PRESENTATIONS + "/" + presentationId + "/" + BOOKINGS + "/"
				+ bookingDir;
		case HALL -> HALLS + "/" + hallId;
		};
	}

	/**
	 * Die Umkehrung von {@link #relativePath()}: erkennt aus einem relativen Ordnerpfad (mit {@code /}) die
	 * Einheit. Alles, was nicht genau einem der fünf Muster entspricht (fremde Ordner, Unterordner, leere oder
	 * unzulässige Namen), ergibt {@link Optional#empty()}.
	 */
	public static Optional<UnitKey> parse(String relativePath) {
		if (relativePath == null) {
			return Optional.empty();
		}
		String[] parts = relativePath.split("/", -1);
		try {
			if (parts.length == 2 && parts[0].equals(EVENTS)) {
				return Optional.of(event(parts[1]));
			}
			if (parts.length == 2 && parts[0].equals(HALLS)) {
				return Optional.of(hall(parts[1]));
			}
			if (parts.length == 4 && parts[0].equals(EVENTS) && parts[2].equals(PRESENTATIONS)) {
				return Optional.of(presentation(parts[1], parts[3]));
			}
			if (parts.length == 6 && parts[0].equals(EVENTS) && parts[2].equals(PRESENTATIONS)
					&& parts[4].equals(BOOKINGS)) {
				String dir = parts[5];
				if (dir.startsWith(UNASSIGNED_PREFIX)) {
					return Optional.of(unassignedBooking(parts[1], parts[3], dir.substring(UNASSIGNED_PREFIX.length())));
				}
				return Optional.of(booking(parts[1], parts[3], dir));
			}
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		return Optional.empty();
	}
}
