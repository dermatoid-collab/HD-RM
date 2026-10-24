# Regole generali per Claude in questo progetto

## Git e push
- **Chiedi sempre conferma prima di eseguire `git push`**, senza eccezioni.
- Quando una build (GitHub Actions) fallisce: pusha al massimo una seconda volta in autonomia per tentare il fix, poi chiedi sempre conferma prima di ulteriori push.

## Controllo build
- Dopo ogni push controlla tu l'esito della build (GitHub Actions) senza aspettare che l'utente lo chieda: programma un controllo ogni 5 minuti finché il run non si conclude (success o failure) e riferisci l'esito, con il link all'APK se la build riesce.
- Non triggerare manualmente `build_android.yml`: parte da sola a ogni push su main (path `android-widget/**`), un run manuale crea una build doppia.

## Sicurezza
- Non includere API key, token, secret o credenziali di alcun tipo nel codice o nei commit. Se uno script le referenzia, lascia il nome della variabile d'ambiente ma ometti il valore.

## Stile
- Rispondi in italiano salvo diversa indicazione.
