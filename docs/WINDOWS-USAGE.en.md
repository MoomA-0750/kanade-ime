# Using Kanade on Windows

Kanade brings the input of the Android app (live conversion, zenz reranking and voice input) to Windows. The keys work the same as in the Android app.

## Install and uninstall

- Extract `Kanade-windows-x64.zip` and run `powershell -ExecutionPolicy Bypass -File install.ps1` in PowerShell as administrator
  - The program goes to `C:\Program Files\Kanade`, dictionaries and models to `%LOCALAPPDATA%\Kanade\data`, and settings, the user dictionary and learning to `%APPDATA%\Kanade`
  - Kanade is added to the Japanese keyboards (Microsoft IME stays. To remove it, open Settings → Time & language → Language & region → Japanese → Keyboards)
  - The engine starts when you sign in. If it is not running, the first key starts it (letters stay unconverted for a second or two)
- Uninstall: `powershell -ExecutionPolicy Bypass -File "C:\Program Files\Kanade\uninstall.ps1"` (add `-Purge` to delete settings and dictionaries too)

## Typing

- Choose Kanade with Win+Space
- Japanese on/off: tap Right Alt, Ctrl+Space, Henkan (on) / Muhenkan (off), or Hankaku/Zenkaku. "あ" or "A" appears near the cursor when you switch
- The input indicator on the taskbar (where Microsoft IME shows it) shows "あ" or "A". Left-click to switch, right-click for a menu with settings and more
- Type in romaji and the converted text appears as you type (live conversion). Press Enter to commit
- Press Space to convert, then pick from the candidate window with Space, ↑↓, number keys or a click. ←→ moves between segments and Shift+←→ changes where they split
- F6–F10 (or Ctrl+U/I/O/P/T) convert to hiragana, katakana, half-width katakana, full-width letters and half-width letters
- Hold F12 to record and release it to type the sentence with punctuation (choose the model in settings; the default is whisper small). Text appears near the cursor while you speak

## Settings

Open them from the right-click menu of the taskbar input indicator or from "Kanade Settings" in the Start menu:

- Input: live conversion, full width, punctuation, bracket pairs, keys to switch, learning (and clearing it), display language
- Voice input: spoken punctuation, the model for the final text (parakeet, ReazonSpeech, SenseVoice, whisper small/kotoba/turbo: download, delete, use), microphone settings
- User dictionary and snippets: add, edit and delete in a list, or edit everything as text
- Import and export: import Android settings (the JSON from Export settings), export a file the Android app can read, open the settings folder and the log
- Display language: Same as Windows, 日本語 or English. It changes this window, the taskbar menu and the messages near the cursor
- Changes take effect right away

## Not yet supported

- Continuous listening mode
- In apps using the older input framework (IMM), clicking a candidate commits the candidate that was selected instead
- In apps using the older input framework (IMM), snippets do not work while Japanese is off
