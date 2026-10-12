# Language-detection table — provenance

`app/src/main/assets/langdetect.tab` is the character-trigram table behind
per-utterance language detection (`lang/LangDetector.kt`): which of
English, Spanish, French, Italian, Portuguese and German a piece of Latin-script
text is written in. Japanese, Chinese and Hindi are recognised by their
script and need no table.

- **License:** MIT, as part of the Marmalade source. The table holds only
  trigram statistics Marmalade computed itself; it contains no passages
  from the books below.
- **Trained on:** public-domain books from [Project Gutenberg](https://www.gutenberg.org),
  each written in (or translated into) the language it trains. German
  (added in 2026) uses only German originals whose authors died more than
  70 years ago, so they are in the public domain in Europe as well as the
  United States.
- **Training tools:** built with the training scripts in
  `tools/langdetect-train/` of the marmalade-tts-cli repository, with German
  added to their language list and the books below pinned by number.

## Books (Project Gutenberg ebook numbers)

**English** (`en`)

- #2701 *Moby Dick; Or, The Whale*
- #1342 *Pride and Prejudice*
- #2641 *A Room with a View*
- #1727 *The Odyssey: Rendered into English prose for the use of those who cannot read the original*
- #1513 *Romeo and Juliet*
- #2554 *Crime and Punishment*

**Spanish** (`es`)

- #61851 *El crimen y el castigo*
- #78052 *Andanzas y visiones españolas*
- #78068 *Los miserables - Tomo 2 (de 2)*
- #55514 *Cuentos de amor*
- #52597 *Insolación y Morriña (Dos historias amorosas)*

**French** (`fr`)

- #62215 *Le Fantôme de l'Opéra*
- #13951 *Les trois mousquetaires*
- #14287 *L'île mystérieuse*
- #4791 *Voyage au Centre de la Terre*
- #78027 *Les enchantements de la forêt*
- #76412 *Le cas étrange du docteur Jekyll; Un logement pour la nuit*

**Italian** (`it`)

- #1012 *La Divina Commedia di Dante*
- #61885 *Ricordi d'infanzia e di scuola: seguìti da Bambole e marionette, Gente minima, Piccoli studenti, Adolescenti, Due di spade e due di cuori*
- #38720 *L'amore che torna: romanzo*
- #38628 *Vecchie storie d'amore*
- #25178 *Damiano: Storia di una povera famiglia*
- #25179 *La carità del prossimo*
- #22501 *Fino a Dogali*

**Portuguese** (`pt`)

- #23145 *A Illustre Casa de Ramires*
- #24919 *Amor Crioulo: vida argentina*
- #24824 *Os deputados brasileiros nas Côrtes Geraes de 1821*
- #25925 *Poesias*
- #27964 *Portugal e Brazil: emigração e colonisação*
- #22015 *As Minas de Salomão*
- #22622 *Aves Migradoras*
- #26338 *Alguns homens do meu tempo: impressões litterarias*
- #27637 *A correspondência de Fradique Mendes: memórias e notas*

**German** (`de`)

- #22367 *Die Verwandlung*, Franz Kafka (1883–1924)
- #65661 *Der Zauberberg. Erster Band*, Thomas Mann (1875–1955)
- #50285 *Dr. Mabuse, der Spieler*, Norbert Jacques (1880–1954)
- #35312 *Aus dem Leben eines Taugenichts*, Joseph von Eichendorff (1788–1857)
- #78056 *Urwald*, Raoul Heinrich Francé (1874–1943)
- #78125 *Liebe ist ewig*, Wilhelm von Polenz (1861–1903)
- #77073 *Die Flucht der Beate Hoyermann*, Thea von Harbou (1888–1954)
- #5323 *Effi Briest*, Theodor Fontane (1819–1898)
