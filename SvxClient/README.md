# SVX Client (Android)

App Android per collegarsi a un server **SvxReflector** (SvxLink): ascolti, premi PTT e parli,
come con QSOLink. Protocollo 2.0 (login con nominativo e password), audio Opus.

## Come usarla con il tuo server

| Campo        | Valore                                                              |
|--------------|---------------------------------------------------------------------|
| Server       | `192.168.178.70` in casa (Wi-Fi), `9jadiyn7rn43g858.myfritz.net` fuori |
| Porta        | `5300`                                                              |
| Nominativo   | uno tra `RADIO1`...`RADIO5` (uno diverso per ogni telefono!)         |
| Password     | quella del gruppo (`GRUPPO` nel tuo `svxreflector.conf`)            |
| Talkgroup    | `1` (o quella che usi)                                              |

Fuori casa il router (FRITZ!Box) deve inoltrare la porta **5300 sia TCP sia UDP** verso il Raspberry.

## Come ottenere l'APK

### Strada A: Android Studio (consigliata)
1. Installa Android Studio (gratis) da developer.android.com/studio.
2. `File > Open` e scegli questa cartella `SvxClient`. Aspetta la sincronizzazione di Gradle
   (la prima volta scarica da solo tutto quello che serve; se chiede di installare l'SDK, accetta).
3. Menu `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Al termine clicca "locate": il file e' `app/build/outputs/apk/debug/app-debug.apk`.
5. Copialo sul telefono e aprilo (autorizza "installa da fonti sconosciute").

### Strada B: senza installare nulla (GitHub)
1. Crea un repository su GitHub e carica tutto il contenuto di questa cartella.
2. Vai nella scheda **Actions**: parte da sola la compilazione ("Build APK").
3. A fine lavoro scarica l'artefatto `svx-client-apk`, che contiene `app-debug.apk`.

## Cosa e' stato provato e cosa no
- **Provato**: la parte di rete (`app/src/main/java/it/svxclient/core`) e' stata compilata e
  testata contro un finto reflector scritto dal sorgente ufficiale di SvxLink: login, scelta
  talkgroup, audio in andata e ritorno, heartbeat, password errata, server irraggiungibile.
  Per rifarlo: `cd test && ./run_test.sh`.
- **Non provato**: l'interfaccia, l'audio del telefono (microfono/altoparlante) e il servizio
  in background non sono stati compilati ne' provati su un telefono, e nemmeno la connessione
  al tuo server reale. Se Gradle segnala un errore di compilazione, mandami il messaggio e lo correggo.

## Limiti attuali
- Un solo talkgroup alla volta, niente ascolto di piu' talkgroup.
- Niente TLS/certificati (protocollo 2.0): l'audio viaggia non cifrato, la password no.
- Il telefono deve avere il permesso del microfono. L'app resta attiva in background con una notifica.

## Struttura
- `core/Wire.java`, `core/Proto.java`, `core/SvxSession.java`: protocollo (Java puro, senza Android)
- `AudioEngine.kt`: microfono, altoparlante, codec Opus (libreria Concentus, solo Java)
- `SvxService.kt`: servizio in primo piano, riconnessione automatica
- `MainActivity.kt`: schermata
