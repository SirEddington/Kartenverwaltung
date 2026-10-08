/**
 * Fachliche Plausibilitätsprüfungen (ohne JavaFX, mit JUnit testbar).
 *
 * <p>Konvention: ein Validator je Entität des Modells, jeweils mit eigenem Issue-Enum und
 * eigenem Code-Kürzel. Der Code lautet {@code <Kürzel>-<3-stellige Nummer>}, Nummern werden nie
 * wiederverwendet. Ein Validator wirft keine Ausnahme, sondern liefert ein {@link ValidationResult}.
 *
 * <p>Meldungstexte stehen als Vorlage im Issue-Enum und enthalten Platzhalter {@code &1} bis {@code &9}
 * (wie in SAP, siehe {@link MessageTemplate}). {@code &1} ist in der Regel die Beschreibung der
 * betroffenen Entität ({@link EntityLabels}); weitere Parameter sind Werte aus der Prüfung.
 * Aufruf: {@code SeatIssue.PRICE_TOO_HIGH.toIssue(EntityLabels.seat(seat), preis, maximum)}.
 *
 * <p>Eine Prüfung gehört zu der Entität, deren Daten sie braucht; Prüfungen, die Geschwister
 * vergleichen (z. B. überlappende Tische), gehören zum Elternteil.
 *
 * <table>
 * <caption>Entitäten und Code-Kürzel</caption>
 * <tr><th>Entität</th><th>Kürzel</th><th>Validator / Enum</th><th>Stand</th></tr>
 * <tr><td>Event</td><td>EVT</td><td>EventValidator / EventIssue</td><td>geplant</td></tr>
 * <tr><td>Vorstellung</td><td>PRS</td><td>PresentationValidator / PresentationIssue</td><td>geplant</td></tr>
 * <tr><td>Tisch</td><td>TBL</td><td>TableValidator / TableIssue</td><td>geplant</td></tr>
 * <tr><td>Sitz</td><td>SEA</td><td>{@link SeatValidator} / {@link SeatIssue}</td><td>vorhanden</td></tr>
 * <tr><td>Halle</td><td>HLL</td><td>HallValidator / HallIssue</td><td>geplant</td></tr>
 * <tr><td>Hallenobjekt</td><td>HOB</td><td>HallObjectValidator / HallObjectIssue</td><td>geplant</td></tr>
 * <tr><td>technisch (unerwartet)</td><td>SYS</td><td>GlobalErrorHandler.CODE</td><td>vorhanden</td></tr>
 * </table>
 */
package de.eltviller_carneval_verein.karten.validation;
