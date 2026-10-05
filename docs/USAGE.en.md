# How to use Kanade

Kanade is a Japanese input method built for physical keyboards. When no physical keyboard is connected, you can type with the on-screen keyboard.

## Getting started

1. Open Kanade's settings. If anything still needs setting up, a guide appears at the top left. Follow it to enable Kanade as an input method and switch to it
2. Allow "Display over other apps". Kanade uses this to show conversion candidates and the "あ"/"A" indicator near the cursor (without it, candidates may not appear on Galaxy devices when you use a physical keyboard)
3. If you want to use voice input, also allow the microphone

## Turning Japanese on and off

| Key | Action |
|---|---|
| Henkan / Kana (JIS) | On |
| Muhenkan / Eisu (JIS) | Off |
| Hankaku/Zenkaku (JIS) | Toggle |
| Tap of right Alt, Ctrl+Space, or F13 (US; choose in settings) | Toggle |

When you switch, "あ" (Japanese) or "A" (direct input) briefly appears near the cursor.

You can choose the punctuation that `,` and `.` produce under "Input and conversion" in settings: "、。", "，．", "、．", or "，。".

## While composing (before you press Space)

| Key | Action |
|---|---|
| Letters | Turns romaji into kana. Each keystroke shows the converted result (live conversion: kana become kanji as you type; you can turn it off in settings) |
| Enter | Commits what you see |
| Space, ↓ | Starts conversion (the text is split into segments and the candidate window appears) |
| ←→, Home, End | Moves the cursor inside the text you are composing. While the cursor is in the middle, the text shows as kana; move back to the end to see the converted result again |
| Backspace, Delete | Deletes one character before or after the cursor |
| Shift+←→ | Starts conversion and shrinks or extends the first segment |
| F6–F10, Ctrl+U/I/O/P/T | Converts to hiragana, katakana, half-width katakana, full-width alphanumerics, or half-width alphanumerics |
| Esc | Cancels everything |

## English spellings for katakana words

When you type a word by its katakana reading, the English spelling also appears among the candidates (アップル→Apple, グーグル→Google, ニューヨーク→New York, キーボード→keyboard). In the candidate window it is labeled "English".

## Brackets

When you type an opening bracket such as 「 or （ in Japanese input, Kanade also inserts the closing bracket and puts the cursor between them (you can turn this off under "Input and conversion" in settings). Text you type inside is live-converted as usual.

- Typing the closing bracket skips over the existing one instead of inserting another
- Pressing Backspace on an empty pair deletes both brackets
- Pressing Space on an empty pair cycles through （）→「」→『』→【】→［］→｛｝… (Shift+Space goes backwards; you can also pick from the candidate window). This is handy for turning the （ you get from "や" on the flick keyboard into another bracket
- During conversion, if a segment is a single bracket, other brackets also appear as candidates

## Converting (after you press Space)

| Key | Action |
|---|---|
| Space, ↓ / Shift+Space, ↑ | Moves to the next or previous candidate for the selected segment |
| 1–9 | Picks the candidate with that number and moves to the next segment (commits if there is only one segment) |
| Tap a candidate | Same as 1–9. You can scroll the candidate window with your finger |
| ←→ | Moves between segments |
| Shift+←→ | Shrinks or extends the selected segment by one character |
| Enter | Commits everything |
| Letters | Commits the current conversion and starts new input |
| Esc, Backspace | Cancels conversion and goes back to kana |

Labels such as "User dictionary", "Hiragana", "Katakana", "Symbol", "Emoji", "Full-width", and "Half-width" appear to the right of candidates.
Every dictionary word with that reading appears as a candidate, including symbols ("やじるし" → "→") and emoji ("えがお" → "😀").
Readings such as "きょう" (today), "あした" (tomorrow), and "きのう" (yesterday) also offer dates (2026/10/03, 10月3日（土）, 令和8年10月3日, etc.), "いま" (now) offers the time, and "ことし" (this year) and "らいねん" (next year) offer the year.

## Learning

If you pick a candidate other than the first one and commit it, Kanade remembers it for that reading. Next time it appears higher in the list and is more likely to be used in live conversion. You can turn this off with "Learn" in settings, and clear what it has learned with "Clear learning" (your user dictionary is not affected).

## User dictionary

Register a reading (in hiragana) and a word. Registered words are preferred in conversion and appear first among the candidates (just like the macOS user dictionary). They are also more likely to be used for that reading in the middle of a sentence.

- Edit it under "User dictionary" in settings, either as a list ("Add"; tap a row to edit or delete it) or as text (register many at once, or copy to export)
- In text form, each line is `reading word` (split at the first space). Lines starting with `#` are ignored

```
かなで Kanade
でっくす DeX
```

## Snippets

The moment you finish typing a key, it is replaced with its output. Snippets work both in Japanese input and in direct input.

- The key is the characters you actually type (even in Japanese input, Kanade looks at the characters before they become kana or full-width)
- Keys made only of letters (like `sig`) also react to romaji in Japanese input, so keys with symbols (`@@`, `->`) are recommended
- Longer keys win (if you have both `->` and `-->`, `-->` is used)
- In Japanese input, the text you are composing before the key is committed as currently shown, and then the key is replaced. In direct input, Kanade checks the text before the cursor before replacing (nothing is replaced if you moved the cursor)
- In text form, each line is `key output` (split at the first space). `\n` in the output is a line break. Lines starting with `#` are ignored

```
@@ you@example.com
-> →
<- ←
=> ⇒
sig お世話になっております。\nよろしくお願いいたします。
```

## On-screen keyboard

The keyboard appears at the bottom of the screen only when no physical keyboard is connected (you can also set it to "Always" or "Never" under "On-screen keyboard" in settings). There are three layouts: "Glide", "Full keyboard", and "12-key". Choose which ones to use under "Layouts" in settings (Glide and 12-key at first). Swipe up on the bottom-left key to switch to the next of your layouts.

Keys you press are handled just like a physical keyboard. Japanese input, conversion, F6–F10, and Ctrl+U/I/O/P/T all work, and keys the IME doesn't use (Ctrl+C, Ctrl+Z, Ctrl+D in terminal apps, etc.) are passed straight to the app. You can change the height (up to 300%) and the bottom padding under "On-screen keyboard" in settings (increase the bottom padding on devices where the keyboard overlaps the navigation bar).

### Floating

You can detach the keyboard from the bottom of the screen and float it anywhere, at any width (swipe down on the bottom-left key: ?123, Ctrl, or "あa1"). While it floats, the app's screen doesn't shrink, and touching outside the keyboard lets you use the app underneath.

- Drag the middle of the handle below the keyboard to move it
- Flick the handle and the keyboard keeps sliding. When it hits a screen edge, it bounces slightly past it and settles inside
- Drag the right end of the handle (↔) to change the width
- Tap the left end of the handle to dock it back at the bottom
- The position and width are remembered, so it appears in the same place next time

### Glide

Slide your finger across the QWERTY letter keys without lifting it to enter a word (like glide typing in Gboard). Letter keys have no swipe symbols, so moving your finger starts gliding right away. Lift without moving to type that letter as usual.

- With Japanese on, glide the romaji spelling to enter kana as composing text ("arigatou" → ありがとう). Then convert with Space as usual. Both Hepburn (shi, tsu, ji) and Kunrei (si, tu, zi) spellings work
- With Japanese off, you enter English words. If there is a word before, a space is inserted in between. Press Shift before gliding to capitalize the first letter
- Other candidates appear in the candidate window. Tap one to replace the word you entered
- Pressing Backspace right after gliding deletes the whole word (in English, also the space added before it). You can then glide again
- In Japanese, words that naturally follow the reading you are composing come first (after "ほんが", aru becomes "ある", etc.)
- zenz (a language model used to rerank conversions) also checks how the word fits with the preceding text. The dictionary's top choice is entered first, and if zenz picks a different word, it is swapped in a moment later (after "かおを", arau changes from "表す" to "洗う", etc.). You can turn this off under "Layouts" in settings
- Words you enter by gliding and keep are remembered and offered first next time (words you delete right away or replace are not counted)
- Spellings with doubled letters (like kk for "っ") or differing vowel length (たのし vs. たのしい) produce the same glide shape. The more common one comes first, so pick from the candidates when it's wrong
- Glide one word at a time (Japanese uses about 60,000 readings from the Mozc dictionary; English has about 32,000 words, including contractions like i'm and don't)
- ?123 at the bottom left switches to the numbers and symbols page, and =\< there switches to a page of more symbols (「」, €, etc.). ABC brings you back to letters
- Swipe ?123 up to switch layouts, or down to float the keyboard. Swipes on あ/A, Space, and Backspace work the same as on the full keyboard

### Full keyboard (QWERTY)

Works the same way as Unexpected Keyboard, and shortcuts work as-is.

- Swipe a key toward a corner or up, down, left, or right to enter the small character printed there (on the top row, numbers are at the top right and symbols at the top left; the second row also has symbols at the top right and top left)
- Shift, Ctrl, Alt, and Fn apply to the next keystroke only. Press twice to lock them (press again to release). While active, the key becomes pill-shaped
- You can also hold a modifier while pressing another key (hold Ctrl and press a to select all; hold Shift and swipe Space left to select one character at a time). Modifier presses and releases are sent to the app too, so Shift+arrow selection and app shortcuts work just like on a physical keyboard
- Swipe Ctrl up to switch to the 12-key (flick) layout, or down to float the keyboard (same position and directions as "あa1" on Flick)
- Swipe Alt up for Meta (⌘, the equivalent of the Windows key)
- Fn+digits gives F1–F10, and Fn+`-` `=` gives F11 and F12 (`-` is at the top right of g, `=` at the top right of h)
- Drag Space sideways to move the cursor along with your finger (it selects if Shift or similar is active). A short swipe moves one character
- Swipe the Tab key up, down, left, or right for arrow keys, or toward the four corners for Home, End, PgUp, and PgDn
- Hold Backspace to keep deleting; swipe it right for Delete. Letter keys also repeat when held
- The あ/A key turns Japanese on and off. Swipe it up for the emoji panel, or down for the clipboard
- The mic key records only while you hold it (same as the record key). A short press starts continuous listening

### Flick

A 12-key layout that works like Gboard's. Tap the center character of a key for "あ", or swipe left, up, right, or down for "い", "う", "え", or "お".

- "小゛゜" adds or removes a voiced mark, semi-voiced mark, or small form on the previous character (か→が, は→ば→ぱ, つ→っ→づ)
- Pressing the same key repeatedly cycles to the next character (toggle input; you can turn this off in settings). ↶ steps back one
- "あa1" switches between kana, letters, and numbers. Swipe it up for QWERTY
- Tap ☺ for emoji, swipe it left for the clipboard, or up for voice input
- The right column is Backspace, →, Space (converts while composing), and Enter
- On wide screens (such as the inner screen of a Fold), the keyboard splits into left and right halves, just like Gboard

### Emoji

Choose a category with the tabs at the top. The clock tab shows recently used emoji. "ABC" at the bottom left ("あ" in Japanese input) takes you back to the keyboard.

Tap "Search emoji" to bring up a keyboard below, and search in romaji, kana, or English (neko, ねこ, 猫, cat, etc.). Conversion works as usual, with candidates shown in the results row. On wide screens, the search field sits next to the category tabs.

### Clipboard

A history of text and images you've copied. Tap an item to paste it; long-press to pin it, crop it (for images), or delete it.

- Up to the 30 newest unpinned items are kept
- Copies marked as sensitive, such as passwords, are not kept. You can turn history off with "Keep copy history" in settings
- In input fields that don't accept images directly, Kanade puts the image on the clipboard and lets you know. Long-press the field to paste it

## Voice input

Recording runs only while you hold the record key (F12 by default; the mic key on the on-screen keyboard). When you let go, a model on your device transcribes the speech and enters the text. Audio never leaves your device.

1. Allow the microphone
2. "Download" a model from the list under "Voice input" in settings (if you're not on Wi-Fi, Kanade asks before downloading. Keep the settings screen open while it downloads. Tap again to stop; the next download resumes where it left off)
3. Tap "Apply" on the model you want to use (the active model is marked "In use")
4. "Test with a sample" compares the speed and results of all downloaded models
5. Change the record key with the "Record key" button (pick from the list, or press a key on a physical keyboard)

While recording, "🎤" appears near the cursor; while recognizing, "…" appears. If there's only silence or noise, nothing is entered.

### Models

ReazonSpeech shows the text while you speak, and the model you applied with "Apply" produces the text that goes into the field. Each model has tags for language, accuracy, speed, punctuation, and size.

| Model | Size | Tags |
|---|---|---|
| parakeet-ja | 656MB | Japanese, high accuracy, fast, punctuation (NVIDIA's Japanese-only model) |
| ReazonSpeech | 73MB | Japanese, high accuracy, very fast, no punctuation (also used for the live preview; add punctuation by speaking it) |
| SenseVoice | 239MB | Chinese, English, Japanese, Korean, medium accuracy, fast, punctuation |
| whisper small | 190MB | Multilingual, low accuracy, slow, punctuation |
| kotoba-whisper v2.0 | 538MB | Japanese, high accuracy, very slow, little punctuation |
| whisper large-v3-turbo | 574MB | Multilingual, high accuracy, very slow, punctuation |

If the applied model isn't downloaded, Kanade uses another downloaded model (a note appears above the list). Remove models you no longer need with "Delete". To see text while you speak, download ReazonSpeech and turn on "Show text while speaking" (on by default).

"Import a model not on the list" lets you add model files that aren't in the list. Kanade guesses the format from the file names and asks you to confirm before importing.

- whisper.cpp (ggml): a single ggml-*.bin file
- sherpa-onnx transducer (zipformer, etc.): encoder, decoder, and joiner .onnx files plus tokens.txt
- sherpa-onnx NeMo CTC (parakeet, etc.): a model .onnx file plus tokens.txt
- sherpa-onnx SenseVoice: a model .onnx file plus tokens.txt

### Continuous listening

A short press on the record key (mic key) keeps listening, like voice typing on iPhone or Pixel. Each time you finish speaking (about 0.7 seconds of silence), what you said so far is transcribed and entered at the cursor.

- You can keep typing on the keyboard while it listens. If recognition finishes while you have composing text, that text is committed first
- Text you're still speaking appears in the field as uncommitted text (in apps like Termux, it appears in the bar). It disappears briefly when you press a key and comes back while you speak
- Press again to stop (if you're mid-sentence, what you've said so far is entered). It also stops after a minute of silence, or when you move to another input field
- The mic key on the on-screen keyboard changes color while listening

### Spoken punctuation

Say words like "くてん" or "とうてん" to enter punctuation (you can turn this off with "Speak punctuation" in settings).

| Say | Enters |
|---|---|
| くてん | 。 |
| とうてん | 、 |
| かいぎょう | Line break |
| かいだんらく | Two line breaks |
| はてなマーク, ぎもんふ | ？ |
| びっくりマーク, かんたんふ | ！ |
| かぎかっこ / かぎかっことじ | 「 / 」 |
| かっこ / かっことじ | （ / ） |
| なかぐろ | ・ |

If this overlaps with punctuation the model added itself, the two are merged into one. Ordinary words such as "かっこいい" (cool) are left as they are.

## Display language

Under "Display language" in settings, you can show Kanade's screens in Japanese, English, or your device's language (Android 13 and later; you can also choose it under "App languages" in the system settings).

## Exporting and importing settings

"Export and import" in settings writes your settings, user dictionary, and snippets to a single file (JSON). Use it to move to another device or to keep a backup.

- Learned conversions, clipboard history, key logs, and voice models are not included
- Importing replaces your current settings, user dictionary, and snippets with the file's contents. If you may want to go back, export your current settings first
- If you had applied an imported voice model and the new device doesn't have it, Kanade uses an available model instead

## Apps that can't show composing text

In apps like Termux that can't display composing text properly, Kanade shows the composing text in the candidate window and sends only committed text to the app. You can add apps under "Apps that show typing in the bar" in settings (`com.termux` by default).
