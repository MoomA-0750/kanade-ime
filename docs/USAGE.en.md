# How to use Kanade

Kanade is a Japanese input method with voice typing, for physical and on-screen keyboards. When no physical keyboard is connected, you can type with the on-screen keyboard.

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
| Tab | While predictions are shown, selects the next prediction (press Enter to insert it) |

### Predictions

Turn on Settings → Input and conversion → Predictions to see words that complete what you are typing in the candidate window (きーぼ → キーボード, ありがと → ありがとう, ありがとうございます). Earlier segments stay as converted and only the last word is completed (きょうはきーぼ → 今日はキーボード). Tap a prediction, or pick one with Tab and press Enter. Off by default. zenz reorders the predictions by how well they fit the preceding text, so the order may change a moment later.

### How zenz is used, and the conversion profile

Conversions come from the dictionary and are corrected by zenz, a small language model. Choose how under Settings → Input and conversion → How zenz is used.

- **Check and fix** (default): zenz checks the dictionary's top conversion and, where it disagrees, the dictionary searches again from that point (the same method as azooKey's Zenzai). Less computation, faster
- **Rerank**: zenz scores the top 10 dictionary conversions and reorders them

Both are about equally accurate but make different mistakes. Conversions that contain a word you chose yourself and Kanade learned are not changed by zenz.

Write something about yourself in "Conversion profile" (e.g. "プログラマ。技術の話が多い", up to 25 characters) and zenz will lean toward fitting words.

### Typos on the 12-key layout

When you type on the on-screen 12-key layout, Kanade also offers candidates that fix a missing dakuten or small kana (しやしん → 写真, ちよつと → ちょっと). The conversion of exactly what you typed always stays within the top three candidates.

## Reconversion

Select text that Kanade committed and press Space (or the Henkan key on a physical keyboard) to convert it again. Conversion starts with the segments and spellings you committed, so you can change just the segment you want. Esc restores the original text.

- Readings come from what Kanade remembered when you committed (the latest 300). Hiragana, katakana, and symbols are read as they are. Kanji typed with another keyboard, or before Kanade was restarted, have no known reading, so Space works as usual (replaces the selection with a space)
- Nothing is remembered in fields that ask not to learn (such as incognito mode)

## English spellings for katakana words

When you type a word by its katakana reading, the English spelling also appears among the candidates (アップル→Apple, グーグル→Google, ニューヨーク→New York, キーボード→keyboard). In the candidate window it is labeled "English". Words not in the table are tried with and without a final long vowel (コンピュータ / コンピューター), and built from known words (ワールドカップ → World Cup).

## Brackets

When you type an opening bracket such as 「 or （ in Japanese input, Kanade also inserts the closing bracket and puts the cursor between them (you can turn this off under "Input and conversion" in settings). Text you type inside is live-converted as usual.

- Typing the closing bracket skips over the existing one instead of inserting another
- Pressing Backspace on an empty pair deletes both brackets
- Pressing Space on an empty pair cycles through （）→「」→『』→【】→［］→｛｝… (Shift+Space goes backwards; you can also pick from the candidate window). This is handy for turning the （ you get from "や" on the 12-key layout into another bracket
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
Every dictionary word with that reading appears as a candidate, including symbols ("やじるし" → "→") and emoji ("えがお" → "😀"). When a segment is a word plus a particle (かんじが), every dictionary word for the word's reading is offered with the rest attached (漢字が, 感じが, 幹事が, 監事が, …).
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

### Languages per layout and the layout list

Under "Layouts" in settings, choose which languages each layout is used for (Japanese, English, Numbers & symbols) — for example, 12-key for Japanese only and Glide for English and numbers/symbols.

- Tapping あ/A (“あa1” on 12-key) moves to the next language on that layout. If the layout has only one language, it moves to the next layout instead (in the example above, “あa1” on 12-key goes to Glide in English, and あ/A on Glide goes to 12-key in Japanese)
- Long-press あ/A or “あa1” to see a list grouped by layout (for example, Japanese, English, and Numbers & symbols under "12-key"), and pick one (like long-pressing the globe key on iPhone)
- When you switch layouts by swiping up on the bottom-left key, the language also changes to one that layout uses
- If Glide isn't set to use numbers & symbols, ?123 switches to a 12-key layout that is
- When you switch Japanese on or off with a physical keyboard, Kanade also moves to a layout that uses that language

### Foldable phones

- When the phone is open like a book and bent (vertical fold, half open), the glide, full and 12-key keyboards split on either side of the fold, and no key sits on the fold (qwert | yuiop, asdfg | ghjkl, zxcv | vbnm, with g, v and Space on both halves). Opened flat, the keyboard joins back into one
- "Split at the fold" in Settings → On-screen keyboard → Size chooses between "Even flat" (split even when opened flat), "When folded" (the default) and "Never", separately for Glide, Full keyboard and 12-key
- When it is half open (L shape) with a horizontal fold (a Fold held sideways, a Flip held upright), the keyboard fills the screen from the bottom up to the fold. The upper half stays with the app. Opened flat, the keyboard goes back to the height in settings
- Folding and unfolding moves the keys smoothly between the layouts
- Neither happens while the keyboard is floating

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
- Swipe ?123 up to switch layouts, down to float the keyboard, or left to open settings. Swipes on あ/A, Space, and Backspace work the same as on the full keyboard

### Tap correction

On the Glide and 12-key layouts, when you tap near the edge between keys, Kanade picks the key whose character is more likely to come next in the word you're typing (like the iPhone keyboard: after "th", a tap between e and w becomes e). On the 12-key layout it does the same when a diagonal flick makes the direction unclear. Taps near the middle of a key are never changed. You can turn this off under "On-screen keyboard" → controls in settings.

### Full keyboard (QWERTY)

Works the same way as Unexpected Keyboard, and shortcuts work as-is.

- Swipe a key toward a corner or up, down, left, or right to enter the small character printed there (on the top row, numbers are at the top right and symbols at the top left; the second row also has symbols at the top right and top left)
- Shift, Ctrl, Alt, and Fn apply to the next keystroke only. Press twice to lock them (press again to release). While active, the key becomes pill-shaped. Switching layouts (to Glide or the 12-key) releases them
- You can also hold a modifier while pressing another key (hold Ctrl and press a to select all; hold Shift and swipe Space left to select one character at a time). Modifier presses and releases are sent to the app too, so Shift+arrow selection and app shortcuts work just like on a physical keyboard
- Swipe Ctrl up to switch to the 12-key layout, or down to float the keyboard (same position and directions as "あa1" on 12-key)
- Swipe Esc up to open settings
- Swipe Alt up for Meta (⌘, the equivalent of the Windows key)
- Fn+digits gives F1–F10, and Fn+`-` `=` gives F11 and F12 (`-` is at the top right of g, `=` at the top right of h)
- Drag Space sideways to move the cursor along with your finger (it selects if Shift or similar is active). A short swipe moves one character
- Swipe the Tab key up, down, left, or right for arrow keys, or toward the four corners for Home, End, PgUp, and PgDn
- Hold Backspace to keep deleting; swipe it right for Delete. Letter keys also repeat when held. The delay before repeating and the repeat speed are in Settings → On-screen keyboard → Gestures
- The あ/A key turns Japanese on and off. Swipe it up for the emoji panel, or down for the clipboard
- The mic key records only while you hold it (same as the record key). A short press starts continuous listening

### 12-key

A 12-key layout that works like Gboard's. Tap the center character of a key for "あ", or swipe left, up, right, or down for "い", "う", "え", or "お".

- "小゛゜" adds or removes a voiced mark, semi-voiced mark, or small form on the previous character (か→が, は→ば→ぱ, つ→っ→づ)
- Pressing the same key repeatedly cycles to the next character (toggle input; you can turn this off in settings). ↶ steps back one
- Swipe ↶ left to select all, right to redo, up to copy, and down to paste (it sends Ctrl+A, Ctrl+Shift+Z, Ctrl+C, and Ctrl+V)
- "あa1" switches between kana, letters, and numbers. Swipe it up for QWERTY, or left to open settings
- Tap ☺ for emoji, or swipe it for the clipboard
- The mic key records only while you hold it (same as the mic on QWERTY). A short tap keeps listening
- The left column is ↶, ☺, mic, and あa1; the right column is Backspace, Tab, Space (converts while composing), and Enter
- The Tab key works like Tab on QWERTY: swipe up/down/left/right for arrows, or to a corner for Home, End, PgUp, and PgDn. Arrows and PgUp/PgDn repeat while you keep holding after the swipe
- During conversion, the Tab key becomes the "Seg" key: swipe left to shrink the selected segment and right to extend it (Shift+←→ on a physical keyboard). Tap or swipe to the top-right for the next segment, the top-left for the previous one, and up/down for the previous/next candidate. After shrinking a segment, picking a candidate moves on to the next segment
- On wide screens (such as the inner screen of a Fold), the keyboard splits into left and right halves, just like Gboard

### Emoji

Choose a category with the tabs at the top. The clock tab shows recently used emoji. "ABC" at the bottom left ("あ" in Japanese input) takes you back to the keyboard.

Tap "Search emoji" to bring up a keyboard below, and search in romaji, kana, or English (neko, ねこ, 猫, cat, etc.). Conversion works as usual, with candidates shown in the results row. On wide screens, the search field sits next to the category tabs.

### Clipboard

A history of text and images you've copied. Tap an item to paste it; long-press to pin it, edit it (for text), crop it (for images), or delete it.

- The bottom-left corner of each card shows the app it was copied from. Android doesn't tell keyboards where a copy came from, so this is the app the keyboard was connected to when you copied. If you copy in an app where you never opened the keyboard, it may show the previous app instead
- "Edit" opens an editing screen where you can type normally. "Save" updates the item and returns you to the app you were in
- Up to the 30 newest unpinned items are kept. Items older than "Keep items for" in settings (no limit, 1 hour, 1 day, 1 week, or 30 days) are deleted automatically. Pinned items are kept
- Copies marked as sensitive, such as passwords, are not kept. You can turn history off with "Keep copy history" in settings
- In input fields that don't accept images directly, Kanade puts the image on the clipboard and lets you know. Long-press the field to paste it
- With a physical keyboard, press Ctrl+Shift+V to open it. Even when the on-screen keyboard isn't shown, the same cards appear near the cursor. Choose with the arrow keys or Tab and press Enter (or Space), or press the number in a card's bottom-right corner to paste; Esc closes it. Typing any other character closes it and types as usual. Touch works the same as with the on-screen keyboard
- Drag the middle of the handle at the bottom of the panel to move it, and drag the bottom-right corner (diagonal lines) to resize it. The position and size are remembered for next time. Double-tap the handle to return it to the cursor and its original size
- You can change the shortcut under Settings → On-screen keyboard → Clipboard → "Physical keyboard shortcut". Tap the button, then press a combination on the physical keyboard. You can also choose "None". With Ctrl+Shift+V, it opens instead of the app's "paste as plain text". Combinations the device takes first, such as Meta+V, may not work

## Voice input

Recording runs only while you hold the record key (F12 by default; the mic key on the on-screen keyboard). When you let go, a model on your device transcribes the speech and enters the text. Audio never leaves your device.

1. Allow the microphone
2. "Download" a model from the list under "Voice input" in settings (if you're not on Wi-Fi, Kanade asks before downloading. Keep the settings screen open while it downloads. Tap again to stop; the next download resumes where it left off)
3. Tap "Apply" on the model you want to use (the active model is marked "In use")
4. "Test with a sample" compares the speed and results of all downloaded models
5. Change the record key with the "Record key" button (pick from the list, or press a key on a physical keyboard)

While recording, "🎤" appears near the cursor; while recognizing, "…" appears. If there's only silence or noise, nothing is entered.

### Models

Nemotron (or ReazonSpeech if Nemotron isn't downloaded) shows the text while you speak, and the model you applied with "Apply" produces the text that goes into the field. Each model has tags for language, accuracy, speed, punctuation, and size.

| Model | Size | Tags |
|---|---|---|
| parakeet-ja | 656MB | Japanese, high accuracy, fast, punctuation (NVIDIA's Japanese-only model) |
| ReazonSpeech | 73MB | Japanese, high accuracy, very fast, no punctuation (also used for the live preview; add punctuation by speaking it) |
| SenseVoice | 239MB | Chinese, English, Japanese, Korean, medium accuracy, fast, punctuation |
| whisper small | 190MB | Multilingual, low accuracy, slow, punctuation |
| kotoba-whisper v2.0 | 538MB | Japanese, high accuracy, very slow, little punctuation |
| whisper large-v3-turbo | 574MB | Multilingual, high accuracy, very slow, punctuation |
| Nemotron-3.5 Streaming | 682MB | Japanese, medium accuracy, streaming, punctuation, live preview (from NVIDIA; when downloaded, the live preview uses it) |

If the applied model isn't downloaded, Kanade uses another downloaded model (a note appears above the list). Remove models you no longer need with "Delete". To see text while you speak, download Nemotron or ReazonSpeech and turn on "Show text while speaking" (on by default). Nemotron transcribes the audio as it arrives, so it stays fast during long speech and adds punctuation. The first use takes a few seconds to load, and no text appears during that time.

"Import a model not on the list" lets you add model files that aren't in the list. Kanade guesses the format from the file names and asks you to confirm before importing.

- whisper.cpp (ggml): a single ggml-*.bin file
- sherpa-onnx transducer (zipformer, etc.): encoder, decoder, and joiner .onnx files plus tokens.txt
- sherpa-onnx NeMo CTC (parakeet, etc.): a model .onnx file plus tokens.txt
- sherpa-onnx SenseVoice: a model .onnx file plus tokens.txt

### Continuous listening

A short press on the record key (mic key) keeps listening, like voice typing on iPhone or Pixel. Each time you finish speaking (about 0.7 seconds of silence), what you said so far is transcribed and entered at the cursor.

- You can keep typing on the keyboard while it listens. If recognition finishes while you have composing text, that text is committed first
- Text you're still speaking appears in the field as uncommitted text (in apps like Termux, it appears in the bar). It disappears briefly when you press a key and comes back while you speak
- Press again to stop (if you're mid-sentence, what you've said so far is entered). It also stops after the silence time set in settings (1 minute by default), when you move to another input field, or when you say 止めて
- The mic key on the on-screen keyboard changes color while listening

### Spoken symbols

Say words like "くてん" or "とうてん" to enter symbols (you can turn this off with "Speak punctuation" in settings). You can add or remove words under "Spoken symbols" in settings ("Reset to default" restores the original list).

| Say | Enters |
|---|---|
| くてん / まる at the end of a sentence | 。 |
| とうてん / てん at a clause break | 、 |
| かいぎょう, あたらしいぎょう, 次の行 | Line break |
| かいだんらく, 次の段落 | Two line breaks |
| はてなマーク, ぎもんふ | ？ |
| びっくりマーク, かんたんふ | ！ |
| かぎかっこ / かぎかっことじ | 「 / 」 |
| 二重かぎかっこ / 二重かぎかっこ閉じ | 『 / 』 |
| かっこ (かっこ開く) / かっことじ | （ / ） |
| なかぐろ, 三点リーダー, スラッシュ, アットマーク | ・ … ／ ＠ |

- まる and てん become symbols only at the end of a sentence or clause (followed by the end or punctuation). 丸い, 点数 and 100点 stay as they are
- When recognition mishears くてん as 九点 or 点, it still becomes 。 at a sentence break (てん right after です or ます becomes 。)
- If this overlaps with punctuation the model added itself, the two are merged into one. Ordinary words such as "かっこいい" (cool) are left as they are

### Voice commands

Pause briefly (about 0.6 seconds) before and after saying only a command to run it (turn off with "Voice commands" in settings). The same words inside a sentence are typed as text (メッセージを送信してから寝ます stays as text).

| Say | Does |
|---|---|
| 送信して, 送って | Send (Enter in fields without a send button) |
| 次へ | Next input field |
| 取り消して, 元に戻して | Delete the text you just entered by voice |
| 全部消して, すべて消去 | Clear the field |
| 止めて, ストップ | Stop continuous listening |

- 送信して, 次へ, 全部消して and 止めて only work as the last thing in a recording (they can't be undone)
- Polite forms such as 〜してください or 〜お願いします work too
- The command that was recognized is shown briefly near the cursor

### Fixing what was entered

- Pressing Backspace right after voice input deletes the whole entered text (like a glide word)
- If the text shown while you were speaking differs from what was entered, both appear in the candidate window. Pick the in-progress text to swap it in
- Text entered by voice can be reconverted by selecting it and pressing Space (Kanade looks up readings in a reverse dictionary; this also works for kanji typed with other keyboards)

### Discarding a recording

Press Esc while recording, or slide sideways off the mic key before releasing, to discard the recording without entering anything.

### Other settings

- Punctuation added by recognition: when off, the 、 and 。 that the model adds by itself are removed, leaving only punctuation you speak
- Remove fillers: removes えー, えーと, あのー, うーん and similar (off by default)
- Use the user dictionary for voice: replaces parts of recognized text whose reading matches a user dictionary entry with the registered word (this works when recognition writes the same reading in other kanji, not when the reading differs)
- Silence before continuous listening stops: 30 s, 1 min (default), 2 min, or never

## Display language

Under "Display language" in settings, you can show Kanade's screens in Japanese, English, or your device's language (Android 13 and later; you can also choose it under "App languages" in the system settings).

## Exporting and importing settings

"Export and import" in settings writes your settings, user dictionary, and snippets to a single file (JSON). Use it to move to another device or to keep a backup.

- Learned conversions, clipboard history, key logs, and voice models are not included
- Importing replaces your current settings, user dictionary, and snippets with the file's contents. If you may want to go back, export your current settings first
- If you had applied an imported voice model and the new device doesn't have it, Kanade uses an available model instead

## Apps that can't show composing text

In apps like Termux that can't display composing text properly, Kanade shows the composing text in the candidate window and sends only committed text to the app. You can add apps under "Apps that show typing in the bar" in settings (`com.termux` by default).
