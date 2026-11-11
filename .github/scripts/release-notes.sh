#!/usr/bin/env bash
# KARV-125: Erzeugt den Text des GitHub-Releases aus src/main/resources/.../patchnotes.md.
# Läuft in release.yml (vor Conveyor, damit ein Fehler den Lauf vor der Veröffentlichung beendet) und in ci.yml.
# Braucht das Linux-Fat-JAR in target/ (mvn package -Plinux). Schreibt release-notes.md und release-version.txt.
set -euo pipefail

# Die Version kommt aus app.version in conveyor.conf, weil Conveyor daraus das Tag macht
VERSION=$(tr -d '\r' < conveyor.conf | sed -n 's/^  version = "\(.*\)"$/\1/p')
test -n "$VERSION"

java -cp "target/Kartenverwaltung-$VERSION-linux.jar" \
  de.eltviller_carneval_verein.karten.update.PatchNotesExport "$VERSION" \
  "[Download](https://SirEddington.github.io/Kartenverwaltung/download.html)" > release-notes.md

# Nicht leer, nicht abgeschnitten, keine ungültigen UTF-8-Bytes. Dass Umlaute auch unter LANG=C richtig ankommen,
# sichert PatchNotesExport selbst (UTF-8-PrintStream); iconv fängt nur kaputte Bytes, keine Ersatzzeichen.
grep -q '^### ' release-notes.md
tail -n 1 release-notes.md | grep -q '^\[Download\]'
iconv -f UTF-8 -t UTF-8 release-notes.md > /dev/null

echo "$VERSION" > release-version.txt
echo "Release-Text für $VERSION:"
cat release-notes.md
