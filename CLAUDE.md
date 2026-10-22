# Regole generali per Claude in questo progetto

## Git e push
- **Chiedi sempre conferma prima di eseguire `git push`**, senza eccezioni.
- Quando una build (GitHub Actions) fallisce: pusha al massimo una seconda volta in autonomia per tentare il fix, poi chiedi sempre conferma prima di ulteriori push.

## Sicurezza
- Non includere API key, token, secret o credenziali di alcun tipo nel codice o nei commit. Se uno script le referenzia, lascia il nome della variabile d'ambiente ma ometti il valore.

## Stile
- Rispondi in italiano salvo diversa indicazione.
