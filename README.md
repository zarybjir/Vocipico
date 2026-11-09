# Přečti mi to: plovoucí čtečka obrazovky pro Android

Velké kulaté tlačítko, které „plave“ nad každou aplikací (prohlížeč, e-mail,
bankovnictví…). Po klepnutí nahlas a **stručně** přečte, co je na obrazovce:
název stránky, nadpisy, **názvy polí ve formulářích** a jestli jsou vyplněná,
text a tlačítka. Zbytečnosti (reklamy, menu, ikony, cookie lišty) přeskakuje.

- **Žádná AI, žádný internet.** Všechno běží v telefonu, čte se okamžitě.
  Appka nemá ani oprávnění k internetu.
- **Šetrná k výkonu:** běží od Androidu 7, nemá žádné knihovny navíc,
  obrazovku prochází jen na povel a v omezeném rozsahu (nastavitelné).
- **Hodně nastavení:** co číst, co přeskakovat, kolik toho číst, co dělá
  klepnutí, podržení a přetažení bubliny, velikost bubliny, hlas, výkon.

## Ovládání bubliny (výchozí)

| gesto | co udělá |
|---|---|
| klepnutí | přečte obrazovku; když zrovna čte, zastaví |
| podržení | zapne/vypne **režim klepni a přečti** |
| přetažení | přesune bublinu a přečte, co je pod ní |

Každé gesto jde v nastavení přepnout na jinou akci.

## Co je navíc oproti vestavěné čtečce

- **Ukáže, co čte.** Čtené místo se orámuje tlustým žlutým rámečkem a text
  se ukáže obřím písmem dole nebo nahoře (tam, kde nepřekáží). Klepnutím
  na velký text se čtení zastaví.
- **Režim klepni a přečti.** Co ťukneš, to se přečte, ale nic se nespustí.
  Tažením prstu čte, co je pod ním. Dvojím ťuknutím se prvek stiskne.
  Nahoře jsou velká tlačítka ▲ ▼ (posun), Čti (čtení textu) a Konec.
  Telefon se jinak ovládá úplně normálně, ne jako s TalkBackem.
- **Hlásí, co se změnilo.** Po klepnutí v aplikaci chvíli sleduje
  obrazovku a řekne: „Nová stránka …“, „Pozor: Heslo je nesprávné“,
  „Vyskočilo okno …“ nebo přečte krátké hlášení dole na obrazovce.
- **Formulář na jedno klepnutí.** „Formulář, 3 pole, vyplněno 1, prázdné:
  Telefon, E-mail.“ Při klepnutí do pole řekne, co se do něj píše.

## Instalace do telefonu

1. Stáhni APK: na GitHubu **Actions → poslední „Build APK“ → Artifacts**
   (nebo z Releases, pokud je vydaná verze s tagem).
2. Otevři APK v telefonu a povol „Instalovat neznámé aplikace“.
3. Otevři appku **Přečti mi to** → **Zapnout čtečku** → v seznamu služeb
   najdi „Přečti mi to“ a zapni ji.
4. **Android 13 a novější:** přepínač bude šedý („Omezené nastavení“). Pak:
   Nastavení → Aplikace → Přečti mi to → vpravo nahoře ⋮ →
   **Povolit omezená nastavení** a znovu krok 3. Appka na to má tlačítko.
5. Klepni na **Vyzkoušet hlas**. Když chybí český hlas, appka nabídne
   jeho stažení (Google Převod textu na řeč, zdarma, funguje offline).

Bublina se objeví hned po zapnutí služby.

## Když něco nečte dobře

- **Chybí kus stránky:** zvyš „Kolik prvků nejvýš projít“, nebo zapni
  „Důkladné hledání“ (obojí v sekci Výkon).
- **Telefon se zadrhává:** sniž „Kolik prvků nejvýš projít“ (třeba na 300).
- **Čte moc:** vypni v sekci „Co číst“, co nepotřebuje, nebo sniž „Kolik
  textu nejvýš“.
- **Pole nemá název:** některé weby pole nepopisují vůbec. Appka pak zkusí
  použít text, který stojí před polem (nastavení „Domýšlet názvy polí“).
- **Text v obrázcích** appka nepřečte, protože na rozpoznávání textu
  z obrázků by potřebovala velkou knihovnu navíc. Jde to přidat později.

## Sestavení

Na GitHubu: každý push do `main` sestaví APK automaticky (záložka Actions).

V Android Studiu: File → Open → tahle složka → Build → Build APK(s).
APK je v `app/build/outputs/apk/debug/`.

Aby šly aktualizace instalovat přes starou verzi, musí být APK pokaždé
podepsané stejným klíčem. Klíč v repozitáři není; patří do GitHubu jako dva
secrets (**Settings → Secrets and variables → Actions → New repository
secret**): `PMT_KEYSTORE_B64` (klíč v base64) a `PMT_KEYSTORE_PASSWORD`.
Pak GitHub sestavuje podepsanou `app-release.apk`. Klíč i heslo si ulož
bezpečně i jinam — bez nich už aktualizaci stejné appky nepodepíšeš.

## Jak to funguje uvnitř

| soubor | co dělá |
|---|---|
| `ReaderService.kt` | služba přístupnosti; bublina, gesta, události, posouvání stránky |
| `ScreenScanner.kt` | projde prvky obrazovky a pozná pole, tlačítka, nadpisy, odkazy, text; filtruje reklamy a menu |
| `Phrases.kt` | z prvků poskládá české věty podle nastavení |
| `Speaker.kt` | hlas telefonu (jedna instance, dlouhé texty dělí na kousky) |
| `BubbleView.kt` | kreslení a dotyky plovoucího tlačítka |
| `MainActivity.kt` | zapnutí služby a všechna nastavení, velkým písmem |
| `Prefs.kt` | nastavení a jejich výchozí hodnoty |
